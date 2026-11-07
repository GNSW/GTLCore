package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.fluids.FluidStack;

import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a JEI-native recipe tree from the same ingredient slots that JEI displays.
 *
 * <p>
 * The model works for GT recipes and ordinary JEI categories alike. It intentionally keeps
 * recipe selection separate from the rendered tree so that a node can be folded without changing
 * the material calculation.
 * </p>
 */
final class JeiRecipeTreeModel {

    static final int MAX_DEPTH = 7;
    static final int MAX_NODES = 180;
    private static final int MAX_ALTERNATIVES = 12;

    private final IIngredientManager ingredientManager;
    private final IRecipeManager recipeManager;
    private final IFocusFactory focusFactory;
    private final RecipeAlternative originalRecipe;
    private final IngredientStack rootIngredient;
    private final Map<String, Integer> selections;
    private final Set<String> collapsed;
    private final Map<String, List<RecipeAlternative>> alternativeCache = new HashMap<>();
    private final Map<AlternativeIdentity, RecipeShape> shapeCache = new HashMap<>();

    private int remainingNodes;
    private int expandedNodes;

    JeiRecipeTreeModel(IJeiRuntime runtime,
                       IRecipeLayoutDrawable<?> originalLayout,
                       Map<String, Integer> selections,
                       Set<String> collapsed) {
        this.ingredientManager = runtime.getIngredientManager();
        this.recipeManager = runtime.getRecipeManager();
        this.focusFactory = runtime.getJeiHelpers().getFocusFactory();
        this.originalRecipe = RecipeAlternative.ofUntyped(originalLayout);
        this.rootIngredient = findRootIngredient(ingredientManager, originalLayout)
                .orElseThrow(() -> new IllegalArgumentException("Recipe has no output ingredient"));
        this.selections = selections;
        this.collapsed = collapsed;
    }

    IngredientStack rootIngredient() {
        return rootIngredient;
    }

    RecipeNode build(long batches) {
        remainingNodes = MAX_NODES;
        expandedNodes = 0;
        IngredientStack goal = rootIngredient.multiplied(Math.max(1, batches));
        return buildNode(goal, 0, "root", new HashSet<>());
    }

    CostSummary summarize(RecipeNode root) {
        Map<String, CostEntry> costs = new LinkedHashMap<>();
        collectCosts(root, costs);
        return new CostSummary(List.copyOf(costs.values()), expandedNodes);
    }

    private RecipeNode buildNode(IngredientStack required, int depth, String path, Set<String> ancestry) {
        RecipeNode node = new RecipeNode(required, depth, path);
        if (remainingNodes-- <= 0) {
            node.budgetExceeded = true;
            return node;
        }
        expandedNodes++;

        List<RecipeAlternative> alternatives = getAlternatives(required);
        node.alternatives = alternatives;
        if (alternatives.isEmpty()) {
            return node;
        }

        int requestedIndex = selections.getOrDefault(required.key(), 0);
        int selectedIndex = Math.max(0, Math.min(requestedIndex, alternatives.size() - 1));
        RecipeAlternative selected = alternatives.get(selectedIndex);
        RecipeShape shape = getShape(selected);
        node.recipe = selected;
        node.recipeLabel = selected.label(selectedIndex);
        node.collapsed = collapsed.contains(path);

        if (depth >= MAX_DEPTH) {
            node.depthLimited = true;
            return node;
        }
        if (ancestry.contains(required.key())) {
            node.cycle = true;
            return node;
        }

        IngredientStack produced = shape.outputFor(required.key());
        long outputAmount = Math.max(1, produced.amount());
        double outputChance = produced.chance() <= 0.0 ? 1.0 :
                Math.min(1.0, produced.chance());
        node.crafts = Math.max(1, divideCeiling(required.amount(),
                Math.max(1, (long) Math.ceil(outputAmount * outputChance))));

        Set<String> nextAncestry = new HashSet<>(ancestry);
        nextAncestry.add(required.key());
        int childIndex = 0;
        for (IngredientStack input : shape.inputs()) {
            IngredientStack childRequired = input.multiplied(node.crafts).expectedAmount();
            RecipeNode child = buildNode(
                    childRequired,
                    depth + 1,
                    path + "/" + childIndex++,
                    nextAncestry);
            node.children.add(child);
        }
        return node;
    }

