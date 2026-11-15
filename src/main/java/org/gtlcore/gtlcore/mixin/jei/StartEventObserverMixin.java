package org.gtlcore.gtlcore.mixin.jei;

import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import mezz.jei.forge.events.PermanentEventSubscriptions;
import mezz.jei.forge.startup.StartEventObserver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StartEventObserver.class, remap = false)
public abstract class StartEventObserverMixin {

    @Unique
    private boolean gtlcore$observedTagSync;

    @Shadow
    protected abstract boolean shouldWaitForRecipes();

    @Shadow
    protected abstract void startIfReady();

    @Inject(method = "register", at = @At("HEAD"), remap = false)
    private void gtlcore$registerTags(PermanentEventSubscriptions subscriptions, CallbackInfo ci) {
        subscriptions.register(EventPriority.LOWEST, TagsUpdatedEvent.class, this::gtlcore$onTagsUpdated);
    }

    @Unique
    private void gtlcore$onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) {
            return;
        }
        this.gtlcore$observedTagSync = true;
        this.startIfReady();
    }

    @Inject(method = "startIfReady", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$waitForTags(CallbackInfo ci) {
        if (this.shouldWaitForRecipes() && !this.gtlcore$observedTagSync) {
            ci.cancel();
        }
    }

    @Inject(method = "clearObservedStartEvents", at = @At("TAIL"), remap = false)
    private void gtlcore$clearTags(CallbackInfo ci) {
        this.gtlcore$observedTagSync = false;
    }
}
