package org.gtlcore.gtlcore.mixin.gtm.cover;

import org.gtlcore.gtlcore.common.cover.GroupedFluidTransfer;
import org.gtlcore.gtlcore.common.cover.GroupedSupplyWidget;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.cover.CoverDefinition;
import com.gregtechceu.gtceu.api.cover.filter.SimpleFluidFilter;
import com.gregtechceu.gtceu.common.cover.FluidRegulatorCover;
import com.gregtechceu.gtceu.common.cover.PumpCover;
import com.gregtechceu.gtceu.common.cover.data.TransferMode;

import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.core.Direction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

@Mixin(FluidRegulatorCover.class)
public abstract class FluidRegulatorCoverMixin extends PumpCover {

    @Shadow(remap = false)
    private TransferMode transferMode;
    @Shadow(remap = false)
    protected long fluidTransferBuffered;
    @Unique
    @Persisted
    @DescSynced
    private boolean gtlcore$supplyAll;
    @Unique
    private long gtlcore$batchCredit;

    public FluidRegulatorCoverMixin(CoverDefinition definition, ICoverable coverHolder, Direction side, int tier) {
        super(definition, coverHolder, side, tier);
    }

    @Inject(method = "buildAdditionalUI", at = @At("TAIL"), remap = false)
    private void gtlcore$addGroupedSupply(WidgetGroup group, CallbackInfo ci) {
        new GroupedSupplyWidget(group, () -> gtlcore$supplyAll, this::gtlcore$canSupplyAll, enabled -> {
            gtlcore$supplyAll = enabled;
            gtlcore$batchCredit = 0;
            fluidTransferBuffered = 0;
            onChanged();
        });
    }

    @Unique
    private boolean gtlcore$canSupplyAll() {
        return transferMode == TransferMode.TRANSFER_EXACT && filterHandler.getFilter() instanceof SimpleFluidFilter filter &&
                !filter.isBlackList() && Arrays.stream(filter.getMatches()).anyMatch(stack -> !stack.isEmpty());
    }

    @Inject(method = { "configureFilter", "setTransferMode" }, at = @At("TAIL"), remap = false)
    private void gtlcore$resetBatch(CallbackInfo ci) {
        gtlcore$batchCredit = 0;
        if (gtlcore$supplyAll) fluidTransferBuffered = 0;
    }

    @Override
    protected IFluidTransfer getAdjacentFluidTransfer() {
        if (gtlcore$supplyAll && transferMode == TransferMode.TRANSFER_EXACT) {
            Direction side = attachedSide.getOpposite();
            ICoverable adjacent = GTCapabilityHelper.getCoverable(coverHolder.getLevel(), coverHolder.getPos().relative(attachedSide), side);
            if (adjacent != null) return adjacent.getFluidTransferCap(side, true);
        }
        return super.getAdjacentFluidTransfer();
    }

    @Inject(method = "transferExact", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$transferBatch(IFluidTransfer source, IFluidTransfer target, long limit, CallbackInfoReturnable<Long> cir) {
        if (!gtlcore$supplyAll) return;
        GroupedFluidTransfer batch = gtlcore$canSupplyAll() ?
                GroupedFluidTransfer.plan(source, target, (SimpleFluidFilter) filterHandler.getFilter()) : null;
        if (batch == null) {
            gtlcore$batchCredit = 0;
            cir.setReturnValue(0L);
            return;
        }
        long charged = Math.min(batch.amount() - gtlcore$batchCredit, limit);
        gtlcore$batchCredit += charged;
        if (gtlcore$batchCredit == batch.amount()) {
            batch.execute(source, target);
            gtlcore$batchCredit = 0;
        }
        cir.setReturnValue(charged);
    }
}
