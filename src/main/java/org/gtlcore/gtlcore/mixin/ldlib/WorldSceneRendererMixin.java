package org.gtlcore.gtlcore.mixin.ldlib;

import org.gtlcore.gtlcore.client.preview.PreviewRendererAccess;
import org.gtlcore.gtlcore.client.preview.PreviewSceneState;
import org.gtlcore.gtlcore.client.preview.PreviewScenes;
import org.gtlcore.gtlcore.utils.RenderUtil;
import org.gtlcore.gtlcore.utils.datastructure.CacheState;

import com.lowdragmc.lowdraglib.Platform;
import com.lowdragmc.lowdraglib.client.scene.CameraEntity;
import com.lowdragmc.lowdraglib.client.scene.ISceneBlockRenderHook;
import com.lowdragmc.lowdraglib.client.scene.ISceneEntityRenderHook;
import com.lowdragmc.lowdraglib.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(WorldSceneRenderer.class)
public abstract class WorldSceneRendererMixin implements PreviewRendererAccess {

    @Unique
    private PreviewScenes.Key gTLCore$previewKey;
    @Unique
    private PreviewSceneState gTLCore$previewScene;
    @Shadow(remap = false)
    protected boolean ortho;
    @Shadow(remap = false)
    protected CameraEntity cameraEntity;
    @Shadow(remap = false)
    private float fov;

    @Override
    public float gtlcore$fov() {
        return fov;
    }

    @Override
    public void gtlcore$previewKey(PreviewScenes.Key key) {
        gTLCore$previewKey = key;
    }

    @Override
    public PreviewScenes.Key gtlcore$previewKey() {
        return gTLCore$previewKey;
    }

    @Override
    public Iterator<BlockPos> gtlcore$rayCandidates(Vec3 start, Vec3 end) {
        return gTLCore$previewScene == null ? null : gTLCore$previewScene.rayCandidates(start, end);
    }

    @Inject(method = "getCompileProgress", at = @At("HEAD"), cancellable = true, remap = false)
    private void gTLCore$previewProgress(CallbackInfoReturnable<Double> cir) {
        if (gTLCore$previewScene != null && gTLCore$previewScene.compiling()) {
            cir.setReturnValue(gTLCore$previewScene.progress());
        }
    }

    @Inject(method = "rayTrace", at = @At("HEAD"), cancellable = true, remap = false)
    private void gTLCore$previewTrace(Vector3f point, CallbackInfoReturnable<BlockHitResult> cir) {
        if (gTLCore$previewKey == null || gTLCore$previewScene == null) return;
        if (!Float.isFinite(point.x) || !Float.isFinite(point.y) || !Float.isFinite(point.z)) {
            cir.setReturnValue(null);
            return;
        }
        var renderer = (WorldSceneRenderer) (Object) this;
        var start = new Vec3(renderer.getEyePos());
        if (ortho) start = start.add(start.subtract(new Vec3(renderer.getLookAt())).scale(500));
        var twice = point.mul(2.0f, new Vector3f());
        var end = new Vec3(twice.x - start.x, twice.y - start.y, twice.z - start.z);
        try {
            var hit = gTLCore$previewScene.trace(world, start, end, cameraEntity);
            if (hit != null) cir.setReturnValue(hit);
        } catch (RuntimeException unsupportedShape) {
            cir.setReturnValue(null);
        }
    }

    @Unique
    private AtomicReference<CacheState> gTLCore$cacheState = new AtomicReference<>(CacheState.UNUSED);
    @Unique
    protected List<List<BufferBuilder.RenderedBuffer>> gTLCore$compiledBufferData;
    @Unique
    protected List<List<VertexBuffer>> gTLCore$vertexBufferBatches;

    @Shadow(remap = false)
    @Final
    public Map<Collection<BlockPos>, ISceneBlockRenderHook> renderedBlocksMap;
    @Shadow(remap = false)
    @Final
    public Level world;
    @Shadow(remap = false)
    protected boolean useCache;
    @Shadow(remap = false)
    protected Thread thread;
    @Shadow(remap = false)
    protected Set<BlockPos> tileEntities;
    @Shadow(remap = false)
    protected int progress;
    @Shadow(remap = false)
    protected int maxProgress;
    @Shadow(remap = false)
    private ISceneEntityRenderHook sceneEntityRenderHook;
    @Shadow(remap = false)
    protected boolean endBatchLast;
    @Shadow(remap = false)
    private Set<BlockPos> blocked;

