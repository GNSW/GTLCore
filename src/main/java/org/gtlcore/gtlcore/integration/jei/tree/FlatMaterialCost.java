package org.gtlcore.gtlcore.integration.jei.tree;

class FlatMaterialCost {

    final TreeIngredient ingredient;
    long amount;

    FlatMaterialCost(TreeIngredient ingredient, long amount) {
        this.ingredient = ingredient;
        this.amount = amount;
    }

    long effectiveAmount() {
        return amount;
    }
}
