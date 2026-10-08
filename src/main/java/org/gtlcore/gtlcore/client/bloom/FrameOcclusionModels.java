package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.api.block.MaterialBlock;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Keep a frame's cutout base in the opaque depth pass, while preserving its translucent overlay. */
public final class FrameOcclusionModels {

    public static void modifyModels(ModelEvent.ModifyBakingResult event) {
        // Work only on this bake's immutable models and sprites. No live world or GL calls,
        // no texture-name assumptions, and no retained pixels across resource reloads.
        Map<SpriteContents, Boolean> cutoutSprites = new IdentityHashMap<>();
        int changed = 0;
        for (var entry : event.getModels().entrySet()) {
            if (!(entry.getKey() instanceof ModelResourceLocation id) || id.getVariant().equals("inventory")) continue;
            var block = BuiltInRegistries.BLOCK.get(new ResourceLocation(id.getNamespace(), id.getPath()));
            if (!(block instanceof MaterialBlock material) || material.tagPrefix != TagPrefix.frameGt) continue;
            BakedModel original = entry.getValue();
            // Custom/connected models may depend on position or model data. Never freeze them
            // by sampling their quads during baking; vanilla JSON frame models are static.
            if (original.getClass() != SimpleBakedModel.class) continue;
            BlockState state = block.defaultBlockState();
            var random = RandomSource.create(0);
            var layers = original.getRenderTypes(state, random, ModelData.EMPTY);
            if (!layers.asList().equals(List.of(RenderType.translucent()))) continue;
            var model = new Model((SimpleBakedModel) original, state, cutoutSprites);
            if (model.hasCutout) {
                entry.setValue(model);
                changed++;
            }
        }
        if (changed > 0) GTLCore.LOGGER.info("Separated cutout occlusion from translucent overlays in {} frame models", changed);
    }

    static boolean cutout(SpriteContents sprite) {
        // Animated colours are safe when every frame has the same alpha mask. Changing
        // transparency can interpolate between binary keyframes and must stay translucent.
        boolean animated = sprite.getUniqueFrames().limit(2).count() > 1;
        var image = sprite.getOriginalImage();
        boolean opaque = false;
        // Intermediate alpha belongs in the translucent pass; in particular, do not
        // convert shaded overlays or resource-pack textures with translucent surfaces.
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int alpha = image.getPixelRGBA(x, y) >>> 24;
            if (alpha != 0 && alpha != 255) return false;
            if (animated && alpha != (image.getPixelRGBA(x % sprite.width(), y % sprite.height()) >>> 24)) return false;
            opaque |= alpha == 255;
        }
        return opaque;
    }

    private static boolean cutout(BakedQuad quad, Map<SpriteContents, Boolean> sprites) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        // A binary-alpha sprite does not make a translucent vertex colour opaque.
        for (int vertex = 0; vertex < 4; vertex++) if ((vertices[vertex * stride + 3] >>> 24) != 255) return false;
        return sprites.computeIfAbsent(quad.getSprite().contents(), FrameOcclusionModels::cutout);
    }

    static final class Model extends BakedModelWrapper<SimpleBakedModel> {

        private final List<List<BakedQuad>> cutout = new ArrayList<>(7), translucent = new ArrayList<>(7);
        private final ChunkRenderTypeSet layers;
        private final boolean hasCutout;

        Model(SimpleBakedModel original, BlockState state, Map<SpriteContents, Boolean> sprites) {
            super(original);
            boolean opaque = false, transparent = false;
            var random = RandomSource.create(0);
            var directions = Direction.values();
            for (int side = 0; side <= directions.length; side++) {
                var solidQuads = new ArrayList<BakedQuad>();
                var blendedQuads = new ArrayList<BakedQuad>();
                for (var quad : original.getQuads(state, side == directions.length ? null : directions[side], random)) {
                    (cutout(quad, sprites) ? solidQuads : blendedQuads).add(quad);
                }
                opaque |= !solidQuads.isEmpty();
                transparent |= !blendedQuads.isEmpty();
                cutout.add(List.copyOf(solidQuads));
                translucent.add(List.copyOf(blendedQuads));
            }
            hasCutout = opaque;
            layers = transparent ? ChunkRenderTypeSet.of(RenderType.cutoutMipped(), RenderType.translucent()) :
                    ChunkRenderTypeSet.of(RenderType.cutoutMipped());
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
            return layers;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random,
                                        ModelData data, @Nullable RenderType layer) {
            // Null-layer queries (items/previews/other model consumers) retain the original
            // ordering. Terrain draws every quad exactly once, with its original tint/shade.
            if (layer == null) return originalModel.getQuads(state, side, random, data, null);
            int index = side == null ? 6 : side.ordinal();
            if (layer == RenderType.cutoutMipped()) return cutout.get(index);
            if (layer == RenderType.translucent()) return translucent.get(index);
            return List.of();
        }
    }

    private FrameOcclusionModels() {}
}