    private void collectCosts(RecipeNode node, Map<String, CostEntry> costs) {
        if (!node.children.isEmpty()) {
            for (RecipeNode child : node.children) {
                collectCosts(child, costs);
            }
            return;
        }
        CostEntry previous = costs.get(node.ingredient.key());
        if (previous == null) {
            costs.put(node.ingredient.key(), new CostEntry(node.ingredient, node.ingredient.amount()));
        } else {
            costs.put(node.ingredient.key(), new CostEntry(
                    previous.ingredient().withChance(Math.min(
                            previous.ingredient().chance(), node.ingredient.chance())),
                    safeAdd(previous.amount(), node.ingredient.amount())));
        }
    }

    private List<RecipeAlternative> getAlternatives(IngredientStack ingredient) {
        if (ingredient.key().equals(rootIngredient.key())) {
            return List.copyOf(alternativeCache.computeIfAbsent(ingredient.key(), key -> {
                LinkedHashSet<RecipeAlternative> alternatives = new LinkedHashSet<>();
                alternatives.add(originalRecipe);
                lookupAlternatives(ingredient, alternatives);
                return List.copyOf(alternatives);
            }));
        }
        return List.copyOf(alternativeCache.computeIfAbsent(ingredient.key(),
                key -> lookupAlternatives(ingredient, new LinkedHashSet<>())));
    }

    private List<RecipeAlternative> lookupAlternatives(IngredientStack ingredient,
                                                       LinkedHashSet<RecipeAlternative> alternatives) {
        if (alternatives.size() >= MAX_ALTERNATIVES) {
            return List.copyOf(alternatives);
        }

        IFocus<?> focus = focusFactory.createFocus(RecipeIngredientRole.OUTPUT, ingredient.typed());
        List<IFocus<?>> focuses = List.of(focus);
        List<IRecipeCategory<?>> categories = recipeManager.createRecipeCategoryLookup()
                .limitFocus(focuses)
                .get()
                .toList();
        for (IRecipeCategory<?> category : categories) {
            collectCategory(category, focuses, alternatives);
            if (alternatives.size() >= MAX_ALTERNATIVES) {
                break;
            }
        }
        return List.copyOf(alternatives);
    }

    private <T> void collectCategory(IRecipeCategory<T> category,
                                     List<IFocus<?>> focuses,
                                     LinkedHashSet<RecipeAlternative> alternatives) {
        try {
            recipeManager.createRecipeLookup(category.getRecipeType())
                    .limitFocus(focuses)
                    .get()
                    .limit(MAX_ALTERNATIVES - alternatives.size())
                    .map(recipe -> RecipeAlternative.of(category, recipe))
                    .forEach(alternatives::add);
        } catch (RuntimeException exception) {
            GTLCore.LOGGER.debug("Could not query JEI recipe tree alternatives for {}",
                    category.getRecipeType().getUid(), exception);
        }
    }

    private RecipeShape getShape(RecipeAlternative alternative) {
        return shapeCache.computeIfAbsent(AlternativeIdentity.of(alternative), key -> extractShape(alternative));
    }

    @SuppressWarnings("unchecked")
    private RecipeShape extractShape(RecipeAlternative alternative) {
        try {
            Optional<IRecipeLayoutDrawable<Object>> layout = recipeManager.createRecipeLayoutDrawable(
                    (IRecipeCategory<Object>) alternative.category(),
                    alternative.recipe(),
                    focusFactory.getEmptyFocusGroup());
            if (layout.isEmpty()) {
                return RecipeShape.EMPTY;
            }

            List<IngredientStack> inputs = new ArrayList<>();
            List<IngredientStack> outputs = new ArrayList<>();
            for (IRecipeSlotView slot : layout.get().getRecipeSlotsView().getSlotViews()) {
                IngredientStack ingredient = firstIngredient(slot);
                if (ingredient == null) {
                    continue;
                }
                if (slot.getRole() == RecipeIngredientRole.INPUT) {
                    inputs.add(ingredient.withChance(chanceFor(alternative, RecipeIngredientRole.INPUT,
                            ingredient)));
                } else if (slot.getRole() == RecipeIngredientRole.OUTPUT) {
                    outputs.add(ingredient.withChance(chanceFor(alternative, RecipeIngredientRole.OUTPUT,
                            ingredient)));
                }
            }
            return new RecipeShape(List.copyOf(inputs), List.copyOf(outputs));
        } catch (RuntimeException exception) {
            GTLCore.LOGGER.debug("Could not inspect JEI recipe tree layout for {}",
                    alternative.category().getRecipeType().getUid(), exception);
            return RecipeShape.EMPTY;
        }
    }

