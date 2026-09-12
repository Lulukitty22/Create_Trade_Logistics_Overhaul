package com.vrlulu.createtradelogisticsoverhaul.terrain;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Converts Voxy sections into the wire format the page meshes from: global palette ids, cave air
 * marked, palette compressed. Mirrors the Python prototype's terrain_store (see DESIGN.md).
 *
 * <p>Nothing is scanned up front. The octree roots are found by flood-filling outwards from the
 * section the player is in, which touches only the explored area and its border.
 */
public class TerrainStore {
    private static final int VOXELS = 32 * 32 * 32;
    public static final int MISSING = 0xFFFF;
    /** A world is at most ~24 sections tall at LOD 0 (-64..320 plus headroom). */
    private static final int MAX_SECTION_Y = 24;
    private static final int MIN_SECTION_Y = -8;
    private static final long ROOTS_TTL_MS = 10_000;

    private final TerrainPalette palette = new TerrainPalette();
    private List<int[]> cachedRoots = List.of();
    private long rootsAt;
    private int rootsSeedX, rootsSeedZ;

    public TerrainPalette palette() {
        return palette;
    }

    public int maxLod() {
        return WorldEngine.MAX_LOD_LAYER;
    }

    /**
     * Every top-level section reachable from the one the player stands in, found by flood fill.
     * Cached briefly, and recomputed when the player moves to a different root.
     */
    public synchronized List<int[]> roots(WorldEngine engine, double px, double pz) {
        int lod = maxLod(), size = 32 << lod;
        int seedX = Math.floorDiv((int) Math.floor(px), size);
        int seedZ = Math.floorDiv((int) Math.floor(pz), size);
        boolean stale = System.currentTimeMillis() - rootsAt > ROOTS_TTL_MS;
        if (!stale && seedX == rootsSeedX && seedZ == rootsSeedZ && !cachedRoots.isEmpty()) {
            return cachedRoots;
        }
        List<int[]> found = floodFill(engine, lod, seedX, seedZ);
        cachedRoots = found;
        rootsAt = System.currentTimeMillis();
        rootsSeedX = seedX;
        rootsSeedZ = seedZ;
        return found;
    }

    private List<int[]> floodFill(WorldEngine engine, int lod, int seedX, int seedZ) {
        List<int[]> out = new ArrayList<>();
        LongOpenHashSet visitedColumns = new LongOpenHashSet();
        Deque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{seedX, seedZ});
        visitedColumns.add(columnKey(seedX, seedZ));
        while (!queue.isEmpty() && out.size() < 20_000) {
            long[] col = queue.poll();
            int x = (int) col[0], z = (int) col[1];
            boolean any = false;
            for (int y = MIN_SECTION_Y >> lod; y <= MAX_SECTION_Y >> lod; y++) {
                WorldSection s = engine.acquireIfExists(lod, x, y, z);
                if (s != null) {
                    s.release();
                    out.add(new int[]{x, y, z});
                    any = true;
                }
            }
            if (!any) {
                continue;                      // empty column: don't expand past it
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + d[0], nz = z + d[1];
                if (visitedColumns.add(columnKey(nx, nz))) {
                    queue.add(new long[]{nx, nz});
                }
            }
        }
        return out;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** One section, encoded for the page; a "missing" record when Voxy has no such section. */
    public byte[] record(WorldEngine engine, int lod, int x, int y, int z) {
        WorldSection section = engine.acquireIfExists(lod, x, y, z);
        if (section == null) {
            return header(lod, x, y, z, MISSING, 0, 0).array();
        }
        try {
            Mapper mapper = engine.getMapper();
            long[] raw = section._unsafeGetRawDataArray();
            int[] gids = new int[VOXELS];
            byte[] sky = new byte[VOXELS];
            for (int i = 0; i < VOXELS; i++) {
                long v = raw[i];
                gids[i] = palette.gidFor(mapper, Mapper.getBlockId(v), Mapper.getBiomeId(v));
                sky[i] = (byte) (Mapper.getLightId(v) & 15);
            }
            markCaveAir(engine, lod, x, y, z, gids, sky);
            return encode(lod, x, y, z, gids);
        } finally {
            section.release();
        }
    }

