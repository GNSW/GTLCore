package org.gtlcore.gtlcore.integration.jei.tree;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One material in the recipe tree. Mirrors EMI's {@code MaterialNode} one-to-one.
 */
final class MaterialNode {

    final TreeIngredient ingredient;
    final @Nullable TreeIngredient.Variant remainder;
    long remainderAmount;
    @Nullable
    TreeRecipe recipe;
    @Nullable
    List<MaterialNode> children;
    float consumeChance = 1;
    float produceChance = 1;
    long amount = 1;
    long divisor = 1;
    boolean catalyst;
    FoldState state = FoldState.EXPANDED;
    ProgressState progress = ProgressState.UNSTARTED;
    long neededBatches = 0;
    long totalNeeded = 0;

    MaterialNode(TreeIngredient ingredient) {
        this.amount = ingredient.amount();
        this.ingredient = ingredient.setAmount(1).setChance(1);
        this.remainder = ingredient.remainder();
        this.remainderAmount = ingredient.remainderAmount();
        this.catalyst = ingredient.catalyst();
    }

    MaterialNode(MaterialNode node) {
        this.ingredient = node.ingredient;
        this.remainder = node.remainder;
        this.recipe = node.recipe;
        this.remainderAmount = node.remainderAmount;
        this.amount = node.amount;
        this.divisor = node.divisor;
        this.catalyst = node.catalyst;
    }

    void recalculate(MaterialTree tree) {
        recalculate(tree, new ArrayList<>());
    }

    private void recalculate(MaterialTree tree, List<TreeRecipe> used) {
        if (!used.isEmpty() && ingredient.toolLike()) {
            // GT crafting tools are reused; never expand their own recipe inside a tree.
            this.recipe = null;
            this.children = null;
            return;
        }
        TreeRecipe recipe = this.recipe;
        if (!used.isEmpty()) {
            recipe = tree.getRecipe(ingredient);
        }
        if (recipe != null) {
            if (used.contains(recipe)) {
                return;
            }
            used.add(recipe);
            defineRecipe(recipe);
            if (children != null) {
                for (MaterialNode node : children) {
                    node.recalculate(tree, used);
                }
            }
            used.remove(used.size() - 1);
        }
    }

    void defineRecipe(@Nullable TreeRecipe recipe) {
        produceChance = 1;
        if (recipe == null) {
            return;
        }
        this.recipe = recipe;
        divisor = 0;
        for (TreeIngredient output : recipe.outputs()) {
            if (output.key().equals(ingredient.key())) {
                long outputAmount = output.amount();
                float outputChance = output.chance();
                if (divisor > 0) {
                    if (produceChance != 1 || outputChance != 1) {
                        produceChance = (outputAmount * outputChance + divisor * produceChance) /
                                (divisor + outputAmount);
                    }
                    divisor += outputAmount;
                } else {
                    divisor = outputAmount;
                    produceChance = outputChance;
                }
            }
        }
        if (divisor <= 0) {
            divisor = 1;
        }

        children = new ArrayList<>();
        outer:
        for (TreeIngredient input : recipe.inputs()) {
            TreeIngredient.Variant inputRemainder = input.remainder();
            for (MaterialNode node : children) {
                if (input.matches(node.ingredient) && sameRemainder(inputRemainder, node.remainder)) {
                    node.amount += input.amount();
                    node.remainderAmount += input.remainderAmount();
                    continue outer;
                }
            }
            if (!input.isEmpty()) {
                MaterialNode node = new MaterialNode(input);
                node.consumeChance = input.chance();
                children.add(node);
            }
        }
    }

    private static boolean sameRemainder(@Nullable TreeIngredient.Variant first,
                                         @Nullable TreeIngredient.Variant second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.uid().equals(second.uid());
    }
}
