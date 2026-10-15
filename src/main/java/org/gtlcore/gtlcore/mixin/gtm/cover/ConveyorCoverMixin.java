package org.gtlcore.gtlcore.mixin.gtm.cover;

import org.gtlcore.gtlcore.common.data.GTLCovers;

import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.cover.CoverBehavior;
import com.gregtechceu.gtceu.api.cover.CoverDefinition;
import com.gregtechceu.gtceu.common.cover.ConveyorCover;

import net.minecraft.core.Direction;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConveyorCover.class)
public abstract class ConveyorCoverMixin extends CoverBehavior {

    @Shadow(remap = false)
    @Final
    public int tier;

    @Mutable
    @Shadow(remap = false)
    @Final
    public int maxItemTransferRate;

    @Shadow(remap = false)
    protected int transferRate;

    public ConveyorCoverMixin(CoverDefinition definition, ICoverable coverHolder, Direction attachedSide) {
        super(definition, coverHolder, attachedSide);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    public void ConveyorCover(CoverDefinition definition, ICoverable coverHolder, Direction attachedSide, int tier, CallbackInfo ci) {
        int oldMax = this.maxItemTransferRate;
        this.maxItemTransferRate = GTLCovers.itemTransferRate(tier);
        if (this.transferRate == oldMax) {
            this.transferRate = this.maxItemTransferRate;
        }
    }
}
