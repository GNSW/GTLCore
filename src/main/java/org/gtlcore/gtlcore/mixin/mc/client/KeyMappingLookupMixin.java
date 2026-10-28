package org.gtlcore.gtlcore.mixin.mc.client;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyMappingLookup;
import net.minecraftforge.client.settings.KeyModifier;

import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** KeyMapping constructors can run concurrently while Forge loads mod subscribers. */
@Mixin(value = KeyMappingLookup.class, remap = false)
public abstract class KeyMappingLookupMixin {

    @Shadow
    @Final
    private static EnumMap<KeyModifier, Map<InputConstants.Key, List<KeyMapping>>> map;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void gtlcore$initializeConcurrentLookup(CallbackInfo ci) {
        // The modifier buckets are fixed for the lifetime of the lookup.
        map.replaceAll((modifier, bindings) -> new ConcurrentHashMap<>(bindings));
    }

    @Inject(method = "put", at = @At("HEAD"), cancellable = true)
    private void gtlcore$registerAtomically(InputConstants.Key keyCode, KeyMapping keyBinding, CallbackInfo ci) {
        map.get(keyBinding.getKeyModifier()).compute(keyCode, (key, bindings) -> {
            if (bindings == null) bindings = new CopyOnWriteArrayList<>();
            bindings.add(keyBinding);
            return bindings;
        });
        ci.cancel();
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void gtlcore$removeAtomically(KeyMapping keyBinding, CallbackInfo ci) {
        // Removal and empty-bucket cleanup must not discard a concurrent registration.
        map.get(keyBinding.getKeyModifier()).computeIfPresent(keyBinding.getKey(), (key, bindings) -> {
            bindings.remove(keyBinding);
            return bindings.isEmpty() ? null : bindings;
        });
        ci.cancel();
    }
}
