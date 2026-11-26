package org.gtlcore.gtlcore.api.recipe;

import org.gtlcore.gtlcore.api.machine.trait.*;
import org.gtlcore.gtlcore.api.recipe.chance.LongChanceLogic;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.chance.boost.ChanceBoostFunction;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;

import net.minecraft.network.chat.Component;

import it.unimi.dsi.fastutil.objects.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 部分代码参考自gto
 * &#064;line <a href="https://github.com/GregTech-Odyssey/GTOCore">...</a>
 */

public final class RecipeRunner {

    private static final Comparator<RecipeHandlePart> OUTPUT_HANDLER_COMPARATOR = RecipeHandlePart.COMPARATOR.reversed();

    private RecipeRunner() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    public static RecipeResult handle(GTRecipe recipe, IO io, IRecipeCapabilityHolder holder,
                                      Map<RecipeCapability<?>, List<Content>> contents,
                                      Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches,
                                      boolean simulated, RecipeCacheStrategy strategy) {
        var recipeContent = fillContent(contents, recipe, holder, chanceCaches, simulated);

        if (recipeContent.isEmpty()) {
            return RecipeResult.SUCCESS;
        }

        return handleContentsInternal(io, recipe, holder, recipeContent, simulated, strategy) ? RecipeResult.SUCCESS : io == IO.IN ? RecipeResult.FAIL_INPUT : simulated ? generateOutputFailReason(recipeContent) : RecipeResult.fail(null);
    }

    private static Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> fillContent(
                                                                                              Map<RecipeCapability<?>, List<Content>> entries,
                                                                                              GTRecipe recipe,
                                                                                              IRecipeCapabilityHolder holder,
                                                                                              Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches,
                                                                                              boolean simulated) {
        var recipeContent = new Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>>();

        for (var entry : entries.entrySet()) {
            RecipeCapability<?> cap = entry.getKey();
            if (!cap.doMatchInRecipe()) continue;

            List<Content> contents = entry.getValue();
            if (contents.isEmpty()) continue;

            List<Content> chancedContents = null;
            List<Object> contentList = null;
            for (Content cont : contents) {
                if (simulated) {
                    if (contentList == null) contentList = new ObjectArrayList<>();
                    contentList.add(cont.content);
                } else {
                    if (cont.chance >= cont.maxChance) {
                        if (contentList == null) contentList = new ObjectArrayList<>();
                        contentList.add(cont.content);
                    } else if (cont.chance != 0) {
                        if (chancedContents == null) chancedContents = new ObjectArrayList<>();
                        chancedContents.add(cont);
                    }
                }
            }
            if (chancedContents != null && !chancedContents.isEmpty()) {
                ChanceBoostFunction function = recipe.getType().getChanceFunction();
                int holderTier = holder.getChanceTier();
                var cache = chanceCaches.get(cap);
                chancedContents = LongChanceLogic.OR.roll(chancedContents, function, ((IGTRecipe) recipe).getEuTier(), holderTier, cache, ((IGTRecipe) recipe).getRealParallels(), cap);
                if (chancedContents != null) {
                    if (contentList == null) contentList = new ObjectArrayList<>();
                    for (Content cont : chancedContents) {
                        contentList.add(cont.content);
                    }
                }
            }
            if (contentList != null && !contentList.isEmpty()) recipeContent.put(cap, contentList);
        }

        return recipeContent;
    }

    private static boolean handleContentsInternal(IO capIO, GTRecipe recipe, IRecipeCapabilityHolder holder,
                                                  Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                                  boolean simulated, RecipeCacheStrategy strategy) {
        if (!(holder instanceof IRecipeCapabilityMachine machine)) {
            return false;
        }

        if (machine.emptyHandlePart()) {
            return false;
        }

        if (capIO == IO.IN) {
            RecipeExtractionScope scope = simulated ? null : RecipeExtractionScope.beginShared();
            try {
                boolean ok;
                if (strategy == RecipeCacheStrategy.NO_CACHE) {
                    ok = machine.isDistinct() ?
                            handleInputDistinctNocache(machine, recipe, recipeContent, simulated) :
                            handleInputNotDistinctNocache(machine, recipe, recipeContent, simulated);
                } else {
                    ok = machine.isDistinct() ?
                            handleInputDistinct(machine, recipe, recipeContent, simulated, strategy) :
                            handleInputNotDistinct(machine, recipe, recipeContent, simulated, strategy);
                }
                if (scope != null && ok) {
                    scope.commit();
                }
                return ok;
            } finally {
                if (scope != null) {
                    scope.close();
                }
            }
        } else {
            recipeContent = handleMEOutput(machine.getMEOutputRecipeHandleParts(), recipeContent, simulated);
            if (recipeContent.isEmpty()) return true;
            recipeContent = handleNormalOutput(machine.getNormalRecipeHandlePart(IO.OUT), recipe, recipeContent, simulated);
            return recipeContent.isEmpty();
        }
    }

