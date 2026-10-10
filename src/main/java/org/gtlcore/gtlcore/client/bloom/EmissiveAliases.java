package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.sources.SingleFile;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gives emissive model faces a private atlas identity without changing the shared source texture.
 * Only the current delegate's tint &lt; -100 quads or a currently matched full-block rule select an
 * alias. Inactive instances sharing the source retain their sprites; shader-on aliases cover every layer.
 */
public final class EmissiveAliases {

    private static final String PREFIX = "block/gtlbloom_pbr_alias/";
    private static final ResourceLocation BLOCK_ATLAS = new ResourceLocation("minecraft", "blocks");
    // EyeOfHarmonyRenderer draws these additional models directly, never through a blockstate
    // alias wrapper. Their 8192x4096 / 4096x2048 textures alone added 40 Mi pixels to the atlas.
    // This is an ownership declaration, not a size limit or a blanket exclusion of OBJ models.
    private static final Set<ResourceLocation> DIRECT_MODELS = Set.of(
            GTLCore.id("obj/space"), GTLCore.id("obj/star"));
    private static final Set<ResourceLocation> DIRECT_TEXTURES = Set.of(
            GTLCore.id("block/obj/space_layer"), GTLCore.id("block/obj/star_layer"));
    private static volatile Set<ResourceLocation> discovered = Set.of();
    private static volatile Set<ResourceLocation> fullBlockTargets = Set.of();
    private static volatile List<Model> awaitingAtlas = List.of();

    static ResourceLocation alias(ResourceLocation source) {
        return new ResourceLocation(source.getNamespace(), PREFIX + source.getPath());
    }

    /** Original sprite ID; null unless this reload actually registered that generated alias. */
    @Nullable
    public static ResourceLocation original(ResourceLocation alias) {
        ResourceLocation source = decode(alias);
        return source != null && discovered.contains(source) ? source : null;
    }

    @Nullable
    private static ResourceLocation decode(ResourceLocation alias) {
        String path = alias.getPath();
        if (!path.startsWith(PREFIX) || path.length() == PREFIX.length()) return null;
        String sourcePath = path.substring(PREFIX.length());
        if (sourcePath.startsWith(PREFIX)) return null;
        return new ResourceLocation(alias.getNamespace(), sourcePath);
    }

    /** Called at atlas-definition load, before sprite decoding and stitching, on the reload worker. */
    public static void appendSources(ResourceManager resources, ResourceLocation atlas,
                                     List<SpriteSource> sources) {
        if (!BLOCK_ATLAS.equals(atlas)) return;
        // Never retain candidates from a previous resource reload if discovery fails.
        discovered = Set.of();
        fullBlockTargets = Set.of();
        if (!ShaderEmissionBridge.aliasesSupported()) return;
        Set<ResourceLocation> candidates;
        try {
            candidates = discover(resources);
        } catch (RuntimeException failure) {
            GTLCore.LOGGER.warn("Emissive alias discovery failed; keeping original sprites", failure);
            return;
        }
        Set<ResourceLocation> added = new HashSet<>();
        for (ResourceLocation source : candidates) {
            ResourceLocation replacement = alias(source);
            // Do not replace a user-authored asset occupying the reserved alias location.
            if (resources.getResource(texture(replacement)).isPresent()) continue;
            if (resources.getResource(texture(source)).isEmpty()) continue;
            // SingleFile's resourceId selects the original PNG AND its animation metadata; spriteId
            // gives the separately allocated sprite its own atlas coordinates and material identity.
            sources.add(new SingleFile(source, Optional.of(replacement)));
            added.add(source);
        }
        discovered = Set.copyOf(added);
        GTLCore.LOGGER.info("Emissive aliases: {} independent atlas sprites prepared", added.size());
    }

