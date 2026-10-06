package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;

import java.util.Set;

public final class BloomClient {

    public static final SectionMeshes MESHES = new SectionMeshes();
    public static final DynamicRings RINGS = new DynamicRings();
    private static final BloomPipeline PIPELINE = new BloomPipeline();
    private static Matrix4f view, projection;
    private static Frustum frustum;
    private static Vec3 camera;
    private static float fogStart = Float.MAX_VALUE / 2, fogEnd = Float.MAX_VALUE;
    private static int fogShape;
    private static boolean rebuild, initialized;
    private static String unavailable;

    public static void init() {
        if (ModList.get().isLoaded("gtlbloom")) {
            unavailable = "Standalone GTL Bloom Preview is installed; integrated bloom stays inactive.";
            GTLCore.LOGGER.info(unavailable);
            return;
        }
        initialized = true;
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(BloomClient::reload);
        modBus.addListener(PoweredCraftingLights::modifyModels);
        modBus.addListener(PoweredCraftingLights::bakingCompleted);
        modBus.addListener(EmissiveAliases::modifyModels);
        modBus.addListener(EmissiveAliases::bakingCompleted);
        MinecraftForge.EVENT_BUS.addListener(BloomClient::stage);
        MinecraftForge.EVENT_BUS.addListener(BloomClient::chunkLoad);
        MinecraftForge.EVENT_BUS.addListener(BloomClient::chunkUnload);
        MinecraftForge.EVENT_BUS.addListener(BloomClient::levelUnload);
        if (ModList.get().isLoaded("shimmer")) {
            unavailable = "Shimmer is installed; integrated local bloom stays inactive.";
            GTLCore.LOGGER.warn(unavailable);
        }
    }

    private record ReloadData(Set<ResourceLocation> textures, BloomRules.Data rules) {}

    private static void reload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<ReloadData>() {

            protected ReloadData prepare(ResourceManager resources, ProfilerFiller profiler) {
                return new ReloadData(BloomMetadata.read(resources), BloomRules.read(resources));
            }

            protected void apply(ReloadData data, ResourceManager resources, ProfilerFiller profiler) {
                ShaderEmissionBridge.resourceReload();
                BloomMetadata.install(data.textures());
                BloomRules.install(data.rules());
                PIPELINE.close();
                if (!ModList.get().isLoaded("shimmer")) unavailable = null;
                requestRebuild();
            }
        });
    }

    public static boolean active() {
        return initialized && !ShaderEmissionBridge.suppressesLocalBloom() && unavailable == null;
    }

    public static void requestRebuild() {
        rebuild = true;
    }

    /** A mode change must not reuse an emission buffer collected under the previous policy. */
    static void pauseLocalPipeline() {
        RINGS.clear();
        PIPELINE.close();
    }

    public static void beginFrame() {
        if (!initialized) return;
        PIPELINE.beginFrame();
        ShaderEmissionBridge.update();
        view = null;
        projection = null;
        frustum = null;
        camera = null;
        fogStart = Float.MAX_VALUE / 2;
        fogEnd = Float.MAX_VALUE;
        MESHES.setLevel(Minecraft.getInstance().level);
        if (rebuild) {
            MESHES.rebuildAll();
            rebuild = false;
        }
        if (active()) RINGS.begin();
        else RINGS.clear();
    }

    public static void capture(Matrix4f modelView, Matrix4f projectionMatrix, Vec3 pos) {
        view = new Matrix4f(modelView);
        projection = new Matrix4f(projectionMatrix);
        camera = pos;
        frustum = new Frustum(view, projection);
        frustum.prepare(pos.x, pos.y, pos.z);
    }

    private static void stage(RenderLevelStageEvent event) {
        if (ShaderCompat.shadowPass()) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            fogStart = RenderSystem.getShaderFogStart();
            fogEnd = RenderSystem.getShaderFogEnd();
            fogShape = RenderSystem.getShaderFogShape().getIndex();
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) frustum = event.getFrustum();
    }

    public static void beforeTranslucent() {
        if (!active() || view == null || Minecraft.getInstance().level == null || ShaderCompat.shadowPass()) return;
        try {
            PIPELINE.captureOpaqueDepth();
        } catch (Exception e) {
            pauseAfterFailure(e);
        }
    }

    public static void afterLevel() {
        if (!initialized) return;
        // Oculus may prepare the first/dimension pipeline after our frame-head hook.
        ShaderEmissionBridge.update();
        if (!active() || view == null || Minecraft.getInstance().level == null) {
            RINGS.clear();
            return;
        }
        // PBR atlases may upload during this frame's terrain pass. Drop pre-handoff meshes
        // before the post pass rather than allowing one frame of duplicate bloom.
        if (rebuild) {
            MESHES.rebuildAll();
            rebuild = false;
        }
        try {
            MESHES.buildSome(camera, frustum);
            RINGS.finish();
            if (RINGS.hasMesh() || MESHES.hasVisibleMeshes(frustum)) {
                PIPELINE.render(MESHES, RINGS, view, projection, frustum, camera, fogStart, fogEnd, fogShape);
            }
        } catch (Exception e) {
            pauseAfterFailure(e);
        } finally {
            RINGS.endFrame();
        }
    }

    private static void pauseAfterFailure(Exception e) {
        unavailable = "泛光初始化或渲染失败；详见 logs/latest.log，F3+T 可重新加载。";
        GTLCore.LOGGER.error("Bloom paused after rendering failure", e);
        PIPELINE.close();
    }

    private static void chunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk chunk) {
            Minecraft.getInstance().execute(() -> MESHES.loadChunk(chunk));
        }
    }

    private static void chunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            var pos = event.getChunk().getPos();
            Minecraft.getInstance().execute(() -> {
                if (event.getLevel() == Minecraft.getInstance().level) MESHES.unloadChunk(pos.x, pos.z);
            });
        }
    }

    private static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) Minecraft.getInstance().execute(() -> {
            MESHES.clear();
            RINGS.clear();
            PIPELINE.close();
        });
    }

    private BloomClient() {}
}