    // ========================================
    // Input
    // ========================================

    @SuppressWarnings("DuplicatedCode")
    private static boolean handleInputDistinct(IRecipeCapabilityMachine machine, GTRecipe recipe,
                                               Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                               boolean simulated, RecipeCacheStrategy strategy) {
        // Priority 1: Try all cached handlers (active is first in iterator)
        for (var it = machine.getAllCachedRecipeHandlesIter(recipe); it.hasNext();) {
            var handler = it.next();

            if (handler instanceof MEPatternRecipeHandlePart cachedMEPart) {
                var slot = cachedMEPart.handleRecipe(recipe, recipeContent, simulated, strategy.cacheToMEInternal);
                if (slot != -1) {
                    if (simulated && strategy.cacheToHandlePartMap) {
                        if (slot == -2) machine.tryAddAndActiveRhp(recipe, cachedMEPart);
                        else machine.tryAddAndActiveMERhp(cachedMEPart, recipe, slot);
                    }
                    return true;
                }
            } else if (handler instanceof RecipeHandlePart cachedNormalPart) {
                var result = cachedNormalPart.handleRecipe(IO.IN, recipe, recipeContent, simulated);
                if (result.isEmpty()) {
                    if (simulated && strategy.cacheToHandlePartMap) {
                        machine.tryAddAndActiveRhp(recipe, cachedNormalPart);
                    }
                    return true;
                }
            }
        }

        var cachedHandlers = machine.getAllCachedRecipeHandles(recipe);

        // Priority 2: Try uncached ME Pattern parts
        for (var part : machine.getMEPatternRecipeHandleParts()) {
            if (cachedHandlers.contains(part)) continue;
            var slot = part.handleRecipe(recipe, recipeContent, simulated, strategy.cacheToMEInternal);
            if (slot >= 0) {
                if (simulated && strategy.cacheToHandlePartMap) {
                    machine.tryAddAndActiveMERhp(part, recipe, slot);
                }
                return true;
            }
        }

        // Priority 3: Try uncached normal parts
        for (var part : machine.getNormalRecipeHandlePart(IO.IN)) {
            if (cachedHandlers.contains(part)) continue;
            var result = part.handleRecipe(IO.IN, recipe, recipeContent, simulated);
            if (result.isEmpty()) {
                if (simulated && strategy.cacheToHandlePartMap) {
                    machine.tryAddAndActiveRhp(recipe, part);
                }
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("DuplicatedCode")
    private static boolean handleInputNotDistinct(IRecipeCapabilityMachine machine, GTRecipe recipe,
                                                  Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                                  boolean simulated, RecipeCacheStrategy strategy) {
        boolean fluidHandleResult = false;
        boolean hasFluidTry = false;

        // Priority 1: Try all cached handlers (active is first in iterator)
        for (var it = machine.getAllCachedRecipeHandlesIter(recipe); it.hasNext();) {
            var handler = it.next();

            if (handler instanceof MEPatternRecipeHandlePart cachedMEPart) {
                var slot = cachedMEPart.handleRecipe(recipe, recipeContent, simulated, strategy.cacheToMEInternal);
                if (slot != -1) {
                    if (simulated && strategy.cacheToHandlePartMap) {
                        if (slot == -2) machine.tryAddAndActiveRhp(recipe, cachedMEPart);
                        else machine.tryAddAndActiveMERhp(cachedMEPart, recipe, slot);
                    }
                    return true;
                }
            } else if (handler instanceof RecipeHandlePart cachedNormalPart) {
                if (!hasFluidTry) {
                    fluidHandleResult = tryNotDistinctFluid(machine.getSharedRecipeHandlePart(), recipe, recipeContent, simulated);
                    hasFluidTry = true;
                }
                if (fluidHandleResult && tryNotDistinctItem(cachedNormalPart, recipe, recipeContent, simulated)) {
                    if (simulated && strategy.cacheToHandlePartMap) {
                        machine.tryAddAndActiveRhp(recipe, cachedNormalPart);
                    }
                    return true;
                }
            }
        }

        var cachedHandlers = machine.getAllCachedRecipeHandles(recipe);

        // Priority 2: Try uncached ME Pattern parts
        for (var part : machine.getMEPatternRecipeHandleParts()) {
            if (cachedHandlers.contains(part)) continue;
            var slot = part.handleRecipe(recipe, recipeContent, simulated, strategy.cacheToMEInternal);
            if (slot >= 0) {
                if (simulated && strategy.cacheToHandlePartMap) {
                    machine.tryAddAndActiveMERhp(part, recipe, slot);
                }
                return true;
            }
        }

        // Priority 3: Try uncached normal parts
        RecipeHandlePart sharedPart = machine.getSharedRecipeHandlePart();

        List<?> fluidContent = recipeContent.getOrDefault(FluidRecipeCapability.CAP, Collections.emptyList());
        if (!fluidContent.isEmpty()) {
            if (sharedPart == null) return false;
            fluidContent = sharedPart.handleRecipe(IO.IN, recipe, FluidRecipeCapability.CAP, fluidContent, simulated);
            if (fluidContent != null && !fluidContent.isEmpty()) return false;
        }

        List<?> itemContent = recipeContent.getOrDefault(ItemRecipeCapability.CAP, Collections.emptyList());
        if (itemContent.isEmpty()) {
            if (simulated && strategy.cacheToHandlePartMap) {
                machine.tryAddAndActiveRhp(recipe, sharedPart);
            }
            return true;
        }

        for (var part : machine.getNormalRecipeHandlePart(IO.IN)) {
            if (cachedHandlers.contains(part)) continue;
            var result = part.handleRecipe(IO.IN, recipe, ItemRecipeCapability.CAP, itemContent, simulated);
            if (result == null || result.isEmpty()) {
                if (simulated && strategy.cacheToHandlePartMap) {
                    machine.tryAddAndActiveRhp(recipe, part);
                }
                return true;
            }
        }

        if (sharedPart != null && !cachedHandlers.contains(sharedPart)) {
            var result = sharedPart.handleRecipe(IO.IN, recipe, ItemRecipeCapability.CAP, itemContent, simulated);
            if (result == null || result.isEmpty()) {
                if (simulated && strategy.cacheToHandlePartMap) {
                    machine.tryAddAndActiveRhp(recipe, sharedPart);
                }
                return true;
            }
        }

        return false;
    }

    private static boolean tryNotDistinctFluid(@Nullable RecipeHandlePart sharedPart, GTRecipe recipe,
                                               Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                               boolean simulated) {
        List<?> fluidContent = recipeContent.getOrDefault(FluidRecipeCapability.CAP, Collections.emptyList());
        if (fluidContent.isEmpty()) return true;
        if (sharedPart == null) return false;

        fluidContent = sharedPart.handleRecipe(IO.IN, recipe, FluidRecipeCapability.CAP, fluidContent, simulated);
        return fluidContent == null || fluidContent.isEmpty();
    }

    private static boolean tryNotDistinctItem(RecipeHandlePart cachedPart, GTRecipe recipe,
                                              Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                              boolean simulated) {
        List<?> itemContent = recipeContent.getOrDefault(ItemRecipeCapability.CAP, Collections.emptyList());
        if (itemContent.isEmpty()) return true;

        var result = cachedPart.handleRecipe(IO.IN, recipe, ItemRecipeCapability.CAP, itemContent, simulated);
        return result == null || result.isEmpty();
    }

    // ========================================
    // Output
    // ========================================

    private static Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> handleNormalOutput(
                                                                                                     List<RecipeHandlePart> handlers, GTRecipe recipe,
                                                                                                     Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                                                                                     boolean simulated) {
        if (handlers.isEmpty()) return recipeContent;

        // Sort only if not already sorted (assuming handlers list is stable)
        // Consider caching sorted handlers in machine if this becomes a bottleneck
        if (handlers.size() > 1 && !isSortedByPriority(handlers)) {
            handlers.sort(OUTPUT_HANDLER_COMPARATOR);
        }

        for (var handler : handlers) {
            recipeContent = handler.handleRecipe(IO.OUT, recipe, recipeContent, simulated);
            if (recipeContent.isEmpty()) break;
        }

        return recipeContent;
    }

    private static boolean isSortedByPriority(List<RecipeHandlePart> handlers) {
        RecipeHandlePart previous = handlers.get(0);
        for (int i = 1; i < handlers.size(); i++) {
            RecipeHandlePart current = handlers.get(i);
            if (OUTPUT_HANDLER_COMPARATOR.compare(previous, current) > 0) return false;
            previous = current;
        }
        return true;
    }

    private static Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> handleMEOutput(
                                                                                                 List<MEIORecipeHandlePart<?>> meHandlers,
                                                                                                 Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                                                                                 boolean simulated) {
        for (MEIORecipeHandlePart<?> meHandler : meHandlers) {
            recipeContent = meHandler.meHandleOutput(recipeContent, simulated);
            if (recipeContent.isEmpty()) break;
        }
        return recipeContent;
    }

    private static RecipeResult generateOutputFailReason(Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent) {
        var builder = new StringBuilder();
        for (var entry : Reference2ObjectMaps.fastIterable(recipeContent)) {
            var cap = entry.getKey();
            for (var ing : entry.getValue()) {
                if (cap == ItemRecipeCapability.CAP) {
                    if (ing instanceof LongIngredient li && li.getItems().length >= 1) {
                        builder.append(li.getItems()[0].getDisplayName().getString()).append("x ").append(li.getActualAmount()).append(" ");
                    } else if (ing instanceof SizedIngredient si && si.getItems().length >= 1) {
                        builder.append(si.getItems()[0].getDisplayName().getString()).append("x ").append(si.getAmount()).append(" ");
                    }
                } else if (cap == FluidRecipeCapability.CAP) {
                    if (ing instanceof FluidIngredient fi && fi.getStacks().length >= 1) {
                        builder.append(fi.getStacks()[0].getDisplayName().getString()).append("x ").append(fi.getAmount()).append(" ");
                    }
                }
            }
        }
        return RecipeResult.fail(Component.translatable("gtceu.recipe.fail.Output.Content", builder));
    }

    // ========================================
    // No-Cache versions (for DUMMY_RECIPES)
    // Only handles input, output doesn't need nocache
    // ========================================

    private static boolean handleInputDistinctNocache(IRecipeCapabilityMachine machine, GTRecipe recipe,
                                                      Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                                      boolean simulated) {
        for (var part : machine.getNormalRecipeHandlePart(IO.IN)) {
            var result = part.handleRecipe(IO.IN, recipe, recipeContent, simulated);
            if (result.isEmpty()) {
                return true;
            }
        }

        for (var part : machine.getMEPatternRecipeHandleParts()) {
            var slot = part.handleRecipe(recipe, recipeContent, simulated, false);
            if (slot >= 0) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("DuplicatedCode")
    private static boolean handleInputNotDistinctNocache(IRecipeCapabilityMachine machine, GTRecipe recipe,
                                                         Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> recipeContent,
                                                         boolean simulated) {
        RecipeHandlePart sharedPart = machine.getSharedRecipeHandlePart();
        boolean fluidHandled = true;

        List<?> fluidContent = recipeContent.getOrDefault(FluidRecipeCapability.CAP, Collections.emptyList());
        if (!fluidContent.isEmpty()) {
            if (sharedPart == null) fluidHandled = false;
            else {
                fluidContent = sharedPart.handleRecipe(IO.IN, recipe, FluidRecipeCapability.CAP, fluidContent, simulated);
                if (fluidContent != null && !fluidContent.isEmpty()) fluidHandled = false;
            }
        }

        if (fluidHandled) {
            List<?> itemContent = recipeContent.getOrDefault(ItemRecipeCapability.CAP, Collections.emptyList());
            if (itemContent.isEmpty()) {
                return true;
            }

            for (var part : machine.getNormalRecipeHandlePart(IO.IN)) {
                var result = part.handleRecipe(IO.IN, recipe, ItemRecipeCapability.CAP, itemContent, simulated);
                if (result == null || result.isEmpty()) {
                    return true;
                }
            }

            if (sharedPart != null) {
                var result = sharedPart.handleRecipe(IO.IN, recipe, ItemRecipeCapability.CAP, itemContent, simulated);
                if (result == null || result.isEmpty()) {
                    return true;
                }
            }
        }

        for (var part : machine.getMEPatternRecipeHandleParts()) {
            var slot = part.handleRecipe(recipe, recipeContent, simulated, false);
            if (slot >= 0) {
                return true;
            }
        }

        return false;
    }
}
