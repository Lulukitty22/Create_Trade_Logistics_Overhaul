package com.vrlulu.createtradelogisticsoverhaul.terrain;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Converts Voxy sections into the wire format the page meshes from: global palette ids, cave air
 * marked, palette compressed. Mirrors the Python prototype's terrain_store (see DESIGN.md).
 */
public class TerrainStore {
    private static final int VOXELS = 32 * 32 * 32;
    public static final int MISSING = 0xFFFF;

    private final TerrainPalette palette = new TerrainPalette();
    private final LongOpenHashSet index = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<int[]> columnYs = new Long2ObjectOpenHashMap<>();
    private long indexedAt;
    private int maxLod;

    public TerrainPalette palette() {
        return palette;
    }

    public int maxLod() {
        return maxLod;
    }

    public synchronized int sectionCount() {
        return index.size();
    }

    /** Rebuilds the section index (keys only, so it stays cheap), at most every minIntervalMs. */
    public void refreshIndex(WorldEngine engine, long minIntervalMs) {
        synchronized (this) {
            if (System.currentTimeMillis() - indexedAt < minIntervalMs) {
                return;
            }
        }
        LongOpenHashSet found = new LongOpenHashSet();
        if (!VoxyBridge.forEachStoredSection(engine, found::add)) {
            return;
        }
        Long2ObjectOpenHashMap<IntArrayList> columns = new Long2ObjectOpenHashMap<>();
        int top = 0;
        for (long key : found) {
            int lod = WorldEngine.getLevel(key);
            top = Math.max(top, lod);
            long column = columnKey(lod, WorldEngine.getX(key), WorldEngine.getZ(key));
            columns.computeIfAbsent(column, k -> new IntArrayList()).add(WorldEngine.getY(key));
        }
        synchronized (this) {
            index.clear();
            index.addAll(found);
            columnYs.clear();
            columns.forEach((k, v) -> columnYs.put(k.longValue(), v.toIntArray()));
            maxLod = top;
            indexedAt = System.currentTimeMillis();
        }
    }

    private static long columnKey(int lod, int x, int z) {
        return ((long) lod << 56) | ((long) (x & 0xFFFFFF) << 28) | (z & 0xFFFFFFL);
    }

    public synchronized List<int[]> sectionsAt(int lod) {
        List<int[]> out = new ArrayList<>();
        for (long key : index) {
            if (WorldEngine.getLevel(key) == lod) {
                out.add(new int[]{WorldEngine.getX(key), WorldEngine.getY(key), WorldEngine.getZ(key)});
            }
        }
        return out;
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
                gids[i] = palette.gidFor(mapper, Mapper.getBlockId(v));
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

    /** Columns of this section that have something drawn above them, in the sections higher up. */
    private boolean[] coverFromAbove(WorldEngine engine, int lod, int x, int y, int z) {
        boolean[] cover = new boolean[1024];
        int[] ys;
        synchronized (this) {
            ys = columnYs.get(columnKey(lod, x, z));
        }
        if (ys == null) {
            return cover;
        }
        Mapper mapper = engine.getMapper();
        for (int above : ys) {
            if (above <= y) {
                continue;
            }
            WorldSection s = engine.acquireIfExists(lod, x, above, z);
            if (s == null) {
                continue;
            }
            try {
                long[] raw = s._unsafeGetRawDataArray();
                for (int i = 0; i < VOXELS; i++) {
                    if (!cover[i & 1023] && palette.drawn(palette.gidFor(mapper, Mapper.getBlockId(raw[i])))) {
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
