package com.vrlulu.createtradelogisticsoverhaul.terrain;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;

/**
 * The global palette the browser meshes against: one entry per Voxy block id, plus fixed slots for
 * air and cave air.
 *
 * <p>Colours come from Minecraft's own map colours for now. Real textures and block models will
 * replace them later, using the game's baked models (which also covers modded blocks).
 */
public class TerrainPalette {
    public static final int KIND_AIR = 0, KIND_SOLID = 1, KIND_WATER = 2, KIND_CAVE = 3;
    public static final int GID_AIR = 0, GID_CAVE = 1;
    private static final int[] CAVE_RGB = {26, 24, 30};

    private final Int2IntOpenHashMap gidByBlockId = new Int2IntOpenHashMap();
    private final List<Integer> kinds = new ArrayList<>();
    private final List<int[]> top = new ArrayList<>(), side = new ArrayList<>();
    private final List<Integer> blockNameIndex = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Int2IntOpenHashMap nameIndexByBlockId = new Int2IntOpenHashMap();

    public TerrainPalette() {
        gidByBlockId.defaultReturnValue(-1);
        add(KIND_AIR, new int[]{0, 0, 0}, new int[]{0, 0, 0}, -1);
        add(KIND_CAVE, CAVE_RGB, CAVE_RGB, -1);
    }

    private int add(int kind, int[] topRgb, int[] sideRgb, int nameIdx) {
        kinds.add(kind);
        top.add(topRgb);
        side.add(sideRgb);
        blockNameIndex.add(nameIdx);
        return kinds.size() - 1;
    }

    /** Global id for a Voxy block id, registering it on first sight. */
    public synchronized int gidFor(Mapper mapper, int blockId) {
        int existing = gidByBlockId.get(blockId);
        if (existing >= 0) {
            return existing;
        }
        BlockState state;
        try {
            state = mapper.getBlockStateFromBlockId(blockId);
        } catch (Throwable t) {
            state = null;
        }
        int gid;
        if (state == null || state.isAir()) {
            gid = GID_AIR;
        } else {
            int[] rgb = colorOf(state);
            int[] darker = {rgb[0] * 8 / 10, rgb[1] * 8 / 10, rgb[2] * 8 / 10};
            String name = state.getBlock().builtInRegistryHolder().key().location().toString();
            names.add(name);
            gid = add(kindOf(state), rgb, darker, names.size() - 1);
        }
        gidByBlockId.put(blockId, gid);
        return gid;
    }

    private static int kindOf(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            return KIND_WATER;
        }
        return KIND_SOLID;
    }

    private static int[] colorOf(BlockState state) {
        MapColor mc;
        try {
            mc = state.getMapColor(Minecraft.getInstance().level, BlockPos.ZERO);
        } catch (Throwable t) {
            mc = MapColor.STONE;
        }
        int packed = mc == null ? MapColor.STONE.col : mc.col;
        return new int[]{(packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF};
    }

    public synchronized int size() {
        return kinds.size();
    }

    /** True for anything that covers the air below it (used when marking cave air). */
    public synchronized boolean drawn(int gid) {
        int k = kinds.get(gid);
        return k == KIND_SOLID || k == KIND_WATER;
    }

    /** Palette in the shape the page expects (see DESIGN.md, "Section wire format"). */
    public synchronized void writeJson(StringBuilder out) {
        out.append("\"palette\":{");
        appendInts(out, "kind", kinds);
        out.append(',');
        appendRgb(out, "top", top);
        out.append(',');
        appendRgb(out, "side", side);
        out.append(',');
        appendInts(out, "block", blockNameIndex);
        out.append(",\"biome\":[");
        for (int i = 0; i < kinds.size(); i++) {
            out.append(i == 0 ? "" : ",").append(-1);
        }
        out.append("],\"faces\":[");
        for (int i = 0; i < kinds.size() * 6; i++) {
            out.append(i == 0 ? "" : ",").append(0);
        }
        out.append("],\"tint\":[");
        for (int i = 0; i < kinds.size() * 3; i++) {
            out.append(i == 0 ? "" : ",").append(255);
        }
        out.append("],\"flags\":[");
        for (int i = 0; i < kinds.size(); i++) {
            out.append(i == 0 ? "" : ",").append(0);
        }
        out.append("],\"model\":[");
        for (int i = 0; i < kinds.size(); i++) {
            out.append(i == 0 ? "" : ",").append(-1);
        }
        out.append("],\"rot\":[");
        for (int i = 0; i < kinds.size(); i++) {
            out.append(i == 0 ? "" : ",").append(0);
        }
        out.append("]},\"names\":[");
        for (int i = 0; i < names.size(); i++) {
            out.append(i == 0 ? "" : ",").append('"').append(names.get(i)).append('"');
        }
        out.append("],\"biomes\":[]");
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
