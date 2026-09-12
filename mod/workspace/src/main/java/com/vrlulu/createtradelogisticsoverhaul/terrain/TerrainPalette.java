package com.vrlulu.createtradelogisticsoverhaul.terrain;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;

/**
 * The global palette the browser meshes against: one entry per (block, biome-if-tinted), plus fixed
 * slots for air and cave air. Mirrors the prototype's palette (see DESIGN.md).
 */
public class TerrainPalette {
    public static final int KIND_AIR = 0, KIND_SOLID = 1, KIND_WATER = 2, KIND_CAVE = 3,
            KIND_GLASS = 4, KIND_MODEL = 5;
    public static final int FLAG_WATERLOGGED = 1 << 6, FLAG_CUBE_AT_LOD = 1 << 7;
    public static final int GID_AIR = 0, GID_CAVE = 1;
    private static final int[] CAVE_RGB = {26, 24, 30};
    private static final BlockAssets.BlockInfo AIR_INFO =
            new BlockAssets.BlockInfo(KIND_AIR, new int[6], 0, false, -1, 0, false);

    private final BlockAssets assets = new BlockAssets();
    private final Long2IntOpenHashMap gidByKey = new Long2IntOpenHashMap();   // block<<32 | biome+1
    private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<BlockAssets.BlockInfo> infoByBlockId =
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    private final List<Integer> kinds = new ArrayList<>();
    private final List<int[]> top = new ArrayList<>(), side = new ArrayList<>(), tint = new ArrayList<>();
    private final List<int[]> faces = new ArrayList<>();
    private final List<Integer> flags = new ArrayList<>(), blockNameIndex = new ArrayList<>(),
            biomeIndex = new ArrayList<>(), modelIndex = new ArrayList<>(), rotations = new ArrayList<>();
    private final List<String> names = new ArrayList<>(), biomeNames = new ArrayList<>();
    private int waterLayer;

    public TerrainPalette() {
        gidByKey.defaultReturnValue(-1);
        add(KIND_AIR, new int[]{0, 0, 0}, new int[]{0, 0, 0}, new int[6], 0, 0, -1, -1, -1, 0);
        add(KIND_CAVE, CAVE_RGB, CAVE_RGB, new int[6], 0, 0, -1, -1, -1, 0);
    }

    public BlockAssets assets() {
        return assets;
    }

    private int add(int kind, int[] topRgb, int[] sideRgb, int[] faceLayers, int tintMask, int extraFlags,
                    int nameIdx, int biomeIdx, int model, int rot) {
        kinds.add(kind);
        top.add(topRgb);
        side.add(sideRgb);
        faces.add(faceLayers);
        tint.add(new int[]{255, 255, 255});
        flags.add(tintMask | extraFlags);
        blockNameIndex.add(nameIdx);
        biomeIndex.add(biomeIdx);
        modelIndex.add(model);
        rotations.add(rot);
        return kinds.size() - 1;
    }

    /** Global id for a Voxy (block, biome) pair, registering it on first sight. */
    public synchronized int gidFor(Mapper mapper, int blockId, int biomeId) {
        BlockAssets.BlockInfo info = infoByBlockId.get(blockId);
        BlockState state = null;
        if (info == null) {
            state = stateOf(mapper, blockId);
            if (state == null || state.isAir()) {
                infoByBlockId.put(blockId, AIR_INFO);
                return GID_AIR;
            }
            info = assets.infoFor(state);
            infoByBlockId.put(blockId, info);
        }
        if (info.kind() == KIND_AIR) {
            return GID_AIR;
        }
        boolean tinted = info.tintMask() != 0;
        long key = ((long) blockId << 32) | (tinted ? biomeId + 1 : 0);
        int existing = gidByKey.get(key);
        if (existing >= 0) {
            return existing;
        }
        if (state == null) {
            state = stateOf(mapper, blockId);
            if (state == null) {
                return GID_AIR;
            }
        }
        int[] tintRgb = info.tintMask() != 0 ? tintFor(state, mapper, biomeId) : new int[]{255, 255, 255};
        int[] rgb = flatColor(info, 2, tintRgb);
        int[] darker = flatColor(info, 4, tintRgb);
        String name = state.getBlock().builtInRegistryHolder().key().location().toString();
        names.add(name);
        int biomeIdx = -1;
        if (tinted) {
            String biome = biomeNameOf(mapper, biomeId);
            biomeIdx = biomeNames.indexOf(biome);
            if (biomeIdx < 0) {
                biomeNames.add(biome);
                biomeIdx = biomeNames.size() - 1;
            }
        }
        int extraFlags = (info.cubeAtLod() ? FLAG_CUBE_AT_LOD : 0) | (info.waterlogged() ? FLAG_WATERLOGGED : 0);
        int gid = add(info.kind(), rgb, darker, info.faces(), info.tintMask(), extraFlags,
                names.size() - 1, biomeIdx, info.model(), info.rot());
        if (info.kind() == KIND_WATER && waterLayer == 0) {
            waterLayer = info.faces()[2];
        }
        if (tinted) {
            tint.set(gid, tintRgb);
        }
        gidByKey.put(key, gid);
        return gid;
    }

