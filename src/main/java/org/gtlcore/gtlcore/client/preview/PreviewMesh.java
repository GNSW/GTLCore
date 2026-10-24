package org.gtlcore.gtlcore.client.preview;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.utils.datastructure.LatestTask;

import com.lowdragmc.lowdraglib.client.scene.WorldSceneRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded CPU compilation and render-thread-only VBO ownership. */
public final class PreviewMesh implements AutoCloseable {

    private static final List<RenderType> LAYERS = RenderType.chunkBufferLayers();
    private static final int BLOCKS_PER_BUFFER = 1024;
    private static ThreadPoolExecutor workers;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final Set<PreviewMesh> LIVE = Collections.newSetFromMap(new IdentityHashMap<>());

    private final LatestTask task = new LatestTask();
    private final ArrayBlockingQueue<Part> ready = new ArrayBlockingQueue<>(8);
    private final List<List<VertexBuffer>> buffers = new ArrayList<>();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger progress = new AtomicInteger();
    private final int total;
    private volatile Throwable failure;
    private long bytes;
    private boolean closed;
    private int scheduled;
    private final PreviewSnapshot snapshot;
    private final boolean ghost;
    private final List<Runnable> jobs = new ArrayList<>();
    private final int partitions;

    public PreviewMesh(PreviewSnapshot snapshot, boolean ghost) {
        this.snapshot = snapshot;
        this.ghost = ghost;
        total = Math.max(1, snapshot.positions.size() * LAYERS.size());
        partitions = Math.min(PreviewSettings.workers(), Math.max(1, snapshot.positions.size() / 4096));
        for (int i = 0; i < LAYERS.size() * partitions; i++) buffers.add(new ArrayList<>());
        for (int i = 0; i < partitions; i++) {
            int part = i;
            int from = snapshot.positions.size() * i / partitions;
            int to = snapshot.positions.size() * (i + 1) / partitions;
            jobs.add(() -> compile(part, from, to));
        }
        LIVE.add(this);
    }

