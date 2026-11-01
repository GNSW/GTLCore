package org.gtlcore.gtlcore.mixin.gtm.cover;

import org.gtlcore.gtlcore.common.cover.GroupedItemTransfer;
import org.gtlcore.gtlcore.common.cover.GroupedSupplyWidget;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.cover.CoverDefinition;
import com.gregtechceu.gtceu.api.cover.filter.SimpleItemFilter;
import com.gregtechceu.gtceu.common.cover.ConveyorCover;
import com.gregtechceu.gtceu.common.cover.RobotArmCover;
import com.gregtechceu.gtceu.common.cover.data.TransferMode;

import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
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

@Mixin(RobotArmCover.class)
public abstract class RobotArmCoverMixin extends ConveyorCover {

    @Shadow(remap = false)
    protected TransferMode transferMode;
    @Shadow(remap = false)
    protected int itemsTransferBuffered;
    @Unique
    @Persisted
    @DescSynced
    private boolean gtlcore$supplyAll;
    @Unique
    private long gtlcore$batchCredit;

    public RobotArmCoverMixin(CoverDefinition definition, ICoverable coverHolder, Direction side, int tier) {
        super(definition, coverHolder, side, tier);
    }

    @Inject(method = "buildAdditionalUI", at = @At("TAIL"), remap = false)
    private void gtlcore$addGroupedSupply(WidgetGroup group, CallbackInfo ci) {
        new GroupedSupplyWidget(group, () -> gtlcore$supplyAll, this::gtlcore$canSupplyAll, enabled -> {
            gtlcore$supplyAll = enabled;
            gtlcore$batchCredit = 0;
            itemsTransferBuffered = 0;
            onChanged();
        });
    }

    @Unique
    private boolean gtlcore$canSupplyAll() {
        return transferMode == TransferMode.TRANSFER_EXACT && filterHandler.getFilter() instanceof SimpleItemFilter filter &&
                !filter.isBlackList() && Arrays.stream(filter.getMatches()).anyMatch(stack -> !stack.isEmpty());
    }

    @Inject(method = { "configureFilter", "setTransferMode" }, at = @At("TAIL"), remap = false)
    private void gtlcore$resetBatch(CallbackInfo ci) {
        gtlcore$batchCredit = 0;
        if (gtlcore$supplyAll) itemsTransferBuffered = 0;
    }

    @Override
    protected IItemTransfer getAdjacentItemTransfer() {
        if (gtlcore$supplyAll && transferMode == TransferMode.TRANSFER_EXACT) {
            Direction side = attachedSide.getOpposite();
            ICoverable adjacent = GTCapabilityHelper.getCoverable(coverHolder.getLevel(), coverHolder.getPos().relative(attachedSide), side);
            if (adjacent != null) return adjacent.getItemTransferCap(side, true);
        }
        return super.getAdjacentItemTransfer();
    }

    @Inject(method = "doTransferExact", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$transferBatch(IItemTransfer source, IItemTransfer target, int limit, CallbackInfoReturnable<Integer> cir) {
        if (!gtlcore$supplyAll) return;
        GroupedItemTransfer batch = gtlcore$canSupplyAll() ?
                GroupedItemTransfer.plan(source, target, (SimpleItemFilter) filterHandler.getFilter()) : null;
        if (batch == null) {
            gtlcore$batchCredit = 0;
            cir.setReturnValue(0);
            return;
        }
        int charged = (int) Math.min(batch.amount() - gtlcore$batchCredit, limit);
        gtlcore$batchCredit += charged;
        if (gtlcore$batchCredit == batch.amount()) {
            batch.execute(source, target);
            gtlcore$batchCredit = 0;
        }
        // Reserved credit must be deducted from the parent's current rate budget.
        cir.setReturnValue(charged);
    }
}
