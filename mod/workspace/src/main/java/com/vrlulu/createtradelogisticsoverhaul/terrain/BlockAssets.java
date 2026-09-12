package com.vrlulu.createtradelogisticsoverhaul.terrain;

import com.mojang.blaze3d.platform.NativeImage;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns block states into what the browser needs to draw them: a texture layer per face and a
 * classification (cube, glass-like, water, decoration).
 *
 * <p>Unlike the Python prototype, this reads the game's own baked models and resource manager, so
 * resource packs, modded blocks and custom model loaders all come out right.
 */
public class BlockAssets {
    public static final int TEX = 16;
    private static final RandomSource RANDOM = RandomSource.create(42L);
    private static final int STRIDE = 8;   // ints per vertex in DefaultVertexFormat.BLOCK
    private static final Direction[] DIRS = {
            Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH};

    /**
     * kind: see TerrainPalette; faces: texture layer per direction; tintMask: bit per direction;
     * model: index into the baked model list, or -1 for plain cubes.
     */
    public record BlockInfo(int kind, int[] faces, int tintMask, boolean cubeAtLod, int model,
                           int rot, boolean waterlogged) {
    }

    private final Map<String, Integer> layerByKey = new HashMap<>();
    private final List<byte[]> layers = new ArrayList<>();     // TEX*TEX*4 RGBA each
    private final List<int[]> layerAverages = new ArrayList<>();
    private final List<float[]> models = new ArrayList<>();    // one float[n * 24] per model
    private final Map<BlockState, BlockInfo> infoCache = new java.util.concurrent.ConcurrentHashMap<>();

    public BlockAssets() {
        byte[] missing = new byte[TEX * TEX * 4];
        for (int i = 0; i < TEX * TEX; i++) {
            missing[i * 4] = (byte) 0xC0;
            missing[i * 4 + 1] = (byte) 0x40;
            missing[i * 4 + 2] = (byte) 0xC0;
            missing[i * 4 + 3] = (byte) 0xFF;
        }
        layers.add(missing);
        layerAverages.add(new int[]{192, 64, 192});
        layerByKey.put("<missing>", 0);
    }

    public synchronized int layerCount() {
        return layers.size();
    }

    /** Alpha-weighted average colour of a layer, used by the flat-colour mode. */
    public synchronized int[] layerAverage(int layer) {
        return layer >= 0 && layer < layerAverages.size() ? layerAverages.get(layer) : new int[]{128, 128, 128};
    }

    private static int[] averageOf(byte[] rgba) {
        long r = 0, g = 0, b = 0, a = 0;
        for (int i = 0; i < TEX * TEX; i++) {
            int alpha = rgba[i * 4 + 3] & 0xFF;
            r += (long) (rgba[i * 4] & 0xFF) * alpha;
            g += (long) (rgba[i * 4 + 1] & 0xFF) * alpha;
            b += (long) (rgba[i * 4 + 2] & 0xFF) * alpha;
            a += alpha;
        }
        return a == 0 ? new int[]{128, 128, 128} : new int[]{(int) (r / a), (int) (g / a), (int) (b / a)};
    }

    public synchronized int modelCount() {
        return models.size();
    }

