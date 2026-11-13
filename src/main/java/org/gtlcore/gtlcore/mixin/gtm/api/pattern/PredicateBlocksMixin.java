package org.gtlcore.gtlcore.mixin.gtm.api.pattern;

import com.gregtechceu.gtceu.api.pattern.predicates.PredicateBlocks;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import net.minecraft.world.level.block.Block;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PredicateBlocks.class, remap = false)
public abstract class PredicateBlocksMixin {

    @Inject(method = "buildPredicate", at = @At("RETURN"))
    private void gtlcore$indexBlocks(CallbackInfoReturnable<SimplePredicate> cir) {
        var target = (PredicateBlocks) (Object) this;
        // GTCEu has filtered the candidates and supplied its empty-list fallback at this point.
        // Rebuilding the predicate also rebuilds this index; the preview keeps its original array/order.
        if (target.blocks.length == 1) {
            Block block = target.blocks[0];
            target.predicate = state -> state.getBlockState().getBlock() == block;
        } else {
            var blocks = new ReferenceOpenHashSet<>(target.blocks);
            target.predicate = state -> blocks.contains(state.getBlockState().getBlock());
        }
    }
}