    @Shadow(remap = false)
    private void renderEntities(TrackedDummyWorld level, PoseStack poseStack, MultiBufferSource buffer, @Nullable ISceneEntityRenderHook hook, float partialTicks) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private void renderTESR(Collection<BlockPos> poses, PoseStack poseStack, MultiBufferSource.BufferSource buffers, @Nullable ISceneBlockRenderHook hook, float partialTicks) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    public static void setDefaultRenderLayerState(RenderType layer) {
        throw new AssertionError();
    }

    @Unique
    private boolean gTLCore$canRenderModelInLayer(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, RenderType layer) {
        var model = dispatcher.getBlockModel(state);
        var entity = world.getBlockEntity(pos);
        var data = model.getModelData(world, pos, state, entity == null ? ModelData.EMPTY : entity.getModelData());
        var random = RandomSource.create(state.getSeed(pos));
        // JSON and wrapped models can declare layers absent from the legacy block-layer table.
        return model.getRenderTypes(state, random, data).contains(layer);
    }

    @Redirect(method = "renderBlocks",
              at = @At(value = "INVOKE",
                       target = "Lcom/lowdragmc/lowdraglib/client/scene/WorldSceneRenderer;canRenderInLayer(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/RenderType;)Z"),
              remap = false)
    private boolean gTLCore$immediateModelLayer(BlockState state, RenderType layer, @Local BlockPos pos,
                                                @Local(argsOnly = true) BlockRenderDispatcher dispatcher) {
        return gTLCore$canRenderModelInLayer(dispatcher, state, pos, layer);
    }

    /**
     * @author Dragons
     * @reason CacheState
     */
    @Overwrite(remap = false)
    public WorldSceneRenderer useCacheBuffer(boolean useCache) {
        if (this.useCache || !Minecraft.getInstance().isSameThread()) return (WorldSceneRenderer) (Object) this;
        deleteCacheBuffer();
        if (useCache) {
            List<RenderType> layers = RenderType.chunkBufferLayers();
            this.gTLCore$vertexBufferBatches = new ObjectArrayList<>(layers.size());
            this.gTLCore$compiledBufferData = new ObjectArrayList<>(layers.size());
            for (int j = 0; j < layers.size(); ++j) {
                this.gTLCore$vertexBufferBatches.add(new ObjectArrayList<>());
                this.gTLCore$compiledBufferData.add(new ObjectArrayList<>());
            }
            if (gTLCore$cacheState.get() == CacheState.COMPILING && thread != null) {
                thread.interrupt();
                thread = null;
            }
            gTLCore$cacheState.set(CacheState.NEED);
        }
        this.useCache = useCache;
        return (WorldSceneRenderer) (Object) this;
    }

    /**
     * @author Dragons
     * @reason CacheState
     */
    @Overwrite(remap = false)
    public WorldSceneRenderer deleteCacheBuffer() {
        if (gTLCore$previewScene != null) gTLCore$previewScene.release();
        if (useCache && gTLCore$vertexBufferBatches != null) {
            for (List<VertexBuffer> bufferList : gTLCore$vertexBufferBatches) {
                if (bufferList != null) {
                    for (VertexBuffer vbo : bufferList) {
                        if (vbo != null) {
                            vbo.close();
                        }
                    }
                    bufferList.clear();
                }
            }
            if (gTLCore$cacheState.get() == CacheState.COMPILING && thread != null) {
                thread.interrupt();
                thread = null;
            }
        }
        this.tileEntities = null;
        this.gTLCore$vertexBufferBatches = null;
        useCache = false;
        gTLCore$cacheState.set(CacheState.UNUSED);
        return (WorldSceneRenderer) (Object) this;
    }

    /**
     * @author Dragons
     * @reason CacheState
     */
    @Overwrite(remap = false)
    public WorldSceneRenderer needCompileCache() {
        if (gTLCore$previewScene != null) gTLCore$previewScene.release();
        PreviewScenes.invalidate(gTLCore$previewKey);
        if (gTLCore$cacheState.get() == CacheState.COMPILING && thread != null) {
            thread.interrupt();
            thread = null;
        }

        if (useCache && gTLCore$vertexBufferBatches != null) {
            for (List<VertexBuffer> batches : gTLCore$vertexBufferBatches) {
                if (batches != null) {
                    for (VertexBuffer vbo : batches) {
                        if (vbo != null) {
                            vbo.close();
                        }
                    }
                    batches.clear();
                }
            }
        }

        gTLCore$cacheState.set(CacheState.NEED);
        return (WorldSceneRenderer) (Object) this;
    }

    /**
     * @author Dragons
     * @reason CacheState
     */
    @Overwrite(remap = false)
    public boolean isCompiling() {
        return (gTLCore$previewScene != null && gTLCore$previewScene.compiling()) ||
                gTLCore$cacheState.get() == CacheState.COMPILING;
    }

