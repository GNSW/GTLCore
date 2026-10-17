package org.gtlcore.gtlcore.mixin.ae2.crafting;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanSummaryEntry;

import net.minecraft.network.FriendlyByteBuf;

import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CraftingPlanSummaryEntry.class)
public class CraftingPlanSummaryEntryMixin implements ICraftingPlanSummaryEntry {

    @Unique
    private long gtlcore$craftTimes = 0;
    @Unique
    private long gtlcore$graphSeed;
    @Unique
    private boolean gtlcore$missingGraphSeed;

    @Override
    public long gtlcore$getGraphSeed() {
        return gtlcore$graphSeed;
    }

    @Override
    public void gtlcore$setGraphSeed(long amount) {
        this.gtlcore$graphSeed = amount;
    }

    @Override
    public boolean gtlcore$isMissingGraphSeed() {
        return gtlcore$missingGraphSeed;
    }

    @Override
    public void gtlcore$setMissingGraphSeed(boolean missing) {
        this.gtlcore$missingGraphSeed = missing;
    }

    public long gtlcore$getCraftTimes() {
        return gtlcore$craftTimes;
    }

    public void gtlcore$setCraftTimes(long craftTimes) {
        this.gtlcore$craftTimes = craftTimes;
    }

    @Inject(at = @At("TAIL"), method = "write", remap = false)
    private void write(FriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeVarLong(gtlcore$craftTimes);
        buffer.writeVarLong(gtlcore$graphSeed);
        buffer.writeBoolean(gtlcore$missingGraphSeed);
    }

    @Inject(at = @At("TAIL"), method = "read", cancellable = true, remap = false)
    private static void read(FriendlyByteBuf buffer, CallbackInfoReturnable<CraftingPlanSummaryEntry> cir) {
        var entry = cir.getReturnValue();
        ((ICraftingPlanSummaryEntry) entry).gtlcore$setCraftTimes(buffer.readVarLong());
        ((ICraftingPlanSummaryEntry) entry).gtlcore$setGraphSeed(buffer.readVarLong());
        ((ICraftingPlanSummaryEntry) entry).gtlcore$setMissingGraphSeed(buffer.readBoolean());
        cir.setReturnValue(entry);
    }
}