    /**
     * Air with no skylight that has something solid above it is cave air, which the page can hide.
     * A 16-block slice that is nearly all air with no skylight anywhere is open sky for which
     * Minecraft stored no light data, not a cave (see DESIGN.md).
     */
    private void markCaveAir(WorldEngine engine, int lod, int x, int y, int z, int[] gids, byte[] sky) {
        boolean[] covered = coverFromAbove(engine, lod, x, y, z);
        int slab = Math.max(1, 16 >> lod);
        for (int top = 32 - slab; top >= 0; top -= slab) {
            boolean skyGap = sliceIsUnlitSky(gids, sky, top, slab);
            for (int yy = top + slab - 1; yy >= top; yy--) {
                int base = yy << 10;
                if (!skyGap) {
                    for (int i = base; i < base + 1024; i++) {
                        if (gids[i] == TerrainPalette.GID_AIR && sky[i] == 0 && covered[i & 1023]) {
                            gids[i] = TerrainPalette.GID_CAVE;
                        }
                    }
                }
                for (int i = base; i < base + 1024; i++) {
                    if (palette.drawn(gids[i])) {
                        covered[i & 1023] = true;
                    }
                }
            }
        }
    }

    private boolean sliceIsUnlitSky(int[] gids, byte[] sky, int fromY, int height) {
        int air = 0;
        for (int yy = fromY; yy < fromY + height; yy++) {
            int base = yy << 10;
            for (int i = base; i < base + 1024; i++) {
                if (gids[i] == TerrainPalette.GID_AIR) {
                    air++;
                    if (sky[i] > 0) {
                        return false;
                    }
                }
            }
        }
        return air > height * 1024 * 9 / 10;
    }

    /**
     * Columns of this section with something drawn above them. Walks up section by section until
     * the world top, stopping after a couple of empty ones (no global index needed).
     */
    private boolean[] coverFromAbove(WorldEngine engine, int lod, int x, int y, int z) {
        boolean[] cover = new boolean[1024];
        Mapper mapper = engine.getMapper();
        int misses = 0;
        for (int above = y + 1; above <= (MAX_SECTION_Y >> lod) && misses < 3; above++) {
            WorldSection s = engine.acquireIfExists(lod, x, above, z);
            if (s == null) {
                misses++;
                continue;
            }
            misses = 0;
            try {
                long[] raw = s._unsafeGetRawDataArray();
                for (int i = 0; i < VOXELS; i++) {
                    if (!cover[i & 1023] && palette.drawn(palette.gidFor(mapper, Mapper.getBlockId(raw[i]), Mapper.getBiomeId(raw[i])))) {
                        cover[i & 1023] = true;
                    }
                }
            } finally {
                s.release();
            }
        }
        return cover;
    }

    /** i32 lod,x,y,z | u16 palLen | u16 flags | u16 palette[] (4-aligned) | u8 or u16 indices */
    private static byte[] encode(int lod, int x, int y, int z, int[] gids) {
        int[] localOf = new int[65536];
        Arrays.fill(localOf, -1);
        int[] local = new int[VOXELS];
        IntArrayList pal = new IntArrayList();
        for (int i = 0; i < VOXELS; i++) {
            int g = gids[i];
            int idx = localOf[g];
            if (idx < 0) {
                idx = pal.size();
                localOf[g] = idx;
                pal.add(g);
            }
            local[i] = idx;
        }
        if (pal.size() == 1 && pal.getInt(0) == TerrainPalette.GID_AIR) {
            return header(lod, x, y, z, 0, 0, 0).array();
        }
        boolean wide = pal.size() > 256;
        int palBytes = pal.size() * 2;
        palBytes += (4 - (palBytes % 4)) % 4;
        int indexBytes = pal.size() == 1 ? 0 : VOXELS * (wide ? 2 : 1);
        ByteBuffer buf = header(lod, x, y, z, pal.size(), wide ? 1 : 0, palBytes + indexBytes);
        for (int i = 0; i < pal.size(); i++) {
            buf.putShort((short) pal.getInt(i));
        }
        while (buf.position() % 4 != 0) {
            buf.put((byte) 0);
        }
        if (pal.size() > 1) {
            for (int i = 0; i < VOXELS; i++) {
                if (wide) {
                    buf.putShort((short) local[i]);
                } else {
                    buf.put((byte) local[i]);
                }
            }
        }
        return buf.array();
    }

    private static ByteBuffer header(int lod, int x, int y, int z, int palLen, int flags, int extra) {
        ByteBuffer buf = ByteBuffer.allocate(20 + extra).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(lod).putInt(x).putInt(y).putInt(z).putShort((short) palLen).putShort((short) flags);
        return buf;
    }
}