    private @Nullable IngredientStack firstIngredient(IRecipeSlotView slot) {
        return slot.getAllIngredients()
                .map(typed -> IngredientStack.from(ingredientManager, typed))
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(null);
    }

    private double chanceFor(RecipeAlternative alternative,
                             RecipeIngredientRole role,
                             IngredientStack ingredient) {
        if (!(alternative.recipe() instanceof GTRecipeWrapper wrapper)) {
            return 1.0;
        }
        Optional<ItemStack> item = ingredient.typed().getItemStack();
        if (item.isPresent()) {
            var contents = role == RecipeIngredientRole.INPUT ?
                    wrapper.recipe.getInputContents(ItemRecipeCapability.CAP) :
                    wrapper.recipe.getOutputContents(ItemRecipeCapability.CAP);
            for (var content : contents) {
                if (ItemRecipeCapability.CAP.of(content.getContent()).test(item.get())) {
                    return chance(content.chance, content.maxChance);
                }
            }
        }

        Optional<FluidStack> fluid = ingredient.typed().getIngredient(ForgeTypes.FLUID_STACK);
        if (fluid.isPresent()) {
            var contents = role == RecipeIngredientRole.INPUT ?
                    wrapper.recipe.getInputContents(FluidRecipeCapability.CAP) :
                    wrapper.recipe.getOutputContents(FluidRecipeCapability.CAP);
            for (var content : contents) {
                for (var candidate : FluidRecipeCapability.CAP.of(content.getContent()).getStacks()) {
                    if (candidate.getFluid() == fluid.get().getFluid() &&
                            java.util.Objects.equals(candidate.getTag(), fluid.get().getTag())) {
                        return chance(content.chance, content.maxChance);
                    }
                }
            }
        }
        return 1.0;
    }

    private static double chance(int chance, int maxChance) {
        if (maxChance <= 0 || chance >= maxChance) {
            return 1.0;
        }
        return Math.max(0.0, chance / (double) maxChance);
    }

