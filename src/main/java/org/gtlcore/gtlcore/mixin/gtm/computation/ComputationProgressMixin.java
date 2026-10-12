package org.gtlcore.gtlcore.mixin.gtm.computation;

import org.gtlcore.gtlcore.api.machine.computation.ComputationAmounts;
import org.gtlcore.gtlcore.api.machine.computation.ComputationMath;
import org.gtlcore.gtlcore.api.machine.computation.ComputationNetwork;
import org.gtlcore.gtlcore.api.machine.computation.ComputationProgress;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeLogic.class)
public abstract class ComputationProgressMixin implements ComputationProgress {

    @Unique
    private long gtlcore$completed = -1;
    @Unique
    private long gtlcore$target;
    @Unique
    private String gtlcore$researchId = "";
    @Unique
    private long gtlcore$lastDraw;
    @Unique
    private long gtlcore$lastDrawTick = Long.MIN_VALUE;

    @Override
    public void gtlcore$recordComputation(long amount) {
        long tick = ComputationNetwork.tick(((RecipeLogic) (Object) this).getMachine().getLevel());
        gtlcore$lastDraw = gtlcore$lastDrawTick == tick ? ComputationMath.add(gtlcore$lastDraw, amount) : amount;
        gtlcore$lastDrawTick = tick;
    }

    @Override
    public long gtlcore$usedComputation() {
        long tick = ComputationNetwork.tick(((RecipeLogic) (Object) this).getMachine().getLevel());
        return tick == gtlcore$lastDrawTick || tick - 1 == gtlcore$lastDrawTick ? gtlcore$lastDraw : 0;
    }

    @Override
    public long gtlcore$usedComputationThisTick() {
        long tick = ComputationNetwork.tick(((RecipeLogic) (Object) this).getMachine().getLevel());
        return tick == gtlcore$lastDrawTick ? gtlcore$lastDraw : 0;
    }

    @Override
    public long gtlcore$completedComputation(GTRecipe recipe) {
        long total = ComputationAmounts.total(recipe);
        String id = String.valueOf(recipe.id);
        if (gtlcore$completed < 0 || total != gtlcore$target || !id.equals(gtlcore$researchId)) {
            var logic = (RecipeLogic) (Object) this;
            gtlcore$completed = Math.min(total, ComputationMath.multiplyDivide(Math.max(0, logic.getProgress()),
                    total, Math.max(1, logic.getDuration())));
            gtlcore$target = total;
            gtlcore$researchId = id;
        }
        return gtlcore$completed;
    }

    @Override
    public void gtlcore$advanceComputation(GTRecipe recipe, long amount) {
        long previous = gtlcore$completedComputation(recipe);
        if (amount < 0 || amount > gtlcore$target - previous)
            throw new IllegalArgumentException("Research computation exceeds remaining work");
        gtlcore$completed += amount;
        var logic = (RecipeLogic) (Object) this;
        int duration = Math.max(1, logic.getDuration());
        int projected = gtlcore$completed == gtlcore$target ? duration :
                Math.min(duration - 1, ComputationMath.toInt(ComputationMath.multiplyDivide(gtlcore$completed, duration, gtlcore$target)));
        logic.setProgress(projected);
    }

    @Inject(method = "setupRecipe", at = @At("RETURN"), remap = false)
    private void gtlcore$newResearch(GTRecipe recipe, CallbackInfo ci) {
        gtlcore$resetComputation(recipe);
    }

    @Override
    public void gtlcore$resetComputation(GTRecipe recipe) {
        var logic = (RecipeLogic) (Object) this;
        if (logic.getLastRecipe() == recipe && logic.getProgress() == 0) {
            gtlcore$completed = -1;
            if (recipe.data.getBoolean("duration_is_total_cwu")) gtlcore$completedComputation(recipe);
        }
    }

    @Inject(method = { "resetRecipeLogic", "interruptRecipe" }, at = @At("RETURN"), remap = false)
    private void gtlcore$clearResearch(CallbackInfo ci) {
        gtlcore$lastDraw = 0;
        gtlcore$completed = -1;
        gtlcore$target = 0;
        gtlcore$researchId = "";
    }

    @Inject(method = "doDamping", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$keepPaidResearch(CallbackInfo ci) {
        var recipe = ((RecipeLogic) (Object) this).getLastRecipe();
        if (recipe != null && recipe.data.getBoolean("duration_is_total_cwu")) ci.cancel();
    }

    @Inject(method = "getProgressPercent", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$researchPercent(CallbackInfoReturnable<Double> cir) {
        var recipe = ((RecipeLogic) (Object) this).getLastRecipe();
        if (recipe != null && recipe.data.getBoolean("duration_is_total_cwu"))
            cir.setReturnValue((double) gtlcore$completedComputation(recipe) / ComputationAmounts.total(recipe));
    }

    @Inject(method = "saveCustomPersistedData", at = @At("TAIL"), remap = false)
    private void gtlcore$saveResearch(CompoundTag tag, boolean forDrop, CallbackInfo ci) {
        var recipe = ((RecipeLogic) (Object) this).getLastRecipe();
        if (recipe == null || !recipe.data.getBoolean("duration_is_total_cwu")) return;
        CompoundTag research = new CompoundTag();
        research.putLong("completed", gtlcore$completedComputation(recipe));
        research.putLong("total", gtlcore$target);
        research.putString("recipe", gtlcore$researchId);
        tag.put("gtlcore_research", research);
    }

    @Inject(method = "loadCustomPersistedData", at = @At("TAIL"), remap = false)
    private void gtlcore$loadResearch(CompoundTag tag, CallbackInfo ci) {
        gtlcore$completed = -1;
        if (!tag.contains("gtlcore_research")) return;
        var research = tag.getCompound("gtlcore_research");
        long completed = research.getLong("completed");
        long total = research.getLong("total");
        if (completed < 0 || total <= 0 || completed > total) return;
        gtlcore$completed = completed;
        gtlcore$target = total;
        gtlcore$researchId = research.getString("recipe");
    }
}
