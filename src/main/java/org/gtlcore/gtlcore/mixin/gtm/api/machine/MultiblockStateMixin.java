package org.gtlcore.gtlcore.mixin.gtm.api.machine;

import org.gtlcore.gtlcore.api.pattern.PatternCapacityHints;
import org.gtlcore.gtlcore.api.pattern.PatternCapacityState;
import org.gtlcore.gtlcore.api.pattern.PatternRetryController;

import com.gregtechceu.gtceu.api.block.ActiveBlock;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.*;
import com.gregtechceu.gtceu.common.machine.multiblock.part.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.hepdd.gtmthings.common.block.machine.multiblock.part.HugeBusPartMachine;
import it.unimi.dsi.fastutil.longs.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiblockState.class)
public abstract class MultiblockStateMixin implements PatternCapacityState {

    @Unique
    private final PatternCapacityHints gtlcore$capacityHints = new PatternCapacityHints();

    @Override
    public PatternCapacityHints gtlcore$getCapacityHints() {
        return gtlcore$capacityHints;
    }

    @Inject(method = "clean", at = @At("HEAD"), remap = false)
    private void gtlcore$captureAttemptSizes(CallbackInfo ci) {
        gtlcore$capacityHints.beforeClean((MultiblockState) (Object) this);
    }

    @Redirect(method = "clean", at = @At(value = "NEW", target = "it/unimi/dsi/fastutil/longs/LongOpenHashSet"), remap = false)
    private LongOpenHashSet gtlcore$allocatePositionCache() {
        return gtlcore$capacityHints.newCache();
    }

    @Final
    @Shadow(remap = false)
    public Level world;

    @Final
    @Shadow(remap = false)
    public BlockPos controllerPos;

    @Shadow(remap = false)
    public IMultiController lastController;

    /**
     * @author Dragons
     * @reason Fix the issue that duplicate check form when the output work
     */
    @Overwrite(remap = false)
    public void onBlockStateChanged(BlockPos pos, BlockState state) {
        if (this.world instanceof ServerLevel serverLevel) {
            if (!serverLevel.getServer().isSameThread()) {
                BlockPos changed = pos.immutable();
                serverLevel.getServer().execute(() -> onBlockStateChanged(changed, state));
                return;
            }
            if (pos.equals(this.controllerPos)) {
                if (this.lastController != null && !state.is(this.lastController.self().getBlockState().getBlock())) {
                    ((PatternRetryController) this.lastController).gtlcore$onStructureBlockChanged(true);
                }
            } else {
                final var tempThis = (MultiblockState) (Object) this;
                final IMultiController controller = tempThis.getController();
                if (controller != null) {
                    final boolean formed = controller.isFormed();
                    if (formed) {
                        if (state.getBlock() instanceof ActiveBlock) {
                            LongSet activeBlocks = tempThis.getMatchContext().getOrDefault("vaBlocks", LongSets.emptySet());
                            if (activeBlocks.contains(pos.asLong())) {
                                return;
                            }
                        } else if (serverLevel.getBlockEntity(pos) instanceof IMachineBlockEntity IMBE) {
                            var metaMachine = IMBE.getMetaMachine();
                            if (metaMachine instanceof ItemBusPartMachine ||
                                    metaMachine instanceof FluidHatchPartMachine ||
                                    metaMachine instanceof HugeBusPartMachine)
                                return;
                        }
                    }

                    ((PatternRetryController) controller).gtlcore$onStructureBlockChanged(false);
                }
            }
        }
    }
}