    private static ResourceLocation texture(ResourceLocation sprite) {
        return new ResourceLocation(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
    }

    /** Resource JSON is only a discovery hint. It never globally marks a source texture emissive. */
    static Set<ResourceLocation> discover(ResourceManager resources) {
        Map<ResourceLocation, Definition> definitions = new HashMap<>();
        Set<ResourceLocation> sharedDirectResources = new HashSet<>();
        resources.listResources("models", id -> id.getPath().endsWith(".json")).forEach((id, resource) -> {
            try (var reader = resource.openAsReader()) {
                String path = id.getPath();
                ResourceLocation model = new ResourceLocation(id.getNamespace(), path.substring(7, path.length() - 5));
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (!DIRECT_MODELS.contains(model)) collectDirectReferences(json, sharedDirectResources, 0);
                definitions.put(model, readDefinition(json));
            } catch (Exception ignored) {
                // A malformed or custom model cannot make the resource reload fail here. Minecraft's
                // own model loader reports its errors; unavailable material conversion never enables shader-on local
                // bloom.
            }
        });
        // Resource packs can reuse an otherwise renderer-only model as a blockstate variant.
        // Read every stack entry, just as Minecraft combines multipart/variant definitions.
        for (ResourceLocation id : resources.listResources("blockstates", file -> file.getPath().endsWith(".json")).keySet()) {
            for (var resource : resources.getResourceStack(id)) {
                try (var reader = resource.openAsReader()) {
                    collectDirectReferences(JsonParser.parseReader(reader), sharedDirectResources, 0);
                } catch (Exception ignored) {
                    // Leave malformed blockstate diagnostics to the model loader.
                }
            }
        }
        Set<ResourceLocation> directTextures = new HashSet<>(DIRECT_TEXTURES);
        directTextures.removeAll(sharedDirectResources);
        Set<ResourceLocation> result = new TreeSet<>();
        Set<ResourceLocation> fullBlockModels = fullBlockModels(resources);
        for (ResourceLocation model : definitions.keySet()) {
            Map<String, String> textures = new HashMap<>();
            List<String> faces = null;
            List<String> allFaces = null;
            Set<ResourceLocation> parents = new HashSet<>();
            ResourceLocation cursor = model;
            // Descendant keys override parent keys, including #references resolved in this final map.
            while (cursor != null && parents.size() < 128 && parents.add(cursor)) {
                Definition definition = definitions.get(cursor);
                if (definition == null) break;
                definition.textures.forEach(textures::putIfAbsent);
                // Vanilla inherits elements if its own elements list is empty.
                if (faces == null && definition.hasElements) {
                    faces = definition.emissiveFaces;
                    allFaces = definition.allFaces;
                }
                cursor = definition.parent;
            }
            if (faces != null) for (String face : faces) addResolved(result, face, textures);
            // A different model (including inherited, custom-loader and unused pack models)
            // referencing the texture makes its use ambiguous: keep the alias conservatively.
            if (!DIRECT_MODELS.contains(model) || sharedDirectResources.contains(model)) {
                for (String value : textures.values()) directTextures.remove(resolve(value, textures));
                if (allFaces != null) for (String value : allFaces) directTextures.remove(resolve(value, textures));
            }
            // Allocate all possible texture slots for a rule's blockstate model variants. Runtime
            // BloomRules.fullBlock(state), not this resource-time superset, chooses emissive instances.
            if (fullBlockModels.contains(model)) {
                for (String value : textures.values()) addResolved(result, value, textures);
                if (allFaces != null) for (String value : allFaces) addResolved(result, value, textures);
            }
        }
        // GTCEu 1.4.4 WorkableOverlayModel discovers *_emissive PNGs programmatically and marks
        // their faces -101 only when the corresponding emissive-rendering option is enabled.
        // Pre-stitch those possibilities, but still require the actual runtime tint before switching.
        // Registrations may place GTCEu-generated overlays in gtlcore, kubejs or other namespaces.
        resources.listResources("textures/block", id -> id.getPath().endsWith("_emissive.png")).keySet().forEach(id -> {
            String path = id.getPath();
            ResourceLocation sprite = new ResourceLocation(id.getNamespace(), path.substring(9, path.length() - 4));
            if (!reserved(sprite)) result.add(sprite);
        });
        // Keep unknown runtime-only textures, all states and all modes preallocated. Only skip
        // the proven standalone-only copies; originals and their PBR maps remain untouched.
        resources.listResources("textures/block", id -> id.getPath().endsWith(".png") && !id.getPath().endsWith("_s.png") && !id.getPath().endsWith("_n.png")).keySet().forEach(id -> {
            String path = id.getPath();
            ResourceLocation sprite = new ResourceLocation(id.getNamespace(), path.substring(9, path.length() - 4));
            if (!reserved(sprite) && !directTextures.contains(sprite)) result.add(sprite);
        });
        return result;
    }

    private static void collectDirectReferences(JsonElement value, Set<ResourceLocation> references, int depth) {
        if (depth > 128) return;
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) collectDirectReferences(child, references, depth + 1);
        } else if (value.isJsonObject()) {
            for (JsonElement child : value.getAsJsonObject().asMap().values())
                collectDirectReferences(child, references, depth + 1);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            // Custom loaders need not call their material slot "texture" or their model "model".
            ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
            if (id != null && (DIRECT_MODELS.contains(id) || DIRECT_TEXTURES.contains(id))) references.add(id);
        }
    }

    private static void addResolved(Set<ResourceLocation> result, String value, Map<String, String> textures) {
        ResourceLocation sprite = resolve(value, textures);
        if (sprite != null && !reserved(sprite)) result.add(sprite);
    }

    private static boolean reserved(ResourceLocation sprite) {
        return sprite.getPath().startsWith(PREFIX);
    }

    private static Set<ResourceLocation> fullBlockModels(ResourceManager resources) {
        Set<ResourceLocation> blocks = new HashSet<>();
        Set<ResourceLocation> models = new HashSet<>();
        try {
            for (BloomRules.BlockRule rule : BloomRules.read(resources).blocks()) blocks.add(rule.block());
            for (ResourceLocation block : blocks) {
                for (var resource : resources.getResourceStack(new ResourceLocation(block.getNamespace(),
                        "blockstates/" + block.getPath() + ".json"))) {
                    try (var reader = resource.openAsReader()) {
                        collectModels(JsonParser.parseReader(reader), models, 0);
                    } catch (Exception ignored) {
                        // Runtime block textures are preallocated separately for programmatic blockstates.
                    }
                }
            }
        } catch (RuntimeException failure) {
            // Config paths may not yet exist during early/standalone loading. Tint aliases remain usable.
            GTLCore.LOGGER.debug("Could not discover full-block rule aliases: {}", failure.toString());
        }
        fullBlockTargets = Set.copyOf(blocks);
        return models;
    }

    private static void collectModels(JsonElement value, Set<ResourceLocation> models, int depth) {
        if (depth > 128) return;
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) collectModels(child, models, depth + 1);
        } else if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("model") && entry.getValue().isJsonPrimitive()) {
                    ResourceLocation id = ResourceLocation.tryParse(entry.getValue().getAsString());
                    if (id != null) models.add(id);
                } else collectModels(entry.getValue(), models, depth + 1);
            }
        }
    }

    private record Definition(@Nullable ResourceLocation parent, Map<String, String> textures,
                              boolean hasElements, List<String> emissiveFaces, List<String> allFaces) {}

    private static Definition readDefinition(JsonObject json) {
        ResourceLocation parent = json.has("parent") ? ResourceLocation.tryParse(json.get("parent").getAsString()) : null;
        Map<String, String> textures = new LinkedHashMap<>();
        if (json.has("textures")) json.getAsJsonObject("textures").entrySet().forEach(entry -> {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString())
                textures.put(entry.getKey(), entry.getValue().getAsString());
        });
        List<String> emissiveFaces = new ArrayList<>();
        List<String> allFaces = new ArrayList<>();
        boolean hasElements = json.has("elements") && !json.getAsJsonArray("elements").isEmpty();
        if (hasElements) for (JsonElement element : json.getAsJsonArray("elements")) {
            JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
            if (faces == null) continue;
            for (JsonElement value : faces.asMap().values()) {
                JsonObject face = value.getAsJsonObject();
                if (face.has("texture")) {
                    String valueTexture = face.get("texture").getAsString();
                    allFaces.add(valueTexture);
                    if (face.has("tintindex") && face.get("tintindex").getAsInt() < -100)
                        emissiveFaces.add(valueTexture);
                }
            }
        }
        return new Definition(parent, textures, hasElements, emissiveFaces, allFaces);
    }

    @Nullable
    private static ResourceLocation resolve(String value, Map<String, String> textures) {
        Set<String> visited = new HashSet<>();
        while (value != null && value.startsWith("#")) {
            if (!visited.add(value)) return null;
            value = textures.get(value.substring(1));
        }
        return value == null ? null : ResourceLocation.tryParse(value);
    }

    static boolean target(ResourceLocation id) {
        return id instanceof ModelResourceLocation model && !model.getVariant().equals("inventory");
    }

    static boolean shouldWrap(BakedModel model) {
        if (model.getClass() != SimpleBakedModel.class && !(model instanceof FrameOcclusionModels.Model)) return true;
        // SectionMeshes deliberately prunes ordinary SimpleBakedModel blocks. Keep their exact
        // class identity unless a fixed face can actually use one of this reload's aliases.
        Set<ResourceLocation> candidates = discovered;
        if (candidates.isEmpty()) return false;
        RandomSource random = RandomSource.create(0);
        for (Direction side : Direction.values())
            if (hasEmissiveFace(model.getQuads(null, side, random), candidates)) return true;
        return hasEmissiveFace(model.getQuads(null, null, random), candidates);
    }

    private static boolean hasEmissiveFace(List<BakedQuad> quads, Set<ResourceLocation> candidates) {
        for (BakedQuad quad : quads)
            if (quad.getTintIndex() < -100 && candidates.contains(quad.getSprite().contents().name())) return true;
        return false;
    }

    public static void modifyModels(ModelEvent.ModifyBakingResult event) {
        // An unsupported loader has no alias sprites. Do not wrap unrelated models needlessly.
        if (!ShaderEmissionBridge.aliasesSupported()) {
            awaitingAtlas = List.of();
            return;
        }
        Map<BakedModel, Model> wrappers = new IdentityHashMap<>();
        Map<BakedModel, Boolean> eligible = new IdentityHashMap<>();
        Set<Model> created = Collections.newSetFromMap(new IdentityHashMap<>());
        event.getModels().replaceAll((id, original) -> {
            if (!target(id)) return original;
            ResourceLocation block = new ResourceLocation(id.getNamespace(), id.getPath());
            if (!(original instanceof Model) && !fullBlockTargets.contains(block) && !eligible.computeIfAbsent(original, EmissiveAliases::shouldWrap)) return original;
            Model wrapped = original instanceof Model model ? model : wrappers.computeIfAbsent(original, Model::new);
            created.add(wrapped);
            return wrapped;
        });
        // Retain the actual instances: another mod may add an outer wrapper after this listener.
        awaitingAtlas = List.copyOf(created);
    }

    public static void bakingCompleted(ModelEvent.BakingCompleted event) {
        var atlas = event.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        Map<ResourceLocation, TextureAtlasSprite> installed = new HashMap<>();
        for (ResourceLocation source : discovered) {
            ResourceLocation id = alias(source);
            TextureAtlasSprite sprite = atlas.getSprite(id);
            if (sprite.contents().name().equals(id)) installed.put(source, sprite);
        }
        int initialized = bindModels(Map.copyOf(installed), event.getModels().values());
        GTLCore.LOGGER.info("Emissive aliases: {} stitched sprites bound to {} block models", installed.size(), initialized);
        BloomClient.requestRebuild();
    }

    static int bindModels(Map<ResourceLocation, TextureAtlasSprite> installed, Iterable<BakedModel> models) {
        Set<Model> initialized = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Model wrapped : awaitingAtlas) if (initialized.add(wrapped)) wrapped.install(installed);
        awaitingAtlas = List.of();
        for (BakedModel model : models)
            if (model instanceof Model wrapped && initialized.add(wrapped)) wrapped.install(installed);
        return initialized.size();
    }

    /** Shader-on material identity is independent of render-layer classification. */
    static boolean supportedLayer(@Nullable RenderType layer) {
        return true;
    }

    static final class Model extends BakedModelWrapper<BakedModel> {

        private volatile Map<ResourceLocation, TextureAtlasSprite> sprites = Map.of();

        Model(BakedModel original) {
            super(original);
        }

        BakedModel localDelegate() {
            return originalModel;
        }

        void install(Map<ResourceLocation, TextureAtlasSprite> value) {
            sprites = Map.copyOf(value);
        }

        private List<BakedQuad> replace(List<BakedQuad> input, @Nullable BlockState state, @Nullable RenderType layer) {
            Map<ResourceLocation, TextureAtlasSprite> available = sprites;
            if (!BloomConfig.generatedMaterialsEnabled() || available.isEmpty() || !ShaderEmissionBridge.nativeActive() || !ShaderEmissionBridge.aliasesSupported()) return input;
            boolean fullBlock = state != null && BloomRules.fullBlock(state);
            List<BakedQuad> result = null;
            for (int i = 0; i < input.size(); i++) {
                BakedQuad quad = input.get(i);
                if (quad.getTintIndex() >= -100 && !fullBlock) continue;
                TextureAtlasSprite replacement = available.get(quad.getSprite().contents().name());
                if (replacement == null || replacement == quad.getSprite()) continue;
                if (result == null) result = new ArrayList<>(input);
                result.set(i, PoweredCraftingLights.retexture(quad, replacement));
            }
            return result == null ? input : result;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
            // Shader-on aliases also apply to the legacy API with no layer information.
            return replace(originalModel.getQuads(state, side, random), state, null);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random,
                                        ModelData data, @Nullable RenderType layer) {
            return replace(originalModel.getQuads(state, side, random, data, layer), state, layer);
        }
    }

    private EmissiveAliases() {}
}