    /**
     * @author Dragons
     * @reason In case of >2GB structure
     */
    @SuppressWarnings({ "DataFlowIssue", "deprecation" })
    @Overwrite(remap = false)
    private void renderCacheBuffer(Minecraft mc, MultiBufferSource.BufferSource buffers, float particleTicks) {
        if (gTLCore$previewKey != null) {
            if (gTLCore$previewScene == null) gTLCore$previewScene = new PreviewSceneState();
            if (gTLCore$previewScene.render(gTLCore$previewKey, (WorldSceneRenderer) (Object) this, particleTicks)) return;
        }
        List<RenderType> layers = RenderType.chunkBufferLayers();
        if (gTLCore$cacheState.get() == CacheState.NEED) {
            progress = 0;
            maxProgress = renderedBlocksMap.keySet().stream().map(Collection::size).reduce(0, Integer::sum) * (layers.size() + 1);
            thread = new Thread(() -> {
                gTLCore$cacheState.set(CacheState.COMPILING);
                BlockRenderDispatcher blockRendererDispatcher = mc.getBlockRenderer();
                try {
                    ModelBlockRenderer.enableCaching();
                    PoseStack matrixStack = new PoseStack();

                    // Process each render layer
                    for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
                        if (Thread.interrupted()) {
                            return;
                        }

                        RenderType layer = layers.get(layerIndex);
                        List<BufferBuilder.RenderedBuffer> compiledDataForThisLayer = gTLCore$compiledBufferData.get(layerIndex);

                        BufferBuilder currentBuffer = new BufferBuilder(layer.bufferSize());
                        currentBuffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                        int vertexCount = 0;

                        for (var entry : renderedBlocksMap.entrySet()) {
                            if (Thread.interrupted())
                                return;

                            Collection<BlockPos> renderedBlocks = entry.getKey();
                            ISceneBlockRenderHook hook = entry.getValue();

                            for (BlockPos pos : renderedBlocks) {
                                if (blocked != null && blocked.contains(pos)) {
                                    continue;
                                }

                                BlockState state = world.getBlockState(pos);
                                FluidState fluidState = state.getFluidState();
                                Block block = state.getBlock();

                                if (block == Blocks.AIR) continue;

                                // Check if it's needed to start a new batch BEFORE rendering
                                // Estimate: each block can produce up to ~6 faces * 4 vertices = 24 vertices
                                if (RenderUtil.shouldStartNewBatch(currentBuffer, vertexCount, 24)) {
                                    var builtBuffer = currentBuffer.end();
                                    compiledDataForThisLayer.add(builtBuffer);

                                    currentBuffer = new BufferBuilder(layer.bufferSize());
                                    currentBuffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                                    vertexCount = 0;
                                }

                                var wrapperBuffer = new WorldSceneRenderer.VertexConsumerWrapper(currentBuffer);

                                if (hook != null) {
                                    hook.applyVertexConsumerWrapper(world, pos, state, wrapperBuffer, layer, 0);
                                }

                                if (state.getRenderShape() != RenderShape.INVISIBLE && gTLCore$canRenderModelInLayer(blockRendererDispatcher, state, pos, layer)) {
                                    matrixStack.pushPose();
                                    matrixStack.translate(pos.getX(), pos.getY(), pos.getZ());
                                    if (Platform.isForge()) {
                                        WorldSceneRenderer.renderBlocksForge(blockRendererDispatcher, state, pos, world, matrixStack, wrapperBuffer, world.random, layer);
                                    } else {
                                        blockRendererDispatcher.renderBatched(state, pos, world, matrixStack, wrapperBuffer, false, world.random);
                                    }
                                    matrixStack.popPose();
                                    vertexCount += 24;
                                }

                                if (!fluidState.isEmpty() && ItemBlockRenderTypes.getRenderLayer(fluidState) == layer) {
                                    wrapperBuffer.addOffset((pos.getX() - (pos.getX() & 15)), (pos.getY() - (pos.getY() & 15)), (pos.getZ() - (pos.getZ() & 15)));
                                    blockRendererDispatcher.renderLiquid(pos, world, wrapperBuffer, state, fluidState);
                                    vertexCount += 24;
                                }

                                wrapperBuffer.clerOffset();
                                wrapperBuffer.clearColor();

                                if (maxProgress > 0) {
                                    progress++;
                                }
                            }
                        }

                        if (vertexCount > 0) {
                            var builtBuffer = currentBuffer.end();
                            compiledDataForThisLayer.add(builtBuffer);
                        }
                    }
                } finally {
                    ModelBlockRenderer.clearCache();
                }

                Set<BlockPos> poses = new ObjectOpenHashSet<>();
                renderedBlocksMap.forEach((renderedBlocks, hook) -> {
                    for (BlockPos pos : renderedBlocks) {
                        progress++;
                        if (Thread.interrupted())
                            return;
                        BlockEntity tile = world.getBlockEntity(pos);
                        if (tile != null) {
                            if (mc.getBlockEntityRenderDispatcher().getRenderer(tile) != null) {
                                poses.add(pos);
                            }
                        }
                    }
                });
                if (Thread.interrupted())
                    return;
                tileEntities = poses;
                gTLCore$cacheState.set(CacheState.COMPILED);
                thread = null;
                maxProgress = -1;
            });
            thread.start();
        } else if (gTLCore$cacheState.get() == CacheState.COMPILED) {
            // create VBOs from compiled data on render thread
            for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
                if (gTLCore$vertexBufferBatches.get(layerIndex).isEmpty() && !gTLCore$compiledBufferData.get(layerIndex).isEmpty()) {
                    List<VertexBuffer> batchList = gTLCore$vertexBufferBatches.get(layerIndex);

                    for (BufferBuilder.RenderedBuffer data : gTLCore$compiledBufferData.get(layerIndex)) {
                        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        batchList.add(vbo);

                        final var vboToUpload = vbo;
                        final var dataToUpload = data;
                        RenderSystem.recordRenderCall(() -> {
                            if (!vboToUpload.isInvalid()) {
                                vboToUpload.bind();
                                vboToUpload.upload(dataToUpload);
                                VertexBuffer.unbind();
                            }
                        });
                    }
                    gTLCore$compiledBufferData.get(layerIndex).clear();
                }
            }

