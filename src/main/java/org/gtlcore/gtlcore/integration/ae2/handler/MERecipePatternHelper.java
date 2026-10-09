package org.gtlcore.gtlcore.integration.ae2.handler;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.integration.ae2.pattern.VirtualIngredientEncoding;
import org.gtlcore.gtlcore.utils.Registries;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Converts the recipes of the active machine mode into ordinary, persistent AE2 processing patterns. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class MERecipePatternHelper {

    private static long reloadGeneration;

    private MERecipePatternHelper() {}

    public record RecipePattern(GTRecipe recipe, IPatternDetails pattern, int circuit, @Nullable AEKey primaryOutput,
                                List<List<AEKey>> nonConsumedInputs, boolean chancedInput, List<InputSlot> inputSlots) {

        public String id() {
            return recipe.recipeType.registryName + "/" + recipe.id;
        }
    }

    /** Original recipe positions stay distinct even when AE2 merges identical materials. */
    public record InputSlot(String id, long amount, List<AEKey> alternatives, AEKey defaultKey) {}

    public static long getReloadGeneration() {
        return reloadGeneration;
    }

    @SubscribeEvent
    public static void onReload(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) reloadGeneration++;
    }

    public static Set<GTRecipeType> getActiveRecipeTypes(List<IMultiController> controllers) {
        Set<GTRecipeType> types = new LinkedHashSet<>();
        for (IMultiController controller : controllers) {
            if (controller.isFormed() && controller.self() instanceof IRecipeLogicMachine machine) {
                GTRecipeType type = machine.getRecipeType();
                try {
                    Object multiType = machine.getClass().getMethod("getMultiRecipeType").invoke(machine);
                    if (multiType instanceof GTRecipeType recipeType) type = recipeType;
                } catch (ReflectiveOperationException ignored) {
                    // Ordinary GT controllers expose their current mode through getRecipeType().
                }
                addRecipeType(types, type, Collections.newSetFromMap(new IdentityHashMap<>()));
            }
        }
        return types;
    }

    private static void addRecipeType(Set<GTRecipeType> types, @Nullable GTRecipeType type, Set<GTRecipeType> visited) {
        if (type == null || !visited.add(type)) return;
        try {
            if (type.getClass().getMethod("getRecipeTypes").invoke(type) instanceof GTRecipeType[] children) {
                boolean expanded = false;
                for (GTRecipeType child : children) {
                    if (child != null && child != type) {
                        addRecipeType(types, child, visited);
                        expanded = true;
                    }
                }
                if (expanded) return;
            }
        } catch (ReflectiveOperationException ignored) {
            // A non-composite recipe type is already the leaf recipe map.
        }
        types.add(type);
    }

    public static List<GTRecipe> getRecipes(RecipeManager manager, Set<GTRecipeType> types) {
        Map<String, GTRecipe> recipes = new TreeMap<>();
        for (GTRecipeType type : types) {
            List<GTRecipe> candidates = new ArrayList<>(manager.getAllRecipesFor(type));
            type.getProxyRecipes().values().forEach(candidates::addAll);
            candidates.addAll(type.getRepresentativeRecipes());
            for (GTRecipe recipe : candidates) {
                recipes.putIfAbsent(type.registryName + "/" + recipe.id, recipe);
            }
        }
        return new ArrayList<>(recipes.values());
    }

    public static @Nullable RecipePattern createPattern(GTRecipe recipe, Level level) {
        Map<AEKey, Long> inputs = new LinkedHashMap<>();
        Map<AEKey, Long> outputs = new LinkedHashMap<>();
        int[] circuit = { -1 };
        List<InputSlot> inputSlots = new ArrayList<>();
        try {
            if (!appendItems(recipe.getInputContents(ItemRecipeCapability.CAP), inputs, 1, false, circuit, "item", inputSlots) ||
                    !appendFluids(recipe.getInputContents(FluidRecipeCapability.CAP), inputs, 1, false, "fluid", inputSlots) ||
                    !appendItems(recipe.getTickInputContents(ItemRecipeCapability.CAP), inputs, recipe.duration, false, circuit, "tick_item", inputSlots) ||
                    !appendFluids(recipe.getTickInputContents(FluidRecipeCapability.CAP), inputs, recipe.duration, false, "tick_fluid", inputSlots) ||
                    inputs.isEmpty())
                return null;

            // A chanced default primary stays unavailable until a guaranteed byproduct is selected in the UI.
            if (!hasChancedPrimaryOutput(recipe)) appendPrimaryOutput(recipe, outputs);
            AEKey primary = outputs.isEmpty() ? null : outputs.keySet().iterator().next();

            // The complete expansion retains guaranteed byproducts. The hatch applies the existing byproduct
            // switch only to the pattern it publishes for AE2 planning.
            outputs.clear();
            if (!appendItems(recipe.getOutputContents(ItemRecipeCapability.CAP), outputs, 1, true, circuit) ||
                    !appendFluids(recipe.getOutputContents(FluidRecipeCapability.CAP), outputs, 1, true) ||
                    !appendItems(recipe.getTickOutputContents(ItemRecipeCapability.CAP), outputs, recipe.duration, true, circuit) ||
                    !appendFluids(recipe.getTickOutputContents(FluidRecipeCapability.CAP), outputs, recipe.duration, true) || outputs.isEmpty())
                return null;

            ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(toStacks(inputs), toStacks(outputs));
            // Distinguish recipes with identical visible IO but different circuits or machine requirements.
            encoded.getOrCreateTag().putString("gtlcoreRecipe", recipe.recipeType.registryName + "/" + recipe.id);
            encoded.getOrCreateTag().putInt("gtlcoreRecipeCircuit", circuit[0]);
            IPatternDetails pattern = PatternDetailsHelper.decodePattern(encoded, level);
            // Keep a preview for search; recipes with probabilistic consumption are never published by the hatch.
            return pattern == null ? null : new RecipePattern(recipe, pattern, circuit[0], primary, getNonConsumedInputs(recipe), hasChancedInput(recipe), List.copyOf(inputSlots));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return null;
        }
    }

    public static boolean hasChancedInput(GTRecipe recipe) {
        // Zero chance means a reusable catalyst, not probabilistic consumption. Check normal and per-tick IO.
        for (List<Content> contents : List.of(recipe.getInputContents(ItemRecipeCapability.CAP),
                recipe.getInputContents(FluidRecipeCapability.CAP), recipe.getTickInputContents(ItemRecipeCapability.CAP),
                recipe.getTickInputContents(FluidRecipeCapability.CAP))) {
            for (Content content : contents) {
                if (content.chance > 0 && content.chance < content.maxChance) return true;
            }
        }
        return false;
    }

    public static boolean hasChancedPrimaryOutput(GTRecipe recipe) {
        // Use the same output order as appendPrimaryOutput; probabilistic byproducts do not exclude a recipe.
        for (List<Content> contents : List.of(recipe.getOutputContents(ItemRecipeCapability.CAP),
                recipe.getOutputContents(FluidRecipeCapability.CAP), recipe.getTickOutputContents(ItemRecipeCapability.CAP),
                recipe.getTickOutputContents(FluidRecipeCapability.CAP))) {
            if (contents.isEmpty()) continue;
            Content content = contents.get(0);
            if (content.chance < content.maxChance) return true;
            if (content.content instanceof Ingredient ingredient && unwrapIngredient(ingredient) instanceof IntProviderIngredient provider) {
                return provider.getCountProvider().getMinValue() <= 0;
            }
            return false;
        }
        return false;
    }

    private static List<List<AEKey>> getNonConsumedInputs(GTRecipe recipe) {
        List<Content> items = new ArrayList<>(recipe.getInputContents(ItemRecipeCapability.CAP));
        items.addAll(recipe.getTickInputContents(ItemRecipeCapability.CAP));
        List<Content> fluids = new ArrayList<>(recipe.getInputContents(FluidRecipeCapability.CAP));
        fluids.addAll(recipe.getTickInputContents(FluidRecipeCapability.CAP));
        Set<AEKey> consumed = new HashSet<>();
        for (Content content : items) {
            if (content.chance > 0) consumed.addAll(itemKeys(content));
        }
        for (Content content : fluids) {
            if (content.chance > 0) consumed.addAll(fluidKeys(content));
        }
        List<List<AEKey>> catalysts = new ArrayList<>();
        for (Content content : items) {
            if (content.chance != 0) continue;
            List<AEKey> keys = itemKeys(content).stream().filter(key -> !consumed.contains(key)).toList();
            if (!keys.isEmpty()) catalysts.add(keys);
        }
        for (Content content : fluids) {
            if (content.chance != 0) continue;
            List<AEKey> keys = fluidKeys(content).stream().filter(key -> !consumed.contains(key)).toList();
            if (!keys.isEmpty()) catalysts.add(keys);
        }
        return List.copyOf(catalysts);
    }

    private static List<AEKey> itemKeys(Content content) {
        Ingredient ingredient = unwrapIngredient(ItemRecipeCapability.CAP.of(content.content));
        // Count is irrelevant to a virtual supply. Do not sample the recipe's random-count cache.
        if (ingredient instanceof IntProviderIngredient provider) ingredient = unwrapIngredient(provider.getInner());
        return Arrays.stream(ingredient.getItems()).filter(stack -> !stack.isEmpty() && !VirtualIngredientEncoding.isAlwaysDropped(stack))
                .map(AEItemKey::of).distinct().map(key -> (AEKey) key).toList();
    }

    private static List<AEKey> fluidKeys(Content content) {
        return Arrays.stream(FluidRecipeCapability.CAP.of(content.content).getStacks()).filter(stack -> !stack.isEmpty())
                .map(stack -> (AEKey) AEFluidKey.of(stack.getFluid(), stack.getTag())).distinct().toList();
    }

    private static Ingredient unwrapIngredient(Ingredient ingredient) {
        while (ingredient instanceof SizedIngredient sized) ingredient = sized.getInner();
        return ingredient;
    }

    private static boolean appendPrimaryOutput(GTRecipe recipe, Map<AEKey, Long> outputs) {
        // Follow GT's display order: ordinary item outputs, fluids, then per-tick outputs.
        var items = recipe.getOutputContents(ItemRecipeCapability.CAP);
        if (!items.isEmpty()) return appendItems(List.of(items.get(0)), outputs, 1, true, new int[] { -1 });
        var fluids = recipe.getOutputContents(FluidRecipeCapability.CAP);
        if (!fluids.isEmpty()) return appendFluids(List.of(fluids.get(0)), outputs, 1, true);
        items = recipe.getTickOutputContents(ItemRecipeCapability.CAP);
        if (!items.isEmpty()) return appendItems(List.of(items.get(0)), outputs, recipe.duration, true, new int[] { -1 });
        fluids = recipe.getTickOutputContents(FluidRecipeCapability.CAP);
        return !fluids.isEmpty() && appendFluids(List.of(fluids.get(0)), outputs, recipe.duration, true);
    }

    private static boolean appendItems(List<Content> contents, Map<AEKey, Long> stacks, long multiplier,
                                       boolean output, int[] circuit) {
        return appendItems(contents, stacks, multiplier, output, circuit, "", null);
    }

    private static boolean appendItems(List<Content> contents, Map<AEKey, Long> stacks, long multiplier,
                                       boolean output, int[] circuit, String slotType, @Nullable List<InputSlot> slots) {
        for (int index = 0; index < contents.size(); index++) {
            Content content = contents.get(index);
            if (output && content.chance < content.maxChance) continue;
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.content);
            long amount = ingredient instanceof LongIngredient longIngredient ? longIngredient.getActualAmount() :
                    ingredient instanceof SizedIngredient sized ? sized.getAmount() : 1;
            Ingredient sample = ingredient;
            while (sample instanceof SizedIngredient sized) sample = sized.getInner();
            if (sample instanceof IntProviderIngredient provider) {
                int count = output ? provider.getCountProvider().getMinValue() : provider.getCountProvider().getMaxValue();
                // Sampling a representative must not alter the recipe's own random-count cache.
                IntProviderIngredient copy = new IntProviderIngredient(provider.getInner(), provider.getCountProvider());
                copy.setSampledCount(count);
                sample = copy;
                amount = count;
            }
            if (output && amount <= 0) continue;
            ItemStack[] options = sample.getItems();
            if (options.length == 0 || options[0].isEmpty()) return false;
            ItemStack stack = options[0];
            if (!output && IntCircuitBehaviour.isIntegratedCircuit(stack)) {
                if (content.chance != 0) return false;
                int config = IntCircuitBehaviour.getCircuitConfiguration(stack);
                if (circuit[0] >= 0 && circuit[0] != config) return false;
                circuit[0] = config;
                continue;
            }
            // Real catalysts stay out of consumable inputs; available virtual wrappers are added when publishing.
            if (!output && content.chance == 0) continue;
            if (amount > 0) {
                long total = Math.multiplyExact(amount, multiplier);
                AEKey key = AEItemKey.of(stack);
                if (slots != null) {
                    List<AEKey> alternatives = Arrays.stream(options).filter(option -> !option.isEmpty())
                            .map(option -> (AEKey) AEItemKey.of(option)).distinct().toList();
                    key = alternatives.stream().filter(alternative -> alternative instanceof AEItemKey item &&
                            Registries.getItemId(item.getItem()).contains("universal_circuit")).findFirst().orElse(key);
                    slots.add(new InputSlot(slotType + ":" + index, total, alternatives, key));
                }
                add(stacks, key, total);
            }
        }
        return true;
    }

    private static boolean appendFluids(List<Content> contents, Map<AEKey, Long> stacks, long multiplier, boolean output) {
        return appendFluids(contents, stacks, multiplier, output, "", null);
    }

    private static boolean appendFluids(List<Content> contents, Map<AEKey, Long> stacks, long multiplier, boolean output,
                                        String slotType, @Nullable List<InputSlot> slots) {
        for (int index = 0; index < contents.size(); index++) {
            Content content = contents.get(index);
            if (output ? content.chance < content.maxChance : content.chance == 0) continue;
            var ingredient = FluidRecipeCapability.CAP.of(content.content);
            var options = ingredient.getStacks();
            if (options.length == 0 || options[0].isEmpty()) return false;
            var stack = options[0];
            long total = Math.multiplyExact(ingredient.getAmount(), multiplier);
            AEKey key = AEFluidKey.of(stack.getFluid(), stack.getTag());
            if (slots != null && total > 0) {
                List<AEKey> alternatives = Arrays.stream(options).filter(option -> !option.isEmpty())
                        .map(option -> (AEKey) AEFluidKey.of(option.getFluid(), option.getTag())).distinct().toList();
                slots.add(new InputSlot(slotType + ":" + index, total, alternatives, key));
            }
            add(stacks, key, total);
        }
        return true;
    }

    private static void add(Map<AEKey, Long> stacks, AEKey key, long amount) {
        if (amount > 0) stacks.merge(key, amount, Math::addExact);
    }

    private static GenericStack[] toStacks(Map<AEKey, Long> stacks) {
        return stacks.entrySet().stream().map(entry -> new GenericStack(entry.getKey(), entry.getValue())).toArray(GenericStack[]::new);
    }
}
