package org.gtlcore.gtlcore.mixin.gtm.cover;

import com.gregtechceu.gtceu.api.machine.ConditionalSubscriptionHandler;

import net.minecraft.server.TickTask;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConditionalSubscriptionHandler.class)
public abstract class ConditionalSubscriptionHandlerMixin {

    @Unique
    private boolean gtlcore$stale;

    @Inject(method = "initialize(Lnet/minecraft/world/level/Level;)V", at = @At("HEAD"), remap = false)
    private void gtlcore$resetOnLevelInit(Level level, CallbackInfo ci) {
        gtlcore$stale = false;
    }

    @Inject(method = "initialize(Lnet/minecraft/util/thread/BlockableEventLoop;)V", at = @At("HEAD"), remap = false)
    private void gtlcore$resetOnServerInit(BlockableEventLoop<TickTask> server, CallbackInfo ci) {
        gtlcore$stale = false;
    }

    @Inject(method = "unsubscribe", at = @At("HEAD"), remap = false)
    private void gtlcore$markStale(CallbackInfo ci) {
        gtlcore$stale = true;
    }

    @Inject(method = "updateSubscription", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$skipAfterUnsubscribe(CallbackInfo ci) {
        if (gtlcore$stale) {
            ci.cancel();
        }
    }
}