    /** "VXM1", u32 first index, u32 count, u32 quad offsets[count + 1], then the quad floats. */
    public synchronized byte[] modelsBlob() {
        int quads = 0;
        for (float[] m : models) {
            quads += m.length / ModelBaker.FLOATS_PER_QUAD;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer
                .allocate(12 + (models.size() + 1) * 4 + quads * ModelBaker.FLOATS_PER_QUAD * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.put("VXM1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(0).putInt(models.size());
        int offset = 0;
        buf.putInt(0);
        for (float[] m : models) {
            offset += m.length / ModelBaker.FLOATS_PER_QUAD;
            buf.putInt(offset);
        }
        for (float[] m : models) {
            for (float f : m) {
                buf.putFloat(f);
            }
        }
        return buf.array();
    }

    public synchronized byte[] atlasBytes() {
        byte[] out = new byte[layers.size() * TEX * TEX * 4];
        for (int i = 0; i < layers.size(); i++) {
            System.arraycopy(layers.get(i), 0, out, i * TEX * TEX * 4, TEX * TEX * 4);
        }
        return out;
    }

    /**
     * Classifies a block state and registers the textures and model it uses. Cached per state:
     * baking a model and reading textures is far too expensive to redo per voxel.
     */
    public BlockInfo infoFor(BlockState state) {
        BlockInfo cached = infoCache.get(state);
        if (cached != null) {
            return cached;
        }
        BlockInfo info = computeInfo(state);
        infoCache.put(state, info);
        return info;
    }

    private BlockInfo computeInfo(BlockState state) {
        if (state.isAir()) {
            return new BlockInfo(TerrainPalette.KIND_AIR, new int[6], 0, false, -1, 0, false);
        }
        FluidState fluid = state.getFluidState();
        if (state.getBlock() instanceof LiquidBlock && !fluid.isEmpty()) {
            boolean water = fluid.is(net.minecraft.tags.FluidTags.WATER);
            int layer = layerForFluid(fluid);
            int[] faces = new int[6];
            java.util.Arrays.fill(faces, layer);
            return new BlockInfo(water ? TerrainPalette.KIND_WATER : TerrainPalette.KIND_SOLID,
                    faces, water ? 0x3F : 0, false, -1, 0, water);
        }

        boolean leaves = state.getBlock() instanceof LeavesBlock;
        boolean fullCube = isFullCube(state);
        // A non-liquid block holding fluid (waterlogged slab, kelp, seagrass) still needs water drawn.
        boolean waterlogged = !state.getFluidState().isEmpty();
        int[] faces = new int[6];
        int tintMask = 0;
        int rot = 0;
        boolean anyFace = false;
        for (int d = 0; d < 6; d++) {
            TextureAtlasSprite sprite = spriteOf(state, DIRS[d]);
            if (sprite != null) {
                faces[d] = layerFor(sprite, leaves);
                anyFace = true;
                if (isTinted(state, DIRS[d])) {
                    tintMask |= 1 << d;
                }
                rot |= faceRotation(state, DIRS[d], d) << (2 * d);
            }
        }
        if (!anyFace) {
            int particle = layerFor(particleOf(state), leaves);
            java.util.Arrays.fill(faces, particle);
        }
        if (!fullCube) {
            // Plants, torches, rails, slabs, stairs, fences...: drawn from their real quads up close,
            // and as cubes at coarse LODs when they fill enough of the block (as Voxy's LODs do).
            int model = bakeModel(state, leaves);
            boolean chunky = volumeFraction(state) >= 0.2;
            if (tintMask == 0 && isTintedAnywhere(state)) {
                tintMask = 0x3F;      // tinted, but only on quads with no cull face
            }
            return new BlockInfo(TerrainPalette.KIND_MODEL, faces, tintMask, chunky, model, rot, waterlogged);
        }
        int kind = leaves || state.canOcclude() ? TerrainPalette.KIND_SOLID : TerrainPalette.KIND_GLASS;
        return new BlockInfo(kind, faces, tintMask, true, -1, rot, waterlogged);
    }

    /**
     * Collects a block's baked quads. Blocks the game draws with code (chests, beds, signs) have
     * none: those get a plain box the size of their own shape, or nothing if they have no shape.
     */
    private int bakeModel(BlockState state, boolean leaves) {
        List<float[]> quads = new ArrayList<>();
        try {
            BakedModel model = modelOf(state);
            for (Direction dir : Direction.values()) {
                for (BakedQuad quad : model.getQuads(state, dir, RANDOM, ModelData.EMPTY, null)) {
                    float[] converted = ModelBaker.convert(quad, layerFor(quad.getSprite(), leaves),
                            ModelBaker.dirIndex(dir));
                    if (converted != null) {
                        quads.add(converted);
                    }
                }
            }
            for (BakedQuad quad : model.getQuads(state, null, RANDOM, ModelData.EMPTY, null)) {
                float[] converted = ModelBaker.convert(quad, layerFor(quad.getSprite(), leaves), -1);
                if (converted != null) {
                    quads.add(converted);
                }
            }
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.debug("No baked quads for {}", state, t);
        }
        if (quads.isEmpty()) {
            try {
                var shape = state.getShape(Minecraft.getInstance().level, BlockPos.ZERO);
                if (shape.isEmpty()) {
                    return -1;
                }
                quads.addAll(ModelBaker.box(shape.bounds(), layerFor(particleOf(state), false)));
            } catch (Throwable t) {
                return -1;
            }
        }
        float[] flat = new float[quads.size() * ModelBaker.FLOATS_PER_QUAD];
        for (int i = 0; i < quads.size(); i++) {
            System.arraycopy(quads.get(i), 0, flat, i * ModelBaker.FLOATS_PER_QUAD, ModelBaker.FLOATS_PER_QUAD);
        }
        synchronized (this) {
            models.add(flat);
            return models.size() - 1;
        }
    }

    /** Roughly how much of the block its shape fills, deciding whether it survives as a cube far away. */
    private static double volumeFraction(BlockState state) {
        try {
            var shape = state.getCollisionShape(Minecraft.getInstance().level, BlockPos.ZERO);
            if (shape.isEmpty()) {
                shape = state.getShape(Minecraft.getInstance().level, BlockPos.ZERO);
            }
            double volume = 0;
            for (AABB box : shape.toAabbs()) {
                volume += box.getXsize() * box.getYsize() * box.getZsize();
            }
            return Math.min(volume, 1.0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * How far the texture is turned on this face, in quarter turns. Without it a sideways log shows
     * its bark running the wrong way. Found by matching the quad's UVs against the default
     * projection under each rotation.
     */
    private static int faceRotation(BlockState state, Direction dir, int dirIndex) {
        try {
            List<BakedQuad> quads = modelOf(state).getQuads(state, dir, RANDOM, ModelData.EMPTY, null);
            if (quads.isEmpty()) {
                return 0;
            }
            float[] quad = ModelBaker.convert(quads.get(0), 0, dirIndex);
            if (quad == null) {
                return 0;
            }
            int best = 0;
            double bestError = Double.MAX_VALUE;
            for (int r = 0; r < 4; r++) {
                double error = 0;
                for (int i = 0; i < 4; i++) {
                    float[] projected = project(dirIndex, quad[i * 3], quad[i * 3 + 1], quad[i * 3 + 2]);
                    float[] turned = turn(r, projected[0], projected[1]);
                    error += sq(turned[0] - quad[12 + i * 2]) + sq(turned[1] - quad[13 + i * 2]);
                }
                if (error < bestError) {
                    bestError = error;
                    best = r;
                }
            }
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Minecraft's default UV projection for a face direction (v runs downwards). */
    private static float[] project(int dir, float x, float y, float z) {
        return switch (dir) {
            case 0 -> new float[]{1 - z, 1 - y};
            case 1 -> new float[]{z, 1 - y};
            case 2 -> new float[]{x, z};
            case 3 -> new float[]{x, 1 - z};
            case 4 -> new float[]{x, 1 - y};
            default -> new float[]{1 - x, 1 - y};
        };
    }

    private static float[] turn(int quarterTurns, float u, float v) {
        return switch (quarterTurns) {
            case 1 -> new float[]{1 - v, u};
            case 2 -> new float[]{1 - u, 1 - v};
            case 3 -> new float[]{v, 1 - u};
            default -> new float[]{u, v};
        };
    }

    /**
     * Whether the model really is a solid cube: every direction must have a quad covering its whole
     * face. The block's outline shape can't be trusted here, because plants such as tall grass
     * inherit the default full-block shape and would be drawn as cubes.
     */
    private static boolean isFullCube(BlockState state) {
        try {
            BakedModel model = modelOf(state);
            for (Direction dir : Direction.values()) {
                boolean covered = false;
                for (BakedQuad quad : model.getQuads(state, dir, RANDOM, ModelData.EMPTY, null)) {
                    if (coversWholeFace(quad, dir)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean coversWholeFace(BakedQuad quad, Direction dir) {
        int[] v = quad.getVertices();
        if (v.length < 4 * STRIDE) {
            return false;
        }
        Direction.Axis axis = dir.getAxis();
        float plane = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f;
        float minA = 1, maxA = 0, minB = 1, maxB = 0;
        for (int i = 0; i < 4; i++) {
            float x = Float.intBitsToFloat(v[i * STRIDE]);
            float y = Float.intBitsToFloat(v[i * STRIDE + 1]);
            float z = Float.intBitsToFloat(v[i * STRIDE + 2]);
            float onPlane = axis == Direction.Axis.X ? x : axis == Direction.Axis.Y ? y : z;
            if (Math.abs(onPlane - plane) > 0.001f) {
                return false;
            }
            float a = axis == Direction.Axis.X ? y : x;
            float b = axis == Direction.Axis.Z ? y : z;
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }
        return minA < 0.001f && maxA > 0.999f && minB < 0.001f && maxB > 0.999f;
    }

    private static BakedModel modelOf(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }

    private static TextureAtlasSprite spriteOf(BlockState state, Direction dir) {
        try {
            List<BakedQuad> quads = modelOf(state).getQuads(state, dir, RANDOM, ModelData.EMPTY, null);
            if (!quads.isEmpty()) {
                return quads.get(0).getSprite();
            }
            for (BakedQuad quad : modelOf(state).getQuads(state, null, RANDOM, ModelData.EMPTY, null)) {
                if (quad.getDirection() == dir) {
                    return quad.getSprite();
                }
            }
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.debug("No sprite for {} {}", state, dir, t);
        }
        return null;
    }

    private static boolean isTinted(BlockState state, Direction dir) {
        try {
            for (BakedQuad quad : modelOf(state).getQuads(state, dir, RANDOM, ModelData.EMPTY, null)) {
                if (quad.isTinted()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return false;
    }

    /**
     * True if any quad of the model is tinted, including the ones with no cull direction. Cross
     * models (grass, ferns, flowers) return everything that way, and without this they lost their
     * biome colour and showed their greyscale texture raw.
     */
    private static boolean isTintedAnywhere(BlockState state) {
        try {
            BakedModel model = modelOf(state);
            for (BakedQuad quad : model.getQuads(state, null, RANDOM, ModelData.EMPTY, null)) {
                if (quad.isTinted()) {
                    return true;
                }
            }
            for (Direction dir : Direction.values()) {
                for (BakedQuad quad : model.getQuads(state, dir, RANDOM, ModelData.EMPTY, null)) {
                    if (quad.isTinted()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return false;
    }

    private static TextureAtlasSprite particleOf(BlockState state) {
        try {
            return modelOf(state).getParticleIcon(ModelData.EMPTY);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fluids are drawn by their own renderer and have no baked quads, so their texture comes from
     * the fluid type. Without this, water fell back to the missing-texture layer.
     */
    private int layerForFluid(FluidState fluid) {
        try {
            ResourceLocation still = IClientFluidTypeExtensions.of(fluid).getStillTexture();
            if (still != null) {
                return layerForTexture(still, true);
            }
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.debug("No fluid texture for {}", fluid, t);
        }
        return layerForTexture(ResourceLocation.withDefaultNamespace("block/water_still"), true);
    }

    /** Registers a texture by name (for sprites we can't reach through a baked quad). */
    private synchronized int layerForTexture(ResourceLocation name, boolean fill) {
        String key = name + (fill ? "#opaque" : "");
        Integer existing = layerByKey.get(key);
        if (existing != null) {
            return existing;
        }
        byte[] pixels = readTexture(name, fill);
        layers.add(pixels);
        layerAverages.add(averageOf(pixels));
        int index = layers.size() - 1;
        layerByKey.put(key, index);
        return index;
    }

    /** Registers a sprite's pixels as a texture-array layer. fill=true makes holes opaque (leaves). */
    private synchronized int layerFor(TextureAtlasSprite sprite, boolean fill) {
        if (sprite == null) {
            return 0;
        }
        ResourceLocation name = sprite.contents().name();
        String key = name + (fill ? "#opaque" : "");
        Integer existing = layerByKey.get(key);
        if (existing != null) {
            return existing;
        }
        byte[] pixels = readTexture(name, fill);
        layers.add(pixels);
        layerAverages.add(averageOf(pixels));
        int index = layers.size() - 1;
        layerByKey.put(key, index);
        return index;
    }

    /**
     * Reads a block texture through the resource manager, so resource packs apply. Animated
     * textures are taller than they are wide: only the first frame is used.
     */
    private static byte[] readTexture(ResourceLocation sprite, boolean fill) {
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(
                sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        byte[] out = new byte[TEX * TEX * 4];
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(file);
             NativeImage image = NativeImage.read(in)) {
            int size = Math.min(image.getWidth(), image.getHeight());
            long rSum = 0, gSum = 0, bSum = 0, aSum = 0;
            int[] rgba = new int[TEX * TEX * 4];
            for (int y = 0; y < TEX; y++) {
                for (int x = 0; x < TEX; x++) {
                    int px = image.getPixelRGBA(x * size / TEX, y * size / TEX);   // ABGR
                    int r = px & 0xFF, g = (px >> 8) & 0xFF, b = (px >> 16) & 0xFF, a = (px >>> 24) & 0xFF;
                    int i = (y * TEX + x) * 4;
                    rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b; rgba[i + 3] = a;
                    rSum += (long) r * a; gSum += (long) g * a; bSum += (long) b * a; aSum += a;
                }
            }
            int avgR = aSum == 0 ? 128 : (int) (rSum / aSum);
            int avgG = aSum == 0 ? 128 : (int) (gSum / aSum);
            int avgB = aSum == 0 ? 128 : (int) (bSum / aSum);
            for (int i = 0; i < TEX * TEX; i++) {
                int a = rgba[i * 4 + 3];
                if (fill && a < 255) {       // fast-graphics style leaves: holes become leaf colour
                    out[i * 4] = (byte) ((rgba[i * 4] * a + avgR * 55 / 100 * (255 - a)) / 255);
                    out[i * 4 + 1] = (byte) ((rgba[i * 4 + 1] * a + avgG * 55 / 100 * (255 - a)) / 255);
                    out[i * 4 + 2] = (byte) ((rgba[i * 4 + 2] * a + avgB * 55 / 100 * (255 - a)) / 255);
                    out[i * 4 + 3] = (byte) 255;
                } else {
                    out[i * 4] = (byte) rgba[i * 4];
                    out[i * 4 + 1] = (byte) rgba[i * 4 + 1];
                    out[i * 4 + 2] = (byte) rgba[i * 4 + 2];
                    out[i * 4 + 3] = (byte) a;
                }
            }
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.debug("Could not read texture {}", file, t);
            java.util.Arrays.fill(out, (byte) 0x80);
        }
        return out;
    }
}
