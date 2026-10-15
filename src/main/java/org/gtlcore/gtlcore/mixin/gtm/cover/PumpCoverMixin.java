package org.gtlcore.gtlcore.mixin.gtm.cover;

import org.gtlcore.gtlcore.common.data.GTLCovers;

import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.cover.CoverBehavior;
import com.gregtechceu.gtceu.api.cover.CoverDefinition;
import com.gregtechceu.gtceu.common.cover.PumpCover;

import net.minecraft.core.Direction;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PumpCover.class)
public abstract class PumpCoverMixin extends CoverBehavior {

    @Mutable
    @Shadow(remap = false)
    @Final
    public long maxMilliBucketsPerTick;

    @Shadow(remap = false)
    protected long currentMilliBucketsPerTick;

    public PumpCoverMixin(CoverDefinition definition, ICoverable coverHolder, Direction attachedSide) {
        super(definition, coverHolder, attachedSide);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    public void PumpCover(CoverDefinition definition, ICoverable coverHolder, Direction attachedSide, int tier, CallbackInfo ci) {
        long oldMax = this.maxMilliBucketsPerTick;
        this.maxMilliBucketsPerTick = GTLCovers.fluidTransferRate(tier);
        if (this.currentMilliBucketsPerTick == oldMax) {
            this.currentMilliBucketsPerTick = this.maxMilliBucketsPerTick;
        }
    }
}
