package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Universal shader-on LabPBR generation. Shader identity and options never gate emission.
 * AUTO disables local bloom for every active shader. FORCE_LOCAL explicitly opts out of the bridge.
 */
public final class ShaderEmissionBridge {

    private static final MetadataSectionSerializer<Boolean> SHIMMER = new MetadataSectionSerializer<>() {

        public String getMetadataSectionName() {
            return "shimmer";
        }

        public Boolean fromJson(JsonObject value) {
            return value.has("bloom") && value.get("bloom").getAsBoolean();
        }
    };
    private static Method inUse;
    private static Object api;
    private static volatile boolean initialized;
    private static volatile boolean active;
    private static volatile boolean supportedOculus;
    private static volatile Set<ResourceLocation> uploaded = Set.of();

    private static final class Session {

        private final Set<ResourceLocation> generated, decoded;
        private final Set<ResourceLocation> generatedNormals = new HashSet<>(), decodedNormals = new HashSet<>();
        private final SurfaceMaterialResources surfaces = new SurfaceMaterialResources();

        private Session(Set<ResourceLocation> generated, Set<ResourceLocation> decoded) {
            this.generated = generated;
            this.decoded = decoded;
        }

        private Set<ResourceLocation> generated() {
            return generated;
        }

        private Set<ResourceLocation> decoded() {
            return decoded;
        }
    }

    private static volatile Set<ResourceLocation> uploadedNormals = Set.of();
    private static final ThreadLocal<Session> pending = new ThreadLocal<>();
    private static volatile long pbrCacheEpoch;
    private static BloomPolicy appliedPolicy;
    private static BloomConfig.Materials appliedMaterials;
    private static volatile String materialFailure;
    private static boolean samplerResetFailed;
    private static volatile String reason = "Shaders off";

    private static void initialize() {
        if (initialized) return;
        synchronized (ShaderEmissionBridge.class) {
            if (initialized) return;
            supportedOculus = org.gtlcore.gtlcore.client.bloom.BridgeMixinPlugin.supportedOculus();
            for (String name : new String[] { "net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi" }) {
                try {
                    Class<?> type = Class.forName(name);
                    api = type.getMethod("getInstance").invoke(null);
                    inUse = type.getMethod("isShaderPackInUse");
                    break;
                } catch (ReflectiveOperationException | LinkageError absent) {
                    api = null;
                    inUse = null;
                }
            }
            initialized = true;
        }
    }

