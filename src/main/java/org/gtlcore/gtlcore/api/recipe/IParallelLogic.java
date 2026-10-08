package org.gtlcore.gtlcore.api.recipe;

import org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.gtlcore.gtlcore.api.machine.trait.RecipeHandlePart;
import org.gtlcore.gtlcore.api.recipe.chance.LongChanceLogic;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.utils.FluidStackHashStrategy;
import com.gregtechceu.gtceu.utils.IngredientEquality;
import com.gregtechceu.gtceu.utils.ItemStackHashStrategy;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import com.hepdd.gtmthings.common.block.machine.trait.CatalystFluidStackHandler;
import it.unimi.dsi.fastutil.objects.*;

import java.util.*;
import java.util.function.Predicate;

/**
 * 部分代码参考自gto
 * &#064;line <a href="https://github.com/GregTech-Odyssey/GTOCore">...</a>
 */

public interface IParallelLogic {

    static GTRecipe getRecipeOutputChance(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Content>> recipeContents = new Reference2ObjectOpenHashMap<>();
        for (var entry : recipe.outputs.entrySet()) {
            var cap = entry.getKey();
            List<Content> chancedContents = new ObjectArrayList<>();
            var contentList = recipeContents.computeIfAbsent(cap, c -> new ObjectArrayList<>());
            for (var cont : entry.getValue()) {
                if (cont.chance >= cont.maxChance) contentList.add(cont);
                else chancedContents.add(cont);
            }
            if (!chancedContents.isEmpty()) {
                var function = recipe.getType().getChanceFunction();
                int holderTier = holder.getChanceTier();
                var cache = ((IRecipeLogicMachine) holder).getRecipeLogic().getChanceCaches().get(cap);
                chancedContents = LongChanceLogic.OR.roll(chancedContents, function, ((IGTRecipe) recipe).getEuTier(), holderTier, cache, ((IGTRecipe) recipe).getRealParallels(), cap);
                if (chancedContents != null) {
                    for (var cont : chancedContents) {
                        contentList.add(new Content(cont.content, 10000, 10000, 0, null, null));
                    }
                }
            }
            if (contentList.isEmpty()) recipeContents.remove(cap);
        }
        var copy = new GTRecipe(recipe.recipeType, recipe.id, recipe.inputs, recipeContents, recipe.tickInputs, recipe.tickOutputs,
                recipe.inputChanceLogics, recipe.outputChanceLogics, recipe.tickInputChanceLogics, recipe.tickOutputChanceLogics,
                recipe.conditions, recipe.ingredientActions, recipe.data, recipe.duration, recipe.isFuel);
        RecipeExtensionCopier.copy(recipe, copy);
        ((IGTRecipe) copy).setRealParallels(((IGTRecipe) recipe).getRealParallels());
        copy.ocTier = recipe.ocTier;
        return copy;
    }

    static long getParallel(IRecipeCapabilityHolder holder, GTRecipe recipe, long parallelAmount) {
        if (parallelAmount <= 1) return parallelAmount;
        long maxParallel = getMaxParallel(holder, recipe, parallelAmount);
        if (maxParallel == 0L) return 0L;
        return getMinParallel(holder, recipe, maxParallel);
    }

    static long getMaxParallel(IRecipeCapabilityHolder holder, GTRecipe recipe, long parallelAmount) {
        for (var cap : recipe.inputs.keySet()) {
            if (cap == ItemRecipeCapability.CAP) {
                parallelAmount = Math.min(parallelAmount, getInputItemParallel(holder, recipe, parallelAmount));
                if (parallelAmount == 0L) break;
            } else if (cap == FluidRecipeCapability.CAP) {
                parallelAmount = Math.min(parallelAmount, getInputFluidParallel(holder, recipe, parallelAmount));
                if (parallelAmount == 0L) break;
            }
        }
        return parallelAmount;
    }