    private static BlockState stateOf(Mapper mapper, int blockId) {
        try {
            return mapper.getBlockStateFromBlockId(blockId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String biomeNameOf(Mapper mapper, int biomeId) {
        try {
            for (Mapper.BiomeEntry entry : mapper.getBiomeEntries()) {
                if (entry.id == biomeId) {
                    return entry.biome;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "minecraft:plains";
    }

    /** Grass, foliage and water colours for the biome the voxel is actually in. */
    private static int[] tintFor(BlockState state, Mapper mapper, int biomeId) {
        int packed = 0xFFFFFF;
        // Leaves take the biome's foliage colour, including modded ones: Terralith's
        // forested_highlands really does declare a red foliage colour, and the game really does
        // draw red pines there. The map only differs in that it uses one colour per biome, so it
        // misses the per-block blending that softens the edges in game.
        try {
            Biome biome = Minecraft.getInstance().level.registryAccess()
                    .registryOrThrow(Registries.BIOME)
                    .get(ResourceLocation.parse(biomeNameOf(mapper, biomeId)));
            if (biome != null) {
                ColorResolver resolver = state.getBlock() instanceof LiquidBlock ? BiomeColors.WATER_COLOR_RESOLVER
                        : state.getBlock() instanceof LeavesBlock ? BiomeColors.FOLIAGE_COLOR_RESOLVER
                        : BiomeColors.GRASS_COLOR_RESOLVER;
                packed = resolver.getColor(biome, 0, 0);
            }
        } catch (Throwable ignored) {
            // keep white
        }
        return unpack(packed);
    }

    private static int[] unpack(int packed) {
        return new int[]{(packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF};
    }

    /** Average colour of the face's texture, tinted if that face is tinted. */
    private int[] flatColor(BlockAssets.BlockInfo info, int dir, int[] tintRgb) {
        int[] base = assets.layerAverage(info.faces()[dir]);
        boolean tinted = (info.tintMask() & (1 << dir)) != 0;
        int shade = dir == 2 ? 100 : 80;
        return new int[]{
                base[0] * (tinted ? tintRgb[0] : 255) / 255 * shade / 100,
                base[1] * (tinted ? tintRgb[1] : 255) / 255 * shade / 100,
                base[2] * (tinted ? tintRgb[2] : 255) / 255 * shade / 100};
    }

    private static int[] mapColorOf(BlockState state) {
        MapColor mc;
        try {
            mc = state.getMapColor(Minecraft.getInstance().level, BlockPos.ZERO);
        } catch (Throwable t) {
            mc = MapColor.STONE;
        }
        int packed = mc == null ? MapColor.STONE.col : mc.col;
        return new int[]{(packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF};
    }

    public synchronized int waterLayer() {
        return waterLayer;
    }

    public synchronized int size() {
        return kinds.size();
    }

    /** True for anything that covers the air below it (used when marking cave air). */
    public synchronized boolean drawn(int gid) {
        int k = kinds.get(gid);
        return k == KIND_SOLID || k == KIND_WATER || k == KIND_GLASS
                || (k == KIND_MODEL && (flags.get(gid) & FLAG_CUBE_AT_LOD) != 0);
    }

    public synchronized void writeJson(StringBuilder out) {
        out.append("\"palette\":{");
        appendInts(out, "kind", kinds);
        out.append(',');
        appendRgb(out, "top", top);
        out.append(',');
        appendRgb(out, "side", side);
        out.append(',');
        appendRgb(out, "tint", tint);
        out.append(',');
        appendInts(out, "block", blockNameIndex);
        out.append(',');
        appendInts(out, "biome", biomeIndex);
        out.append(',');
        appendInts(out, "flags", flags);
        out.append(",\"faces\":[");
        for (int i = 0; i < faces.size(); i++) {
            int[] f = faces.get(i);
            for (int d = 0; d < 6; d++) {
                out.append(i == 0 && d == 0 ? "" : ",").append(f[d]);
            }
        }
        out.append("],\"model\":[");
        for (int i = 0; i < modelIndex.size(); i++) {
            out.append(i == 0 ? "" : ",").append(modelIndex.get(i));
        }
        out.append("],\"rot\":[");
        for (int i = 0; i < rotations.size(); i++) {
            out.append(i == 0 ? "" : ",").append(rotations.get(i));
        }
        out.append("]},\"names\":[");
        appendStrings(out, names);
        out.append("],\"biomes\":[");
        appendStrings(out, biomeNames);
        out.append(']');
    }

    private static void appendStrings(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : ",").append('"').append(values.get(i)).append('"');
        }
    }

    private static void appendInts(StringBuilder out, String key, List<Integer> values) {
        out.append('"').append(key).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : ",").append(values.get(i));
        }
        out.append(']');
    }

    private static void appendRgb(StringBuilder out, String key, List<int[]> values) {
        out.append('"').append(key).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            int[] c = values.get(i);
            out.append(i == 0 ? "" : ",").append(c[0]).append(',').append(c[1]).append(',').append(c[2]);
        }
        out.append(']');
    }
}
