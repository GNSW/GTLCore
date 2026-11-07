package org.gtlcore.gtlcore.integration.jei.tree;

class ChanceMaterialCost extends FlatMaterialCost {

    long minBatch = 1;
    float chance;

    ChanceMaterialCost(TreeIngredient ingredient, long amount, float chance) {
        super(ingredient, amount);
        this.chance = chance;
    }

    void merge(long addedAmount, float addedChance) {
        long sum = this.amount + addedAmount;
        if (sum > 0) {
            this.chance = (this.chance * this.amount + addedChance * addedAmount) / sum;
        }
        this.amount += addedAmount;
    }

    void minBatch(long value) {
        this.minBatch = Math.max(this.minBatch, value);
    }

    @Override
    long effectiveAmount() {
        return Math.max(minBatch, Math.round(amount * chance));
    }
}