    static long getMinParallel(IRecipeCapabilityHolder holder, GTRecipe recipe, long parallelAmount) {
        for (var entry : recipe.outputs.entrySet()) {
            if (entry.getKey() == ItemRecipeCapability.CAP && holder instanceof IRecipeLogicMachine machine && !machine.canVoidRecipeOutputs(ItemRecipeCapability.CAP)) {
                parallelAmount = Math.min(parallelAmount, getOutputItemParallel(holder, recipe, entry.getValue(), parallelAmount));
                if (parallelAmount == 0L) break;
            } else if (entry.getKey() == FluidRecipeCapability.CAP && holder instanceof IRecipeLogicMachine machine && !machine.canVoidRecipeOutputs(FluidRecipeCapability.CAP)) {
                parallelAmount = Math.min(parallelAmount, getOutputFluidParallel(holder, recipe, entry.getValue(), parallelAmount));
                if (parallelAmount == 0L) break;
            }
        }
        return parallelAmount;
    }

    static long getInputItemParallel(IRecipeCapabilityHolder holder, GTRecipe recipe, long parallelAmount) {
        if (parallelAmount <= 1) return parallelAmount;
        parallelAmount = limitItemAmount(recipe.getInputContents(ItemRecipeCapability.CAP), parallelAmount);
        if (parallelAmount <= 1) return parallelAmount;
        if (!(holder instanceof IRecipeCapabilityMachine machine)) return 1;
        if (machine.emptyRecipeHandlePart()) return 0;

        Object2LongOpenCustomHashMap<Ingredient> countableMap = new Object2LongOpenCustomHashMap<>(IngredientEquality.IngredientHashStrategy.INSTANCE);
        boolean confirmMEStock = machine instanceof ParallelMachine para && para.needConfirmMEStock();

        for (Content content : recipe.getInputContents(ItemRecipeCapability.CAP)) {
            if (content.chance <= 0) continue;
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.content);
            long ingredientCount;
            if (ingredient instanceof LongIngredient longIngredient) {
                ingredientCount = longIngredient.getActualAmount();
            } else if (ingredient instanceof SizedIngredient sizedIngredient) {
                ingredientCount = sizedIngredient.getAmount();
            } else ingredientCount = 1;
            countableMap.addTo(ingredient, ingredientCount);
        }

        if (countableMap.isEmpty()) return parallelAmount;

        Object2LongOpenCustomHashMap<ItemStack> ingredientStacks = new Object2LongOpenCustomHashMap<>(ItemStackHashStrategy.comparingAllButCount());

