package org.gtlcore.gtlcore.api.recipe;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.machine.WorkableTieredMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.steam.SteamWorkableMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.common.machine.multiblock.electric.research.ResearchStationMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.primitive.PrimitiveWorkableMachine;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.*;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class RecipeRunnerHelper {

    public static boolean matchRecipe(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        return matchRecipeInput(holder, recipe) && matchRecipeOutput(holder, recipe);
    }

    public static boolean matchRecipeInput(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        if (recipe.inputs.isEmpty()) return true;
        return handleRecipe(IO.IN, holder, recipe.inputs, Collections.emptyMap(), false, recipe, true).isSuccess();
    }

    /**
     * 只对真实处理会抽取的输入做模拟：{@code chance == 0} 的内容（催化剂、模具、电路等）真实处理阶段
     * 不会扣取，但批处理会把它们和普通输入一起放大；若一并模拟，放大后的数量会被误判为不足。
     */
    public static boolean matchConsumedRecipeInput(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        return matchRecipeInput(holder, consumableInputs(recipe));
    }

    private static GTRecipe consumableInputs(GTRecipe recipe) {
        boolean needsFilter = false;
        for (var contents : recipe.inputs.values()) {
            for (var content : contents) {
                if (content.chance == 0) {
                    needsFilter = true;
                    break;
                }
            }
            if (needsFilter) break;
        }
        if (!needsFilter) return recipe;

        GTRecipe filtered = recipe.copy();
        filtered.inputs.clear();
        for (var entry : recipe.inputs.entrySet()) {
            List<Content> kept = new ObjectArrayList<>(entry.getValue().size());
            for (var content : entry.getValue()) {
                if (content.chance != 0) kept.add(content);
            }
            if (!kept.isEmpty()) filtered.inputs.put(entry.getKey(), kept);
        }
        return filtered;
    }

    public static boolean matchRecipeOutput(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        if (recipe.outputs.isEmpty()) return true;
        if (holder instanceof IRecipeCapabilityMachine machine && machine.isRecipeOutputAlwaysMatch(recipe)) return true;
        return handleRecipe(IO.OUT, holder, recipe.outputs, Collections.emptyMap(), false, recipe, true).isSuccess();
    }

    public static boolean handleRecipeInput(IRecipeLogicMachine holder, GTRecipe recipe) {
        return handleRecipe(IO.IN, holder, recipe.inputs, holder.getRecipeLogic().getChanceCaches(), true, recipe, false).isSuccess();
    }

    public static boolean handleRecipeOutput(IRecipeLogicMachine holder, GTRecipe recipe) {
        return handleRecipe(IO.OUT, holder, recipe.outputs, holder.getRecipeLogic().getChanceCaches(), true, recipe, false).isSuccess();
    }

    public static RecipeResult handleRecipe(IO io, IRecipeCapabilityHolder holder, Map<RecipeCapability<?>, List<Content>> contents,
                                            Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches, boolean isTick, GTRecipe recipe, boolean isSimulate) {
        return handleRecipe(io, holder, contents, chanceCaches, isTick, recipe, isSimulate, RecipeCacheStrategy.FULL_CACHE);
    }

    public static RecipeResult handleRecipe(IO io, IRecipeCapabilityHolder holder, Map<RecipeCapability<?>, List<Content>> contents,
                                            Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches, boolean isTick, GTRecipe recipe, boolean isSimulate, RecipeCacheStrategy cacheStrategy) {
        if (holder instanceof PrimitiveWorkableMachine || holder instanceof SteamWorkableMachine ||
                holder instanceof WorkableTieredMachine || holder instanceof ResearchStationMachine) {
            if (isSimulate) {
                var result = recipe.matchRecipeContents(io, holder, io == IO.IN ? recipe.inputs : recipe.outputs, isTick);
                if (result.isSuccess()) {
                    RecipeResult.of((IRecipeLogicMachine) holder, RecipeResult.SUCCESS);
                    return RecipeResult.SUCCESS;
                } else RecipeResult.of((IRecipeLogicMachine) holder, io == IO.IN ? RecipeResult.FAIL_INPUT : RecipeResult.FAIL_OUTPUT);
            } else if (recipe.handleRecipe(io, holder, isTick, contents, chanceCaches)) return RecipeResult.SUCCESS;
        } else {
            var result = RecipeRunner.handle(recipe, io, holder, contents, chanceCaches, isSimulate, cacheStrategy);
            RecipeResult.of((IRecipeLogicMachine) holder, result);
            return result;
        }
        return RecipeResult.fail(null);
    }

    // ============================================
    // Custom-Cache versions (for DUMMY_RECIPES)
    // ============================================

    public static boolean matchRecipeInputNoMEInnerCache(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        if (recipe.inputs.isEmpty()) return true;
        return handleRecipe(IO.IN, holder, recipe.inputs, Collections.emptyMap(), false, recipe, true, RecipeCacheStrategy.HANDLE_PART_CACHE_ONLY).isSuccess();
    }

    public static boolean matchRecipeInputNocache(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        if (recipe.inputs.isEmpty()) return true;
        return handleRecipe(IO.IN, holder, recipe.inputs, Collections.emptyMap(), false, recipe, true, RecipeCacheStrategy.NO_CACHE).isSuccess();
    }

    public static boolean handleRecipeInputNocache(IRecipeLogicMachine holder, GTRecipe recipe) {
        return handleRecipe(IO.IN, holder, recipe.inputs, holder.getRecipeLogic().getChanceCaches(), true, recipe, false, RecipeCacheStrategy.NO_CACHE).isSuccess();
    }
}
