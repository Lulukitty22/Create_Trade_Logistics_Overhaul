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
    private static final Direction[] DIRS = {
            Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH};

    /** kind: see TerrainPalette; faces: texture layer per direction; tintMask: bit per direction. */
    public record BlockInfo(int kind, int[] faces, int tintMask, boolean cubeAtLod) {
    }

    private final Map<String, Integer> layerByKey = new HashMap<>();
    private final List<byte[]> layers = new ArrayList<>();     // TEX*TEX*4 RGBA each

    public BlockAssets() {
        byte[] missing = new byte[TEX * TEX * 4];
        for (int i = 0; i < TEX * TEX; i++) {
            missing[i * 4] = (byte) 0xC0;
            missing[i * 4 + 1] = (byte) 0x40;
            missing[i * 4 + 2] = (byte) 0xC0;
            missing[i * 4 + 3] = (byte) 0xFF;
        }
        layers.add(missing);
        layerByKey.put("<missing>", 0);
    }

    public synchronized int layerCount() {
        return layers.size();
    }

    public synchronized byte[] atlasBytes() {
        byte[] out = new byte[layers.size() * TEX * TEX * 4];
        for (int i = 0; i < layers.size(); i++) {
            System.arraycopy(layers.get(i), 0, out, i * TEX * TEX * 4, TEX * TEX * 4);
        }
        return out;
    }

    /** Classifies a block state and registers the textures its faces use. */
    public BlockInfo infoFor(BlockState state) {
        if (state.isAir()) {
            return new BlockInfo(TerrainPalette.KIND_AIR, new int[6], 0, false);
        }
        FluidState fluid = state.getFluidState();
        if (state.getBlock() instanceof LiquidBlock && !fluid.isEmpty()) {
            boolean water = fluid.is(net.minecraft.tags.FluidTags.WATER);
            int layer = layerFor(spriteOf(state, Direction.UP), true);
            int[] faces = new int[6];
            java.util.Arrays.fill(faces, layer);
            return new BlockInfo(water ? TerrainPalette.KIND_WATER : TerrainPalette.KIND_SOLID,
                    faces, water ? 0x3F : 0, false);
        }

        boolean leaves = state.getBlock() instanceof LeavesBlock;
        boolean fullCube = isFullCube(state);
        int[] faces = new int[6];
        int tintMask = 0;
        boolean anyFace = false;
        for (int d = 0; d < 6; d++) {
            TextureAtlasSprite sprite = spriteOf(state, DIRS[d]);
            if (sprite != null) {
                faces[d] = layerFor(sprite, leaves);
                anyFace = true;
                if (isTinted(state, DIRS[d])) {
                    tintMask |= 1 << d;
                }
            }
        }
        if (!anyFace) {
            int particle = layerFor(particleOf(state), leaves);
            java.util.Arrays.fill(faces, particle);
        }
        if (!fullCube) {
            // Non-cubes (plants, torches, rails, slabs...) aren't drawn yet; solid-ish ones still
            // show as cubes far away, where Voxy's own LODs do the same.
            boolean chunky = Block.isShapeFullBlock(state.getShape(Minecraft.getInstance().level, BlockPos.ZERO))
                    || state.isCollisionShapeFullBlock(Minecraft.getInstance().level, BlockPos.ZERO);
            return new BlockInfo(TerrainPalette.KIND_MODEL, faces, tintMask, chunky);
        }
        int kind = leaves || state.canOcclude() ? TerrainPalette.KIND_SOLID : TerrainPalette.KIND_GLASS;
        return new BlockInfo(kind, faces, tintMask, true);
    }

    private static boolean isFullCube(BlockState state) {
        try {
            return Block.isShapeFullBlock(state.getShape(Minecraft.getInstance().level, BlockPos.ZERO));
        } catch (Throwable t) {
            return false;
        }
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

    private static TextureAtlasSprite particleOf(BlockState state) {
        try {
            return modelOf(state).getParticleIcon(ModelData.EMPTY);
        } catch (Throwable t) {
            return null;
        }
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