    /** Actual public pipeline-active API, independent of pack name, hash, options or atlas state. */
    static boolean shaderInUse() {
        initialize();
        if (inUse == null) return false;
        try {
            return (boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException | LinkageError failure) {
            // A present but failing shader API must never re-enable local bloom over an unknown pipeline.
            reason = "Shader API failed; local bloom disabled for safety";
            return true;
        }
    }

    public static void update() {
        active = shaderInUse();
        var policy = new BloomPolicy(BloomConfig.mode(), active, supportedOculus);
        var materials = BloomConfig.materials();
        BloomPolicy previous = appliedPolicy;
        var previousMaterials = appliedMaterials;
        appliedPolicy = policy;
        appliedMaterials = materials;
        if (previous != null) {
            if (policy.resetLocalPipeline(previous)) BloomClient.pauseLocalPipeline();
            if (policy.refreshMaterials(previous, !materials.equals(previousMaterials))) refreshMaterials();
        }
        reason = switch (policy.mode()) {
            case OFF -> "Integrated bloom disabled";
            case FORCE_LOCAL -> "Local bloom selected; generated PBR disabled";
            case FORCE_PBR -> active ? "PBR selected; no local fallback" : "PBR selected; waiting for an active shader";
            case AUTO -> active ? "Shaders active; PBR selected" : "Shaders inactive; local bloom selected";
        };
        if (!policy.equals(previous)) GTLCore.LOGGER.info("Bloom mode {}: {} (material bridge={})",
                policy.mode(), reason, supportedOculus);
    }

    public static void beginAtlas(TextureAtlas atlas) {
        if (atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) {
            if (!uploaded.isEmpty() || !uploadedNormals.isEmpty()) BloomClient.requestRebuild();
            uploaded = Set.of();
            uploadedNormals = Set.of();
            pending.set(new Session(new HashSet<>(), new HashSet<>()));
        } else pending.remove();
    }

    /** Called only after Oculus successfully uploads its specular atlas. */
    public static void uploadedAtlas() {
        Session session = pending.get();
        if (session != null && BloomConfig.generatedMaterialsEnabled()) {
            uploaded = Set.copyOf(session.decoded());
            BloomClient.requestRebuild();
            GTLCore.LOGGER.info("Shader emission bridge uploaded {} generated LabPBR sprites", uploaded.size());
        }
    }

    public static void uploadedNormalAtlas() {
        Session session = pending.get();
        if (session != null && BloomConfig.generatedMaterialsEnabled()) uploadedNormals = Set.copyOf(session.decodedNormals);
    }

    public static void decodedNormalSprite(ResourceLocation id, boolean successful) {
        Session session = pending.get();
        if (successful && session != null && session.generatedNormals.contains(id)) session.decodedNormals.add(id);
    }

    public static void endAtlas() {
        pending.remove();
    }

    public static void decodedSprite(ResourceLocation id, boolean successful) {
        Session session = pending.get();
        if (successful && session != null && session.generated().contains(id)) session.decoded().add(id);
    }

    public static void resourceReload() {
        uploaded = Set.of();
        uploadedNormals = Set.of();
        // Keep the master gate current throughout reload, never briefly enable old bloom.
        active = shaderInUse();
        pending.remove();
        BloomClient.requestRebuild();
    }

    public static void shaderReload() {
        resourceReload();
        clearPbrTextures();
    }

    /** Texture registrations can change without a full resource reload. Rebuild the PBR atlas once. */
    public static void rulesChanged() {
        if (!aliasesSupported()) return;
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            resourceReload();
            clearPbrTextures();
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level != null) minecraft.levelRenderer.allChanged();
        });
    }

    private static void refreshMaterials() {
        resourceReload();
        if (aliasesSupported()) clearPbrTextures();
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.level != null) minecraft.levelRenderer.allChanged();
    }

    private static void clearPbrTextures() {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThreadOrInit();
        try {
            Class<?> manager = Class.forName("net.irisshaders.iris.texture.pbr.PBRTextureManager");
            materialFailure = null;
            manager.getMethod("clear").invoke(manager.getField("INSTANCE").get(null));
        } catch (ReflectiveOperationException | LinkageError failure) {
            materialFailure = "PBR cache invalidation failed; reload resources with F3+T";
            GTLCore.LOGGER.error(materialFailure, failure);
        }
    }

    /**
     * Called before every real Oculus cache clear, including its native resource reload path.
     * Move the current pipeline to surviving defaults BEFORE Oculus releases old texture IDs.
     * The following world entry then restores the selected albedo's authored/generated materials.
     */
    public static void pbrCacheClearing() {
        samplerResetFailed = false;
        ++pbrCacheEpoch;
        uploaded = Set.of();
        uploadedNormals = Set.of();
        pending.remove();
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object pipelines = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = pipelines.getClass().getMethod("getPipelineNullable").invoke(pipelines);
            if (!(pipeline instanceof PbrSamplerState state)) return;
            Class<?> manager = Class.forName("net.irisshaders.iris.texture.pbr.PBRTextureManager");
            Object holder = manager.getMethod("getHolder", int.class).invoke(manager.getField("INSTANCE").get(null), -1);
            Class<?> holderType = Class.forName("net.irisshaders.iris.texture.pbr.PBRTextureHolder");
            var normal = (net.minecraft.client.renderer.texture.AbstractTexture) holderType.getMethod("normalTexture").invoke(holder);
            var specular = (net.minecraft.client.renderer.texture.AbstractTexture) holderType.getMethod("specularTexture").invoke(holder);
            if (normal == null || specular == null) return; // manager has not been initialized yet
            state.gtlcore$bloom$resetPbrSamplers(normal.getId(), specular.getId());
            manager.getMethod("notifyPBRTexturesChanged").invoke(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            samplerResetFailed = true;
            materialFailure = "Unable to reset PBR samplers before cache release; next world entry will rebind";
            GTLCore.LOGGER.error(materialFailure, failure);
        }
    }

    public static void pbrCacheCleared() {
        if (!samplerResetFailed) materialFailure = null;
    }

    public static long pbrCacheEpoch() {
        return pbrCacheEpoch;
    }

    public static Optional<Resource> resource(ResourceManager resources, ResourceLocation id) {
        Optional<Resource> existing = resources.getResource(id);
        String path = id.getPath();
        if (!path.startsWith("textures/") || (!path.endsWith("_s.png") && !path.endsWith("_n.png"))) return existing;
        ResourceLocation sprite = new ResourceLocation(id.getNamespace(), path.substring(9, path.length() - 6));
        ResourceLocation original = EmissiveAliases.original(sprite);
        if (original != null && existing.isEmpty()) {
            // Aliases preserve pre-existing normal/specular assets under every shader, including unknown packs.
            String suffix = path.endsWith("_s.png") ? "_s.png" : "_n.png";
            existing = resources.getResource(new ResourceLocation(original.getNamespace(), "textures/" + original.getPath() + suffix));
        }
        // Authored maps win independently for each channel. Surface synthesis is separate from
        // emission eligibility: an unlit casing can receive a material without becoming emissive.
        if (!BloomConfig.generatedMaterialsEnabled() || pending.get() == null) return existing;
        ResourceLocation sourceSprite = original == null ? sprite : original;
        boolean normal = path.endsWith("_n.png");
        if (existing.isEmpty()) {
            Optional<Resource> surface = pending.get().surfaces.resource(resources, sourceSprite, normal);
            if (surface.isPresent()) {
                existing = surface;
                if (normal) pending.get().generatedNormals.add(sprite);
                else pending.get().generated().add(sprite);
            }
        }
        if (normal) return existing;
        // Existing emission bridge semantics remain independent of the optional surface pass.
        ResourceLocation baseId = new ResourceLocation(sourceSprite.getNamespace(), "textures/" + sourceSprite.getPath() + ".png");
        Optional<Resource> base = resources.getResource(baseId);
        if (base.isEmpty()) return existing;
        try {
            Resource source = base.get();
            if (original == null && !source.metadata().getSection(SHIMMER).orElse(false) && !BloomRules.texture(sprite)) return existing;
            java.awt.image.BufferedImage albedo;
            try (var input = source.open()) {
                albedo = LabPbrPixels.decodePng(input.readAllBytes());
            }
            java.awt.image.BufferedImage material = null;
            if (existing.isPresent()) {
                try (var input = existing.get().open()) {
                    material = LabPbrPixels.decodePng(input.readAllBytes());
                }
                material = LabPbrPixels.alignMaterial(albedo, material);
            }
            double strength = EmissionTuning.strength(source);
            byte[] png = LabPbrPixels.encodePng(LabPbrPixels.generate(albedo, material, true, strength));
            pending.get().generated().add(sprite);
            return Optional.of(new Resource(source.source(), () -> new ByteArrayInputStream(png), source::metadata));
        } catch (Exception failure) {
            GTLCore.LOGGER.warn("Unable to generate LabPBR for {}; local bloom remains disabled with shaders: {}", sprite, failure.toString());
            return existing;
        }
    }

    public static boolean handedOff(BakedQuad quad, RenderType layer) {
        return suppressesLocalBloom();
    }

    public static boolean suppressesLocalBloom() {
        // An explicit fallback may start only after old injected textures have been released.
        // If cleanup failed, otherwise local bloom could add to still-bound generated emission.
        return !BloomConfig.mode().local(active) || active && materialFailure != null;
    }

    public static String status() {
        return "mode=" + BloomConfig.mode() + ", shaders=" + active + ", material bridge=" + supportedOculus + ", generated specular=" + uploaded.size() + ", generated normal=" + uploadedNormals.size() + (materialFailure == null ? "; " + reason : "; " + materialFailure);
    }

    public static boolean nativeActive() {
        return active;
    }

    public static boolean aliasesSupported() {
        initialize();
        return supportedOculus;
    }

    private ShaderEmissionBridge() {}
}
