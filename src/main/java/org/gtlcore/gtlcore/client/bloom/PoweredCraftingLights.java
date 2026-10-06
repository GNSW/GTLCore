package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional, dependency-free bridge for GTLCore's six AE2 crafting-storage lights. */
public final class PoweredCraftingLights {

    static final Set<String> STORAGE = Set.of("1m_storage", "4m_storage", "16m_storage",
            "64m_storage", "256m_storage", "max_storage");
    private static volatile List<Model> awaitingAtlas = List.of();

    static ResourceLocation light(String storage) {
        return new ResourceLocation("gtlcore", "block/crafting/" + storage + "_light");
    }

    static ResourceLocation variant(ResourceLocation light, boolean powered) {
        return new ResourceLocation(light.getNamespace(), light.getPath() + (powered ? "_on" : "_off"));
    }

    static boolean target(ResourceLocation id) {
        // Only block-state entries. Inventory transforms and AE2's own storage models stay untouched.
        return id instanceof ModelResourceLocation model && !model.getVariant().equals("inventory") && id.getNamespace().equals("gtlcore") && STORAGE.contains(id.getPath());
    }

    public static void modifyModels(ModelEvent.ModifyBakingResult event) {
        // This event runs during background baking: never consult Minecraft's live model manager here.
        Map<BakedModel, Model> wrappers = new IdentityHashMap<>();
        Set<Model> created = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        event.getModels().replaceAll((id, original) -> {
            if (!target(id)) return original;
            Model wrapped = original instanceof Model model ? model : wrappers.computeIfAbsent(original, Model::new);
            created.add(wrapped);
            return wrapped;
        });
        // Another mod may place its own outer wrapper around ours after this listener returns.
        // Keep this reload's actual wrappers so atlas binding does not depend on being outermost.
        awaitingAtlas = List.copyOf(created);
    }

    public static void bakingCompleted(ModelEvent.BakingCompleted event) {
        var atlas = event.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        Map<ResourceLocation, Pair> pairs = new HashMap<>();
        for (String storage : STORAGE) {
            var source = light(storage);
            var onId = variant(source, true);
            var offId = variant(source, false);
            var on = atlas.getSprite(onId);
            var off = atlas.getSprite(offId);
            // A missing or half-installed resource pack must never replace the world with missing textures.
            if (on.contents().name().equals(onId) && off.contents().name().equals(offId))
                pairs.put(source, new Pair(on, off));
        }
        Map<ResourceLocation, Pair> installed = Map.copyOf(pairs);
        int initialized = bindModels(installed, event.getModels().values());
        if (initialized > 0) GTLCore.LOGGER.info("GTLCore powered crafting lights: {} sprite pairs, {} models",
                pairs.size(), initialized);
        // New atlas coordinates invalidate every old emission quad on F3+T / pack changes.
        BloomClient.requestRebuild();
    }

    static int bindModels(Map<ResourceLocation, Pair> installed, Iterable<BakedModel> models) {
        Set<Model> initialized = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Model wrapped : awaitingAtlas) if (initialized.add(wrapped)) wrapped.install(installed);
        awaitingAtlas = List.of();
        for (BakedModel model : models) {
            if (model instanceof Model wrapped && initialized.add(wrapped)) wrapped.install(installed);
        }
        return initialized.size();
    }

    static boolean powered(@Nullable BlockState state) {
        if (state != null) for (var property : state.getProperties()) {
            if (property instanceof BooleanProperty flag && property.getName().equals("powered"))
                return state.getValue(flag);
        }
        return false;
    }

    record Pair(TextureAtlasSprite on, TextureAtlasSprite off) {}

    /** All Forge model-data, render-type, particle, transform and AO methods delegate unchanged. */
    static final class Model extends BakedModelWrapper<BakedModel> {

        private volatile Map<ResourceLocation, Pair> pairs = Map.of();

        Model(BakedModel original) {
            super(original);
        }

        void install(Map<ResourceLocation, Pair> value) {
            pairs = value;
        }

        private List<BakedQuad> switchLights(List<BakedQuad> input, @Nullable BlockState state) {
            Map<ResourceLocation, Pair> available = pairs;
            if (available.isEmpty()) return input;
            boolean powered = powered(state);
            List<BakedQuad> result = null;
            for (int i = 0; i < input.size(); i++) {
                BakedQuad quad = input.get(i);
                Pair pair = available.get(quad.getSprite().contents().name());
                if (pair == null) continue;
                if (result == null) result = new ArrayList<>(input);
                result.set(i, retexture(quad, powered ? pair.on() : pair.off()));
            }
            return result == null ? input : result;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
            return switchLights(originalModel.getQuads(state, side, random), state);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random,
                                        ModelData data, @Nullable RenderType layer) {
            return switchLights(originalModel.getQuads(state, side, random, data, layer), state);
        }
    }

    static BakedQuad retexture(BakedQuad quad, TextureAtlasSprite replacement) {
        TextureAtlasSprite source = quad.getSprite();
        int[] data = quad.getVertices().clone();
        int stride = data.length / 4;
        // BLOCK baked-quad layout: XYZ, RGBA, UV0, UV2, normal. Only UV0 changes.
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * stride;
            float u = Float.intBitsToFloat(data[offset + 4]);
            float v = Float.intBitsToFloat(data[offset + 5]);
            data[offset + 4] = Float.floatToRawIntBits(remap(u, source.getU0(), source.getU1(),
                    replacement.getU0(), replacement.getU1()));
            data[offset + 5] = Float.floatToRawIntBits(remap(v, source.getV0(), source.getV1(),
                    replacement.getV0(), replacement.getV1()));
        }
        return new BakedQuad(data, quad.getTintIndex(), quad.getDirection(), replacement,
                quad.isShade(), quad.hasAmbientOcclusion());
    }

    static float remap(float value, float from0, float from1, float to0, float to1) {
        return to0 + (value - from0) / (from1 - from0) * (to1 - to0);
    }

    private PoweredCraftingLights() {}
}