    private static ThreadPoolExecutor workers() {
        if (workers == null) {
            int count = PreviewSettings.workers();
            workers = new ThreadPoolExecutor(count, count, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16), runnable -> {
                        var thread = new Thread(runnable, "GTL-Multiblock-Preview-" + THREAD_IDS.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            workers.allowCoreThreadTimeOut(true);
        }
        return workers;
    }

    public void update() {
        if (closed || PreviewLifecycle.reloading()) return;
        while (scheduled < jobs.size()) {
            try {
                workers().execute(jobs.get(scheduled));
                scheduled++;
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                break;
            }
        }
        try (var budget = PreviewFrameBudget.slice()) {
            while (budget.available()) {
                Part part = ready.poll();
                if (part == null) break;
                if (task.cancelled()) {
                    part.data.release();
                    PreviewBuffers.release(part.builder);
                    continue;
                }
                VertexBuffer buffer = null;
                boolean uploaded = false;
                boolean consumed = false;
                try {
                    buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    int size = part.data.vertexBuffer().remaining() + part.data.indexBuffer().remaining();
                    buffer.bind();
                    if (buffer.isInvalid()) throw new IllegalStateException("Preview VBO creation failed");
                    consumed = true;
                    buffer.upload(part.data);
                    uploaded = true;
                    buffers.get(part.group).add(buffer);
                    bytes += size;
                } catch (RuntimeException error) {
                    failure = error;
                    task.cancel();
                    drain();
                    GTLCore.LOGGER.error("Multiblock preview upload failed", error);
                    return;
                } finally {
                    VertexBuffer.unbind();
                    if (!consumed) part.data.release();
                    if (!uploaded && buffer != null) buffer.close();
                    PreviewBuffers.release(part.builder);
                }
            }
        }
    }

    private void compile(int partition, int from, int to) {
        try {
            ModelBlockRenderer.enableCaching();
            var dispatcher = Minecraft.getInstance().getBlockRenderer();
            var random = RandomSource.create(0);
            var pose = new PoseStack();
            for (int layerIndex = 0; layerIndex < LAYERS.size(); layerIndex++) {
                var layer = LAYERS.get(layerIndex);
                BufferBuilder builder = null;
                int count = 0;
                try {
                    for (int index = from; index < to; index++) {
                        if (task.cancelled()) return;
                        var pos = snapshot.positions.get(index);
                        if (ghost) snapshot.isolate(pos);
                        try {
                            var state = snapshot.getBlockState(pos);
                            var fluid = state.getFluidState();
                            var modelData = snapshot.modelData(pos);
                            boolean blockLayer = state.getRenderShape() != RenderShape.INVISIBLE;
                            if (blockLayer) {
                                var model = dispatcher.getBlockModel(state);
                                modelData = model.getModelData(snapshot, pos, state, modelData);
                                random.setSeed(state.getSeed(pos));
                                // Use the same model data for layer selection and quad generation.
                                blockLayer = model.getRenderTypes(state, random, modelData).contains(layer);
                            }
                            boolean fluidLayer = !fluid.isEmpty() && ItemBlockRenderTypes.getRenderLayer(fluid) == layer;
                            progress.incrementAndGet();
                            if (!blockLayer && !fluidLayer) continue;
                            if (builder == null) {
                                builder = PreviewBuffers.acquire(task);
                                if (builder == null) return;
                                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                            }
                            var wrapper = new WorldSceneRenderer.VertexConsumerWrapper(builder);
                            if (blockLayer) {
                                pose.pushPose();
                                pose.translate(pos.getX(), pos.getY(), pos.getZ());
                                if (ghost) {
                                    pose.translate(0.5, 0.5, 0.5);
                                    pose.scale(0.8f, 0.8f, 0.8f);
                                    pose.translate(-0.5, -0.5, -0.5);
                                }
                                try {
                                    // A sliced snapshot exposes cut faces; ghosts must retain all faces around their
                                    // gaps.
                                    dispatcher.renderBatched(state, pos, snapshot, pose, wrapper, !ghost, random, modelData, layer);
                                } finally {
                                    pose.popPose();
                                }
                            }
                            if (fluidLayer) {
                                wrapper.addOffset(pos.getX() - (pos.getX() & 15),
                                        pos.getY() - (pos.getY() & 15), pos.getZ() - (pos.getZ() & 15));
                                dispatcher.renderLiquid(pos, snapshot, wrapper, state, fluid);
                            }
                            if (++count >= BLOCKS_PER_BUFFER) {
                                var owned = builder;
                                builder = null;
                                if (!publish(layerIndex * partitions + partition, owned)) return;
                                count = 0;
                            }
                        } finally {
                            snapshot.isolate(null);
                        }
                    }
                    if (builder != null) {
                        var owned = builder;
                        builder = null;
                        if (!publish(layerIndex * partitions + partition, owned)) return;
                    }
                } finally {
                    if (builder != null) PreviewBuffers.release(builder);
                }
            }
        } catch (Throwable error) {
            if (!task.cancelled()) {
                failure = error;
                task.cancel();
                GTLCore.LOGGER.error("Multiblock preview compilation failed", error);
            }
        } finally {
            ModelBlockRenderer.clearCache();
            completed.incrementAndGet();
            if (task.cancelled()) drain();
        }
    }

    private boolean publish(int group, BufferBuilder builder) throws InterruptedException {
        var data = builder.endOrDiscardIfEmpty();
        if (data == null) {
            PreviewBuffers.release(builder);
            return true;
        }
        boolean delivered = false;
        try {
            var part = new Part(group, data, builder);
            while (!task.cancelled()) {
                if (ready.offer(part, 25, TimeUnit.MILLISECONDS)) {
                    delivered = true;
                    // Cancellation may have drained just before this offer completed.
                    if (task.cancelled() && ready.remove(part)) {
                        delivered = false;
                    }
                    return !task.cancelled();
                }
            }
            return false;
        } finally {
            if (!delivered) {
                data.release();
                PreviewBuffers.release(builder);
            }
        }
    }

    public boolean complete() {
        return scheduled == jobs.size() && completed.get() == jobs.size() && ready.isEmpty() && failure == null && !closed;
    }

    public boolean failed() {
        return failure != null;
    }

    public double progress() {
        return Math.min(0.99, progress.get() / (double) total);
    }

    public long bytes() {
        return bytes + snapshot.positions.size() * 96L + snapshot.rayIndex.estimatedBytes();
    }

    public PreviewSnapshot snapshot() {
        return snapshot;
    }

    public void draw(Matrix4f modelView, float partialTicks, PoseStack entityPose) {
        if (closed) return;
        var mc = Minecraft.getInstance();
        for (int i = 0; i < LAYERS.size(); i++) {
            var layer = LAYERS.get(i);
            if (layer == RenderType.translucent() && complete()) {
                var source = mc.renderBuffers().bufferSource();
                for (var pos : snapshot.entities) {
                    var entity = snapshot.getBlockEntity(pos);
                    var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(entity);
                    if (renderer == null || !entity.hasLevel() || !entity.getType().isValid(entity.getBlockState())) continue;
                    entityPose.pushPose();
                    entityPose.translate(pos.getX(), pos.getY(), pos.getZ());
                    renderer.render(entity, partialTicks, entityPose, source, 0xF000F0,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                    entityPose.popPose();
                }
                source.endBatch();
            }
            boolean empty = true;
            for (int partition = 0; partition < partitions; partition++) {
                if (!buffers.get(i * partitions + partition).isEmpty()) empty = false;
            }
            if (empty) continue;
            layer.setupRenderState();
            WorldSceneRenderer.setDefaultRenderLayerState(layer);
            try {
                // Completion order must not change the final order of translucent geometry.
                for (int partition = 0; partition < partitions; partition++) {
                    for (var buffer : buffers.get(i * partitions + partition)) {
                        buffer.bind();
                        buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                    }
                }
            } finally {
                VertexBuffer.unbind();
                layer.clearRenderState();
            }
        }
    }

    private void drain() {
        Part part;
        while ((part = ready.poll()) != null) {
            part.data.release();
            PreviewBuffers.release(part.builder);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        task.cancel();
        if (workers != null) jobs.forEach(workers::remove);
        drain();
        buffers.forEach(layer -> {
            layer.forEach(VertexBuffer::close);
            layer.clear();
        });
        LIVE.remove(this);
    }

    public static void clearAll() {
        for (var mesh : List.copyOf(LIVE)) mesh.close();
    }

    private record Part(int group, BufferBuilder.RenderedBuffer data, BufferBuilder builder) {}
}
