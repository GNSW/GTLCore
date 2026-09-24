package org.gtlcore.gtlcore.mixin.mc;

import org.gtlcore.gtlcore.integration.ae2.graph.GraphSnapshots;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Run world capture only after vanilla tasks, while the server is waiting for its next tick. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerGraphSnapshotMixin {

    @Shadow
    private long nextTickTime;
    @Unique
    private boolean gtlcore$waitingForTick;

    @Inject(method = "waitUntilNextTick", at = @At("HEAD"))
    private void gtlcore$beginTickWait(CallbackInfo ci) {
        gtlcore$waitingForTick = true;
    }

    @Inject(method = "waitUntilNextTick", at = @At("RETURN"))
    private void gtlcore$endTickWait(CallbackInfo ci) {
        gtlcore$waitingForTick = false;
    }

    @Inject(method = "pollTask", at = @At("RETURN"), cancellable = true)
    private void gtlcore$captureDuringIdleTime(CallbackInfoReturnable<Boolean> cir) {
        if (gtlcore$waitingForTick && !cir.getReturnValueZ() && GraphSnapshots.idle(nextTickTime)) {
            // Do not set vanilla's mayHaveDelayedTasks flag: that flag can extend
            // the tick deadline. Each continuation returns to vanilla task polling.
            cir.setReturnValue(true);
        }
    }
}
