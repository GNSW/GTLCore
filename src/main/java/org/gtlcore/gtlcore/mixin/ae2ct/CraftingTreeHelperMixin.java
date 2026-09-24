package org.gtlcore.gtlcore.mixin.ae2ct;

import org.gtlcore.gtlcore.integration.ae2.graph.CraftingTreeCycleNode;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Optional AE2CT integration: its recursive tree renderer assumes acyclic recipes. */
@Pseudo
@Mixin(targets = "com.neuvillette.ae2ct.api.CraftingTreeHelper", remap = false)
public abstract class CraftingTreeHelperMixin {

    @Unique
    private final Map<AEKey, Integer> gtlcore$treePath = new HashMap<>();

    @Shadow
    private Map<AEKey, Object> amountCache;

    @Coerce
    @WrapMethod(method = "buildNode", remap = false)
    private Object gtlcore$stopCycleExpansion(GenericStack stack, Long amount, List<GenericStack> inputs,
                                              long times, long outputAmount, @Coerce Object parent,
                                              Operation<Object> original) {
        Integer ancestorDepth = gtlcore$treePath.get(stack.what());
        if (ancestorDepth != null) {
            // A back-reference must not consume the display's missing/stored counts.
            Object previous = amountCache.get(stack.what());
            try {
                Object leaf = original.call(stack, amount, List.of(), times, outputAmount, parent);
                if (leaf instanceof CraftingTreeCycleNode cycle) {
                    cycle.gtlcore$setCycleDistance(gtlcore$treePath.size() - ancestorDepth);
                }
                return leaf;
            } finally {
                if (previous == null) amountCache.remove(stack.what());
                else amountCache.put(stack.what(), previous);
            }
        }
        // Track ancestors, not all visited keys, so shared DAG branches stay visible.
        gtlcore$treePath.put(stack.what(), gtlcore$treePath.size());
        try {
            return original.call(stack, amount, inputs, times, outputAmount, parent);
        } finally {
            gtlcore$treePath.remove(stack.what());
        }
    }
}