            // Render all batches
            PoseStack matrixstack = new PoseStack();
            for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
                List<VertexBuffer> batchesForThisLayer = gTLCore$vertexBufferBatches.get(layerIndex);
                if (batchesForThisLayer == null || batchesForThisLayer.isEmpty()) continue;

                RenderType layer = layers.get(layerIndex);

                if (layer == RenderType.translucent() && tileEntities != null) {
                    if (world instanceof TrackedDummyWorld level) {
                        renderEntities(level, matrixstack, buffers, sceneEntityRenderHook, particleTicks);
                    }
                    renderTESR(tileEntities, matrixstack, mc.renderBuffers().bufferSource(), null, particleTicks);
                    if (!endBatchLast) {
                        buffers.endBatch();
                    }
                }

                layer.setupRenderState();

                for (VertexBuffer vertexbuffer : batchesForThisLayer) {
                    // noinspection ConstantValue
                    if (vertexbuffer.isInvalid() || vertexbuffer.getFormat() == null) continue;

                    matrixstack.pushPose();

                    ShaderInstance shaderInstance = RenderSystem.getShader();

                    for (int j = 0; j < 12; ++j) {
                        int k = RenderSystem.getShaderTexture(j);
                        shaderInstance.setSampler("Sampler" + j, k);
                    }

                    // setup shader uniform
                    if (shaderInstance.MODEL_VIEW_MATRIX != null) {
                        shaderInstance.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
                    }

                    if (shaderInstance.PROJECTION_MATRIX != null) {
                        shaderInstance.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
                    }

                    if (shaderInstance.COLOR_MODULATOR != null) {
                        shaderInstance.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
                    }

                    if (shaderInstance.FOG_START != null) {
                        shaderInstance.FOG_START.set(RenderSystem.getShaderFogStart());
                    }

                    if (shaderInstance.FOG_END != null) {
                        shaderInstance.FOG_END.set(RenderSystem.getShaderFogEnd());
                    }

                    if (shaderInstance.FOG_COLOR != null) {
                        shaderInstance.FOG_COLOR.set(RenderSystem.getShaderFogColor());
                    }

                    if (shaderInstance.FOG_SHAPE != null) {
                        shaderInstance.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
                    }

                    if (shaderInstance.TEXTURE_MATRIX != null) {
                        shaderInstance.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
                    }

                    if (shaderInstance.GAME_TIME != null) {
                        shaderInstance.GAME_TIME.set(RenderSystem.getShaderGameTime());
                    }

                    RenderSystem.setupShaderLights(shaderInstance);
                    shaderInstance.apply();

                    setDefaultRenderLayerState(layer);

                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

                    vertexbuffer.bind();
                    vertexbuffer.draw();

                    matrixstack.popPose();

                    shaderInstance.clear();
                    VertexBuffer.unbind();
                }

                layer.clearRenderState();
            }
        }
    }
}
