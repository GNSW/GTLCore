/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.lighting.WorldLightProvider;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.scheduler.LightOwnerFailure;
import org.voxelregion.lighting.scheduler.LightPublicationCohorts;

/**
 * VRE completion receipts over the ordinary Minecraft light owner. Every request crosses
 * its own sorter lane before joining a batch; publication cannot overtake earlier native
 * PRE_UPDATE tasks. Successful native tickets and POST_UPDATE tasks retain their order.
 * A failed owner acknowledges its sorter lane but stops later success callbacks, fails
 * generation/publication receipts, and invalidates the affected columns before cleanup.
 */
@Mixin(ThreadedLevelLightEngine.class)
abstract class WorldLightFenceMixin implements NativeAccess.LightFence {
    @Shadow @Final private ChunkMap chunkMap;
    @Shadow @Final private ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> sorterMailbox;
    @Shadow @Final private ProcessorMailbox<Runnable> taskMailbox;
    @Shadow @Final private ObjectList<?> lightTasks;
    @Shadow @Final private AtomicBoolean scheduled;
    @Shadow private void runUpdate() { throw new AssertionError(); }

    @Unique private final LightOwnerFailure vre$lightOwnerFailure = new LightOwnerFailure(this::vre$failWorldLight);
    @Unique private final LightPublicationCohorts<Consumer<WorldLightProvider>> vre$worldLightCohorts =
            new LightPublicationCohorts<>(this::vre$dispatchOwner, this::vre$flushWorldLight);

    @Unique private void vre$dispatchOwner(Runnable task) {
        try { taskMailbox.tell(() -> vre$lightOwnerFailure.run(task)); }
        catch (RuntimeException | Error failure) {
            vre$lightOwnerFailure.fail(failure);
            throw failure;
        }
    }

    @Unique private void vre$failWorldLight(Throwable failure) {
        // The failing owner runnable has unwound: no publication is still reading its inputs.
        // In particular, never execute native success-only POST tasks after a failed solve.
        lightTasks.clear();
        scheduled.set(false);
        try {
            WorldLightProvider provider = ((WorldLightProvider.Access)(Object)this).vre$worldLighting();
            if (provider != null) provider.failTerminal(failure);
        } finally { vre$worldLightCohorts.fail(failure); }
    }

    @Unique private ChunkTaskPriorityQueueSorter.Message<Runnable> vre$lightMessage(
            Runnable task, long position, IntSupplier priority) {
        if (((WorldLightProvider.Access)(Object)this).vre$worldLighting() == null)
            return ChunkTaskPriorityQueueSorter.message(task, position, priority);
        // Use the native generic message factory, but acknowledge this light-owner lane in
        // finally. An ACK is queue progress, not the generation/publication business receipt.
        return ChunkTaskPriorityQueueSorter.<Runnable>message(handle -> () ->
                LightOwnerFailure.runAcknowledged(() -> vre$lightOwnerFailure.run(task),
                        () -> handle.tell(Unit.INSTANCE)), position, priority);
    }

