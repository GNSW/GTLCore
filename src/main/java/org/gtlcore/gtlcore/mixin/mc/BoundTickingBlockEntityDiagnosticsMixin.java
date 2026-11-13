package org.gtlcore.gtlcore.mixin.mc;

import org.gtlcore.gtlcore.common.diagnostics.BlockEntityValidityDiagnostics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityDiagnosticsMixin {

    @Shadow
    @Final
    private BlockEntity blockEntity;

    @Unique
    private BlockEntityValidityDiagnostics.Tracker gtlcore$validityTracker;

    @WrapOperation(method = "tick",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntityType;isValid(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean gtlcore$observeValidity(BlockEntityType<?> type, BlockState state, Operation<Boolean> original) {
        var session = BlockEntityValidityDiagnostics.activeSession();
        if (session == null) {
            gtlcore$validityTracker = null;
            return original.call(type, state);
        }
        if (!(blockEntity.getLevel() instanceof ServerLevel level) || !session.accepts(level)) {
            return original.call(type, state);
        }
        if (gtlcore$validityTracker == null || !gtlcore$validityTracker.matches(session, type, level.dimension())) {
            gtlcore$validityTracker = session.tracker(type, level.dimension());
        }
        boolean result = original.call(type, state);
        gtlcore$validityTracker.record(state, result);
        return result;
    }
}
