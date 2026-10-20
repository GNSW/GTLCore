package org.gtlcore.gtlcore.mixin.gtm.api.capability;

import org.gtlcore.gtlcore.api.machine.computation.ComputationAmounts;
import org.gtlcore.gtlcore.api.machine.computation.ComputationContentSerializer;
import org.gtlcore.gtlcore.api.machine.computation.ComputationMath;
import org.gtlcore.gtlcore.api.machine.computation.ComputationNetwork;
import org.gtlcore.gtlcore.api.machine.computation.ComputationRecipes;

import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.api.recipe.content.IContentSerializer;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;

import org.apache.commons.lang3.mutable.MutableInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CWURecipeCapability.class)
public abstract class CWURecipeCapabilityMixin {

    @ModifyArg(method = "<init>",
               at = @At(value = "INVOKE",
                        target = "Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;<init>(Ljava/lang/String;IZILcom/gregtechceu/gtceu/api/recipe/content/IContentSerializer;)V"),
               index = 4,
               remap = false)
    private static IContentSerializer<?> gtlcore$longSerializer(IContentSerializer<?> serializer) {
        return ComputationContentSerializer.INSTANCE;
    }

    // These erased bridges are used by Content.copy and RecipeRunner. Retain the old Integer signatures.
    @SuppressWarnings("target") // javac hides synthetic bridge methods from annotation processors.
    @Inject(method = "copyInner(Ljava/lang/Object;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$copyLong(Object content, CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(ComputationAmounts.box(ComputationAmounts.read(content)));
    }

    @SuppressWarnings("target") // The erased bridge exists in the dependency bytecode.
    @Inject(method = "copyWithModifier(Ljava/lang/Object;Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;)Ljava/lang/Object;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void gtlcore$modifyLong(Object content, ContentModifier modifier, CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(ComputationAmounts.box(ComputationAmounts.modify(ComputationAmounts.read(content), modifier)));
    }

    @Inject(method = "copyWithModifier(Ljava/lang/Integer;Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;)Ljava/lang/Integer;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void gtlcore$modifyLegacy(Integer content, ContentModifier modifier, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.toIntExact(ComputationAmounts.modify(ComputationAmounts.read(content), modifier)));
    }

    public int getMaxParallelRatio(IRecipeCapabilityHolder holder, GTRecipe recipe, int maxParallel) {
        long required = ComputationRecipes.required(recipe.tickInputs.getOrDefault(CWURecipeCapability.CAP, List.of()));
        if (required < 0 || maxParallel <= 0) return 0;
        if (required == 0) return maxParallel;
        long available = ComputationNetwork.available(holder, ComputationRecipes.roots(holder));
        return Math.min(maxParallel, ComputationMath.toInt(available / required));
    }

    @Inject(method = "addXEIInfo", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$longRecipeLabels(WidgetGroup group, int xOffset, GTRecipe recipe, List<Content> contents,
                                          boolean perTick, boolean isInput, MutableInt yOffset, CallbackInfo ci) {
        if (perTick) group.addWidget(new LabelWidget(3 - xOffset, yOffset.addAndGet(10),
                LocalizationUtils.format("gtceu.recipe.computation_per_tick", ComputationRecipes.required(contents))));
        if (recipe.data.getBoolean("duration_is_total_cwu")) group.addWidget(new LabelWidget(3 - xOffset, yOffset.addAndGet(10),
                LocalizationUtils.format("gtceu.recipe.total_computation", ComputationAmounts.total(recipe))));
        ci.cancel();
    }
}
