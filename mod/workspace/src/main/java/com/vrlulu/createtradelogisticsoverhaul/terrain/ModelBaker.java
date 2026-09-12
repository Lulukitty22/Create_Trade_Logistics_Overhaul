package com.vrlulu.createtradelogisticsoverhaul.terrain;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns Minecraft's baked quads into the flat quad format the browser meshes with.
 *
 * <p>24 floats per quad: 4 corners (xyz, block units 0..1), 4 uv pairs (0..1 within the sprite),
 * texture layer, tinted flag, cull direction (-1..5) and a shade factor. Same layout the Python
 * prototype produced, so the page needs no changes.
 */
public final class ModelBaker {
    public static final int FLOATS_PER_QUAD = 24;
    /** +x, -x, +y, -y, +z, -z — the page's direction order. */
    private static final float[] SHADE = {0.6f, 0.6f, 1.0f, 0.5f, 0.8f, 0.8f};
    private static final int STRIDE = 8;   // ints per vertex in DefaultVertexFormat.BLOCK

    private ModelBaker() {
    }

    public static int dirIndex(Direction dir) {
        if (dir == null) {
            return -1;
        }
        return switch (dir) {
            case EAST -> 0;
            case WEST -> 1;
            case UP -> 2;
            case DOWN -> 3;
            case SOUTH -> 4;
            case NORTH -> 5;
        };
    }

    /** Converts one baked quad; cullDir is the face it hides behind, or -1 for always drawn. */
    public static float[] convert(BakedQuad quad, int layer, int cullDir) {
        int[] v = quad.getVertices();
        if (v.length < 4 * STRIDE) {
            return null;
        }
        TextureAtlasSprite sprite = quad.getSprite();
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        float du = u1 - u0, dv = v1 - v0;
        float[] out = new float[FLOATS_PER_QUAD];
        for (int i = 0; i < 4; i++) {
            out[i * 3] = Float.intBitsToFloat(v[i * STRIDE]);
            out[i * 3 + 1] = Float.intBitsToFloat(v[i * STRIDE + 1]);
            out[i * 3 + 2] = Float.intBitsToFloat(v[i * STRIDE + 2]);
            float au = Float.intBitsToFloat(v[i * STRIDE + 4]);
            float av = Float.intBitsToFloat(v[i * STRIDE + 5]);
            out[12 + i * 2] = du == 0 ? 0 : (au - u0) / du;          // sprite-local uv
            out[13 + i * 2] = dv == 0 ? 0 : (av - v0) / dv;
        }
        int face = dirIndex(quad.getDirection());
        out[20] = layer;
        out[21] = quad.isTinted() ? 1 : 0;
        out[22] = cullDir;
        out[23] = quad.isShade() && face >= 0 ? SHADE[face] : 1.0f;
        return out;
    }

    /**
     * A plain box, used to stand in for blocks the game draws with code (chests, beds, ...).
     * Sized from the block's own shape so it at least occupies the right space.
     */
    public static List<float[]> box(AABB bounds, int layer) {
        float x0 = (float) bounds.minX, y0 = (float) bounds.minY, z0 = (float) bounds.minZ;
        float x1 = (float) bounds.maxX, y1 = (float) bounds.maxY, z1 = (float) bounds.maxZ;
        List<float[]> quads = new ArrayList<>(6);
        // corners per face, counter-clockwise seen from outside
        quads.add(face(new float[]{x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0}, layer, 0, z0, z1, y0, y1));
        quads.add(face(new float[]{x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1}, layer, 1, z0, z1, y0, y1));
        quads.add(face(new float[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0}, layer, 2, x0, x1, z0, z1));
        quads.add(face(new float[]{x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1}, layer, 3, x0, x1, z0, z1));
        quads.add(face(new float[]{x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1}, layer, 4, x0, x1, y0, y1));
        quads.add(face(new float[]{x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0}, layer, 5, x0, x1, y0, y1));
        return quads;
    }

    private static float[] face(float[] corners, int layer, int dir, float a0, float a1, float b0, float b1) {
        float[] out = new float[FLOATS_PER_QUAD];
        System.arraycopy(corners, 0, out, 0, 12);
        float[] uv = {a0, 1 - b1, a0, 1 - b0, a1, 1 - b0, a1, 1 - b1};
        System.arraycopy(uv, 0, out, 12, 8);
        out[20] = layer;
        out[21] = 0;
        out[22] = dir == 3 ? 3 : -1;     // only the bottom hides against the block below
        out[23] = SHADE[dir];
        return out;
    }
}