        var handle = machine.getActiveRecipeHandle(recipe);
        if (handle instanceof MEPatternRecipeHandlePart mePatternRecipeHandlePart) {
            for (var entry : Object2LongMaps.fastIterable(mePatternRecipeHandlePart.getMEContent(ItemRecipeCapability.CAP, recipe))) {
                ingredientStacks.mergeLong(entry.getKey(), entry.getLongValue(), NumberUtils::saturatedAdd);
            }

        } else if (handle instanceof RecipeHandlePart recipeHandlePart) {
            for (var entry : Object2LongMaps.fastIterable(recipeHandlePart.getSelfContent(ItemRecipeCapability.CAP, confirmMEStock))) {
                ingredientStacks.mergeLong(entry.getKey(), entry.getLongValue(), NumberUtils::saturatedAdd);
            }
        } else {
            var sharedRecipeHandlePart = machine.getSharedRecipeHandlePart();
            if (sharedRecipeHandlePart != null) {
                for (var entry : Object2LongMaps.fastIterable(sharedRecipeHandlePart.getSelfContent(ItemRecipeCapability.CAP, confirmMEStock))) {
                    ingredientStacks.mergeLong(entry.getKey(), entry.getLongValue(), NumberUtils::saturatedAdd);
                }
            }
        }
        return calculate(parallelAmount, countableMap, ingredientStacks);
    }

    static long getInputFluidParallel(IRecipeCapabilityHolder holder, GTRecipe recipe, long parallelAmount) {
        if (parallelAmount <= 1) return parallelAmount;
        parallelAmount = limitFluidAmount(recipe.getInputContents(FluidRecipeCapability.CAP), parallelAmount);
        if (parallelAmount <= 1) return parallelAmount;
        if (!(holder instanceof IRecipeCapabilityMachine machine)) return 1;
        if (machine.emptyRecipeHandlePart()) return 0;

        Object2LongOpenHashMap<FluidIngredient> fluidCountMap = new Object2LongOpenHashMap<>();
        boolean confirmMEStock = machine instanceof ParallelMachine para && para.needConfirmMEStock();

        for (Content content : recipe.getInputContents(FluidRecipeCapability.CAP)) {
            FluidIngredient fluidInput = FluidRecipeCapability.CAP.of(content.content);
            if (content.chance > 0) {
                fluidCountMap.addTo(fluidInput, fluidInput.getAmount());
            }
        }

        if (fluidCountMap.isEmpty()) return parallelAmount;

        Object2LongOpenCustomHashMap<FluidStack> ingredientStacks = new Object2LongOpenCustomHashMap<>(FluidStackHashStrategy.comparingAllButAmount());

        var handle = machine.getActiveRecipeHandle(recipe);
        if (handle instanceof MEPatternRecipeHandlePart mePatternRecipeHandlePart) {
            for (var entry : Object2LongMaps.fastIterable(mePatternRecipeHandlePart.getMEContent(FluidRecipeCapability.CAP, recipe))) {
                ingredientStacks.mergeLong(entry.getKey(), entry.getLongValue(), NumberUtils::saturatedAdd);
            }
        } else if (handle instanceof RecipeHandlePart recipeHandlePart) {
            for (var entry : Object2LongMaps.fastIterable(machine.isDistinct() ? recipeHandlePart.getSelfContent(FluidRecipeCapability.CAP, confirmMEStock) : recipeHandlePart.getContentWithShared(FluidRecipeCapability.CAP, confirmMEStock))) {
                ingredientStacks.mergeLong(entry.getKey(), entry.getLongValue(), NumberUtils::saturatedAdd);
            }
        } else {
            var sharedRecipeHandlePart = machine.getSharedRecipeHandlePart();
            if (sharedRecipeHandlePart != null) {
                for (var handler : sharedRecipeHandlePart.getCapability(FluidRecipeCapability.CAP)) {
                    if (handler instanceof CatalystFluidStackHandler) continue;
                    for (var object : handler.getContents()) {
                        if (object instanceof FluidStack fs) {
                            ingredientStacks.mergeLong(fs, fs.getAmount(), NumberUtils::saturatedAdd);
                        }
                    }
                }

            }
        }
        return calculate(parallelAmount, fluidCountMap, ingredientStacks);
    }

    private static <I extends Predicate<S>, S> long calculate(long parallelLimit,
                                                              Object2LongMap<I> countableMap,
                                                              Object2LongMap<S> ingredientStacks) {
        if (ingredientStacks.isEmpty()) return 0;

        for (var entry : Object2LongMaps.fastIterable(countableMap)) {
            I ingredient = entry.getKey();
            long needed = entry.getLongValue();
            long available = 0;

            for (var it = Object2LongMaps.fastIterator(ingredientStacks); it.hasNext();) {
                var input = it.next();
                if (ingredient.test(input.getKey())) {
                    available = input.getLongValue();
                    it.remove();
                    break;
                }
            }

            if (available < needed) return 0;

            parallelLimit = Math.min(parallelLimit, available / needed);
        }
        return parallelLimit;
    }

    static long getOutputItemParallel(IRecipeCapabilityHolder holder, GTRecipe recipe, List<Content> contents, long multiplier) {
        if (multiplier <= 1L || contents.isEmpty()) return multiplier;
        multiplier = limitItemAmount(contents, multiplier);
        if (multiplier <= 1L) return multiplier;
        if (!(holder instanceof IRecipeCapabilityMachine machine)) return 1;
        if (machine.itemOutPutAlwaysMatch()) return multiplier;

        List<Object> inners = new ObjectArrayList<>(contents.size());
        for (var content : contents) {
            inners.add(content.content);
        }

        for (var meIOHandler : machine.getMEOutputRecipeHandleParts()) {
            inners = meIOHandler.meHandleOutput(ItemRecipeCapability.CAP, inners, true);
            if (inners.isEmpty()) return multiplier;
        }

        List<Ingredient> ingredients = new ObjectArrayList<>(inners.size());
        for (var inner : inners) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(inner);
            if (ingredient instanceof LongIngredient longIngredient) {
                if (longIngredient.getActualAmount() > 0) ingredients.add(ingredient);
            } else if (ingredient instanceof SizedIngredient sizedIngredient) {
                if (sizedIngredient.getAmount() > 0) ingredients.add(ingredient);
            } else ingredients.add(ingredient);
        }

        if (ingredients.isEmpty()) return multiplier;

        return binarySearchMaxParallel(machine, recipe, ItemRecipeCapability.CAP, ingredients, multiplier);
    }

    static long getOutputFluidParallel(IRecipeCapabilityHolder holder, GTRecipe recipe, List<Content> contents, long multiplier) {
        if (multiplier <= 1L || contents.isEmpty()) return multiplier;
        multiplier = limitFluidAmount(contents, multiplier);
        if (multiplier <= 1L) return multiplier;
        if (!(holder instanceof IRecipeCapabilityMachine machine)) return 1;
        if (machine.fluidOutPutAlwaysMatch()) return multiplier;

        List<Object> inners = new ObjectArrayList<>(contents.size());
        for (var content : contents) {
            inners.add(content.content);
        }

        for (var meIOHandler : machine.getMEOutputRecipeHandleParts()) {
            inners = meIOHandler.meHandleOutput(FluidRecipeCapability.CAP, inners, true);
            if (inners.isEmpty()) return multiplier;
        }

        List<FluidIngredient> ingredients = new ObjectArrayList<>(inners.size());
        for (var inner : inners) {
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(inner);
            if (ingredient.getAmount() > 0) ingredients.add(ingredient);
        }

        if (ingredients.isEmpty()) return multiplier;

        return binarySearchMaxParallel(machine, recipe, FluidRecipeCapability.CAP, ingredients, multiplier);
    }

    private static long limitFluidAmount(List<Content> contents, long parallelAmount) {
        for (Content content : contents) {
            long amount = FluidRecipeCapability.CAP.of(content.content).getAmount();
            if (amount <= 0) return 0;
            parallelAmount = Math.min(parallelAmount, Long.MAX_VALUE / amount);
        }
        return parallelAmount;
    }

    private static long limitItemAmount(List<Content> contents, long parallelAmount) {
        for (Content content : contents) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.content);
            long amount = ingredient instanceof LongIngredient longIngredient ?
                    longIngredient.getActualAmount() :
                    ingredient instanceof SizedIngredient sizedIngredient ? sizedIngredient.getAmount() : 1;
            if (amount <= 0) return 0;
            parallelAmount = Math.min(parallelAmount, Long.MAX_VALUE / amount);
        }
        return parallelAmount;
    }

    @SuppressWarnings("unchecked")
    private static <T> long binarySearchMaxParallel(IRecipeCapabilityMachine machine, GTRecipe recipe,
                                                    RecipeCapability<T> capability, List<T> ingredients, long initialMultiplier) {
        var handlers = machine.getNormalRecipeHandlePart(IO.OUT);
        if (handlers.isEmpty()) return 0L;

        long left = 1L, right = initialMultiplier;

        while (left < right) {
            long mid = left + (right - left + 1) / 2;

            List<T> copied = new ObjectArrayList<>(ingredients.size());
            var modifier = IAdvancedContentModifier.preciseMultiplier(mid);
            for (var ing : ingredients) {
                copied.add(capability.copyWithModifier(ing, modifier));
            }

            boolean canHandle = false;
            for (var handler : handlers) {
                copied = (List<T>) handler.handleRecipe(IO.OUT, recipe, capability, copied, true);
                if (copied == null || copied.isEmpty()) {
                    canHandle = true;
                    break;
                }
            }

            if (canHandle) {
                left = mid;
            } else {
                right = mid - 1;
            }
        }

        return left;
    }
}