    static Optional<IngredientStack> findRootIngredient(IIngredientManager ingredientManager,
                                                        IRecipeLayoutDrawable<?> layout) {
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT)) {
            Optional<IngredientStack> ingredient = slot.getAllIngredients()
                    .map(typed -> IngredientStack.from(ingredientManager, typed))
                    .flatMap(Optional::stream)
                    .findFirst();
            if (ingredient.isPresent()) {
                return ingredient;
            }
        }
        return Optional.empty();
    }

    private static long divideCeiling(long amount, long divisor) {
        if (amount <= 0) {
            return 1;
        }
        return amount / divisor + (amount % divisor == 0 ? 0 : 1);
    }

    private static long safeAdd(long first, long second) {
        if (Long.MAX_VALUE - first < second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    record RecipeAlternative(IRecipeCategory<Object> category, Object recipe) {

        @SuppressWarnings("unchecked")
        static <T> RecipeAlternative of(IRecipeCategory<T> category, T recipe) {
            return new RecipeAlternative((IRecipeCategory<Object>) category, recipe);
        }

        @SuppressWarnings("unchecked")
        static RecipeAlternative ofUntyped(IRecipeLayoutDrawable<?> layout) {
            return of((IRecipeCategory<Object>) layout.getRecipeCategory(), layout.getRecipe());
        }

        String label(int index) {
            Component title = category.getTitle();
            String recipeName = recipe.getClass().getSimpleName();
            if (recipeName.isBlank()) {
                recipeName = Integer.toString(index + 1);
            }
            return title.getString() + " · " + recipeName;
        }
    }

    private record AlternativeIdentity(IRecipeCategory<?> category, Object recipe) {

        static AlternativeIdentity of(RecipeAlternative alternative) {
            return new AlternativeIdentity(alternative.category(), alternative.recipe());
        }
    }

    private record RecipeShape(List<IngredientStack> inputs, List<IngredientStack> outputs) {

        private static final RecipeShape EMPTY = new RecipeShape(List.of(), List.of());

        IngredientStack outputFor(String ingredientKey) {
            for (IngredientStack output : outputs) {
                if (output.key().equals(ingredientKey)) {
                    return output;
                }
            }
            return outputs.isEmpty() ? IngredientStack.EMPTY : outputs.get(0);
        }
    }

    static final class RecipeNode {

        final IngredientStack ingredient;
        final int depth;
        final String path;
        final List<RecipeNode> children = new ArrayList<>();
        List<RecipeAlternative> alternatives = List.of();
        @Nullable
        RecipeAlternative recipe;
        String recipeLabel = "";
        long crafts = 1;
        boolean collapsed;
        boolean cycle;
        boolean depthLimited;
        boolean budgetExceeded;

        private RecipeNode(IngredientStack ingredient, int depth, String path) {
            this.ingredient = ingredient;
            this.depth = depth;
            this.path = path;
        }
    }

    record CostEntry(IngredientStack ingredient, long amount) {}

    record CostSummary(List<CostEntry> entries, int nodeCount) {}

    record IngredientStack(ITypedIngredient<Object> typed,
                           IIngredientRenderer<Object> renderer,
                           String key,
                           String name,
                           long amount,
                           double chance) {

        private static final IngredientStack EMPTY = new IngredientStack(
                null, null, "", "", 1, 1.0);

        static Optional<IngredientStack> from(IIngredientManager ingredientManager,
                                              ITypedIngredient<?> typed) {
            return fromTyped(ingredientManager, typed, 0);
        }

        private static <T> Optional<IngredientStack> fromTyped(IIngredientManager ingredientManager,
                                                               ITypedIngredient<T> typed,
                                                               long requestedAmount) {
            IIngredientHelper<T> helper = ingredientManager.getIngredientHelper(typed.getType());
            T copiedIngredient = helper.copyIngredient(typed.getIngredient());
            ITypedIngredient<T> copied = ingredientManager.createTypedIngredient(typed.getType(), copiedIngredient)
                    .orElse(typed);
            long amount = requestedAmount > 0 ? requestedAmount : helper.getAmount(copiedIngredient);
            amount = Math.max(1, amount);
            IIngredientRenderer<T> renderer = ingredientManager.getIngredientRenderer(typed.getType());
            return Optional.of(new IngredientStack(
                    (ITypedIngredient<Object>) copied,
                    (IIngredientRenderer<Object>) renderer,
                    helper.getUniqueId(copiedIngredient, UidContext.Ingredient),
                    helper.getDisplayName(copiedIngredient),
                    amount,
                    1.0));
        }

        IngredientStack multiplied(long multiplier) {
            if (this == EMPTY || multiplier <= 1) {
                return this;
            }
            long result;
            if (amount > Long.MAX_VALUE / multiplier) {
                result = Long.MAX_VALUE;
            } else {
                result = amount * multiplier;
            }
            return new IngredientStack(typed, renderer, key, name, result, chance);
        }

        IngredientStack expectedAmount() {
            if (chance >= 1.0 || chance <= 0.0) {
                return this;
            }
            long expected = (long) Math.ceil(amount / chance);
            return new IngredientStack(typed, renderer, key, name,
                    expected > 0 ? expected : amount, chance);
        }

        IngredientStack withChance(double value) {
            return new IngredientStack(typed, renderer, key, name, amount,
                    Math.max(0.0, Math.min(1.0, value)));
        }

        void render(GuiGraphics graphics, int x, int y) {
            if (typed != null && renderer != null) {
                renderer.render(graphics, typed.getIngredient(), x, y);
            }
        }

        List<Component> tooltip() {
            if (typed == null || renderer == null) {
                return List.of();
            }
            return List.copyOf(renderer.getTooltip(typed.getIngredient(), TooltipFlag.Default.NORMAL));
        }

        Component amountText() {
            String text = amount > 999_999 ? compact(amount) : Long.toString(amount);
            return Component.literal("x" + text).withStyle(ChatFormatting.GRAY);
        }

        private static String compact(long amount) {
            if (amount >= 1_000_000_000_000L) {
                return String.format("%.2fT", amount / 1_000_000_000_000.0);
            }
            if (amount >= 1_000_000_000L) {
                return String.format("%.2fB", amount / 1_000_000_000.0);
            }
            if (amount >= 1_000_000L) {
                return String.format("%.2fM", amount / 1_000_000.0);
            }
            return Long.toString(amount);
        }
    }
}
