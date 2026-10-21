package org.gtlcore.gtlcore.mixin.ae2.service;

import org.gtlcore.gtlcore.integration.ae2.graph.GraphProviderVersion;

import appeng.api.crafting.IPatternDetails;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

@Mixin(targets = "appeng.me.service.helpers.NetworkCraftingProviders$ProviderState")
public abstract class NetworkCraftingProviderStateMixin {

    @WrapOperation(method = "mount",
                   at = @At(value = "INVOKE",
                            target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;",
                            ordinal = 1),
                   remap = false)
    private Object gtlcore$indexMountedPattern(Map<Object, Object> methods, Object key, Function<Object, Object> factory,
                                               Operation<Object> original, NetworkCraftingProviders providers) {
        boolean existed = methods.containsKey(key);
        Object result = original.call(methods, key, factory);
        if (!existed && result != null) ((GraphProviderVersion) providers).gtlcore$patternChanged((IPatternDetails) key, true);
        return result;
    }

    @WrapOperation(method = "unmount",
                   at = @At(value = "INVOKE",
                            target = "Ljava/util/Map;computeIfPresent(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
                            ordinal = 1),
                   remap = false)
    private Object gtlcore$indexUnmountedPattern(Map<Object, Object> methods, Object key, BiFunction<Object, Object, Object> change,
                                                 Operation<Object> original, NetworkCraftingProviders providers) {
        Object result = original.call(methods, key, change);
        if (result == null) ((GraphProviderVersion) providers).gtlcore$patternChanged((IPatternDetails) key, false);
        return result;
    }
}