    @Redirect(method = "addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;message(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter$Message;"))
    private ChunkTaskPriorityQueueSorter.Message<Runnable> vre$acknowledgeNativeLight(
            Runnable task, long position, IntSupplier priority) {
        return vre$lightMessage(task, position, priority);
    }

    @Inject(method = "tryScheduleUpdate", at = @At("HEAD"), cancellable = true)
    private void vre$stopFailedOwner(CallbackInfo callback) {
        if (vre$lightOwnerFailure.failure() != null) callback.cancel();
    }

    @Redirect(method = "runUpdate", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V", remap = false))
    private void vre$skipSuccessTasksAfterFatal(Runnable task) {
        // A native AsyncSupply catches Error rather than throwing out of runUpdate. Its
        // tracked future latches the failure, so later native POST tasks must not mark
        // other columns lightCorrect=true before the owner reports that same failure.
        if (vre$lightOwnerFailure.failure() == null) task.run();
    }

    @Inject(method = "runUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
    private void vre$stopBeforeFurtherComputation(CallbackInfo callback) {
        vre$lightOwnerFailure.checkHealthy();
    }

    @Redirect(method = "tryScheduleUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/thread/ProcessorMailbox;tell(Ljava/lang/Object;)V"))
    private void vre$resetScheduledAfterFailure(ProcessorMailbox<Runnable> mailbox, Object message) {
        if (((WorldLightProvider.Access)(Object)this).vre$worldLighting() == null) {
            mailbox.tell((Runnable)message); return;
        }
        try {
            mailbox.tell(() -> {
                try { vre$lightOwnerFailure.run((Runnable)message); }
                finally { scheduled.set(false); }
            });
        } catch (RuntimeException | Error failure) {
            scheduled.set(false);
            // Admission failed before any work started. Queue failure must not strand futures.
            vre$lightOwnerFailure.fail(failure);
            throw failure;
        }
    }

    // AutoCloseable.close retains its Java interface name in the production namespace.
    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void vre$closeWorldLight(CallbackInfo callback) {
        if (((WorldLightProvider.Access)(Object)this).vre$worldLighting() != null)
            vre$lightOwnerFailure.fail(new CancellationException("Light engine closed"));
        vre$worldLightCohorts.close();
    }

    @Override public boolean vre$rebuildsWholeChunks() { return true; }

    @Inject(method = "initializeLight", at = @At("RETURN"), cancellable = true)
    private void vre$trackInitialization(ChunkAccess requested, boolean alreadyLit,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        if (((WorldLightProvider.Access)(Object)this).vre$worldLighting() != null)
            callback.setReturnValue(vre$lightOwnerFailure.track(callback.getReturnValue(),
                    failure -> requested.setLightCorrect(false)));
    }

    @Inject(method = "lightChunk", at = @At("RETURN"), cancellable = true)
    private void vre$verifyGenerationPublication(ChunkAccess requested, boolean alreadyLit,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        WorldLightProvider provider = ((WorldLightProvider.Access)(Object)this).vre$worldLighting();
        if (provider == null) return;
        // On success native POST releases its ticket and completes its supplier. A missing
        // field fails the dependent future; a fatal owner error stops the whole light owner.
        // An already completed future would run plain thenApply on its registering thread.
        // Explicit owner dispatch also keeps the implicit-NBT capture on the light mailbox.
        CompletableFuture<ChunkAccess> verified = callback.getReturnValue().thenApplyAsync(chunk -> {
            Throwable terminal = vre$lightOwnerFailure.failure();
            if (terminal != null) throw new java.util.concurrent.CompletionException(terminal);
            try {
                // Vanilla NBT may contain no light arrays for an implicitly empty/zero
                // field. No queueSectionData callback exists in that legitimate case.
                if (alreadyLit) provider.initializeImplicitSavedColumn(chunk.getPos());
                provider.requirePublished(chunk.getPos());
            }
            catch (RuntimeException | Error failure) {
                chunk.setLightCorrect(false);
                throw failure;
            }
            return chunk;
        }, this::vre$dispatchOwner);
        callback.setReturnValue(vre$lightOwnerFailure.track(verified, failure -> {
            requested.setLightCorrect(false);
            // The native success POST is intentionally discarded after terminal failure.
            // This method itself schedules the ticket removal on the server owner.
            ((ChunkMapAccessMixin)chunkMap).vre$releaseLightTicket(requested.getPos());
        }));
    }

    @Override public CompletableFuture<Void> vre$afterChunkWork(ChunkPos position) {
        return vre$submitWorldLight(position, provider -> { });
    }

    @Override public CompletableFuture<Void> vre$fullChecks(ChunkPos position, int minSectionY, int sectionCount) {
        if (sectionCount < 1 || sectionCount > ClearedChunkLighting.MAX_HEIGHT / 16)
            throw new IllegalArgumentException("Invalid full-column light height");
        Objects.requireNonNull(position, "position");
        // Rebuild includes the complete world height and affected neighboring columns. Partial
        // height is deliberately not used to truncate a full-column provider's influence.
        return vre$submitWorldLight(position, provider -> provider.rebuild(position));
    }

    @Override public CompletableFuture<Void> vre$changedChecks(ChunkPos position,
            List<NativeAccess.LightChanges> changes) {
        return vre$combinedChecks(position, changes, new int[0], false);
    }

    @Override public CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs) {
        return vre$batchChecks(position, sectionYs, false);
    }

    @Override public CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs,
            boolean replaySkySources) {
        return vre$combinedChecks(position, List.of(), sectionYs, replaySkySources);
    }

    @Override public CompletableFuture<Void> vre$combinedChecks(ChunkPos position,
            List<NativeAccess.LightChanges> changes, int[] boundarySectionYs, boolean replaySkySources) {
        Objects.requireNonNull(position, "position");
        List<NativeAccess.LightChanges> captured = List.copyOf(changes);
        boolean rebuild = Objects.requireNonNull(boundarySectionYs, "boundarySectionYs").length != 0 || replaySkySources;
        if (rebuild) return vre$submitWorldLight(position, provider -> provider.rebuild(position));
        return vre$submitWorldLight(position, provider -> {
            for (NativeAccess.LightChanges change : captured) {
                var cells = change.cells();
                int baseY = Math.multiplyExact(change.sectionY(), 16);
                for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1)) {
                    provider.changed(new BlockPos(position.getMinBlockX() + (cell & 15),
                            baseY + (cell >>> 8), position.getMinBlockZ() + ((cell >>> 4) & 15)));
                }
            }
        });
    }

    @Unique private CompletableFuture<Void> vre$submitWorldLight(ChunkPos position,
            Consumer<WorldLightProvider> request) {
        Objects.requireNonNull(position, "position");
        return vre$worldLightCohorts.submit(position.toLong(), request, marker ->
                sorterMailbox.tell(vre$lightMessage(marker, position.toLong(),
                        ((NativeAccess.ChunkSave)chunkMap).vre$queueLevel(position.toLong()))))
                .whenComplete((unused, failure) -> {
                    if (failure != null) {
                        WorldLightProvider provider = ((WorldLightProvider.Access)(Object)this).vre$worldLighting();
                        // A native failure can precede this request's rebuild action. Its
                        // caller already changed the column, so invalidate before the public
                        // failed receipt lets that caller release its read lease/save fence.
                        if (provider != null) provider.invalidateFailedPublication(position);
                    }
                });
    }

    @Unique private void vre$flushWorldLight(
            List<LightPublicationCohorts.Request<Consumer<WorldLightProvider>>> requests) {
        // Every marker in this finite FIFO prefix crossed its own chunk's sorter lane.
        // Markers do not wait for this callback or its numerical workers. Native PRE/POST
        // and tickets keep their original boundaries, including the 1000-task limit.
        do { runUpdate(); } while (!lightTasks.isEmpty());
        vre$lightOwnerFailure.checkHealthy();
        WorldLightProvider provider = ((WorldLightProvider.Access)(Object)this).vre$worldLighting();
        if (provider == null) throw new IllegalStateException("World light fence has no active provider");
        for (var request : requests) request.action().accept(provider);
        for (var request : requests)
            provider.afterPublication(new ChunkPos(request.column()), request.completion());
        provider.drain();
    }

    @Override public CompletableFuture<Boolean> vre$clearAirRegion(List<NativeAccess.OpenColumn> positions,
            int minSectionY, int sectionCount, boolean hasSky, BooleanSupplier stillValid) {
        // The whole-column route uses the provider's own solver and publication protocol.
        return CompletableFuture.completedFuture(false);
    }

    @Override public CompletableFuture<Boolean> vre$solveAndInstall(ChunkPos position, int minSectionY,
            Supplier<ClearedChunkLighting.Snapshot> input, BooleanSupplier stillValid, Runnable releaseInput) {
        try {
            Objects.requireNonNull(releaseInput, "releaseInput").run();
            return CompletableFuture.completedFuture(false);
        } catch (RuntimeException | LinkageError failure) { return CompletableFuture.failedFuture(failure); }
    }
}
