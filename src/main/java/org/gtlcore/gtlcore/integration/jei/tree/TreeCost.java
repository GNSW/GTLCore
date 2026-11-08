package org.gtlcore.gtlcore.integration.jei.tree;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Material totals, leftovers and progress calculation. Mirrors EMI's {@code TreeCost}.
 */
final class TreeCost {

    final Map<String, FlatMaterialCost> costs = new LinkedHashMap<>();
    final Map<String, ChanceMaterialCost> chanceCosts = new LinkedHashMap<>();
    final Map<String, FlatMaterialCost> remainders = new LinkedHashMap<>();
    final Map<String, ChanceMaterialCost> chanceRemainders = new LinkedHashMap<>();

    void calculate(MaterialNode node, long batches) {
        costs.clear();
        chanceCosts.clear();
        remainders.clear();
        chanceRemainders.clear();
        calculateCost(node, batches * node.amount, ChanceState.DEFAULT, false);
    }

    void calculateProgress(MaterialNode node, long batches, Map<String, TreeIngredient> inventory) {
        costs.clear();
        chanceCosts.clear();
        remainders.clear();
        chanceRemainders.clear();
        // The goal item itself must always be crafted, even when one copy is already
        // in the player's inventory. Only its ingredients count as already obtained.
        java.util.Set<String> excluded = new java.util.HashSet<>();
        excluded.add(node.ingredient.key());
        for (TreeIngredient.Variant variant : node.ingredient.variants()) {
            excluded.add(variant.uid());
        }
        for (TreeIngredient stack : inventory.values()) {
            if (!excluded.contains(stack.key())) {
                remainders.put(stack.key(), new FlatMaterialCost(stack, stack.amount()));
            }
        }
        calculateCost(node, batches * node.amount, ChanceState.DEFAULT, true);
    }

    private void addCost(TreeIngredient stack, long amount, long minBatch, ChanceState chance) {
        if (chance.chanced()) {
            ChanceMaterialCost cost = chanceCosts.get(stack.key());
            if (cost == null) {
                cost = new ChanceMaterialCost(stack, amount, chance.chance());
                chanceCosts.put(stack.key(), cost);
            } else {
                cost.merge(amount, chance.chance());
            }
            cost.minBatch(minBatch);
        } else {
            FlatMaterialCost cost = costs.get(stack.key());
            if (cost != null) {
                if (stack.toolLike()) {
                    cost.amount = Math.max(cost.amount, amount);
                } else {
                    cost.amount += amount;
                }
            } else {
                costs.put(stack.key(), new FlatMaterialCost(stack, amount));
            }
        }
    }

    private void addRemainder(TreeIngredient stack, long amount, ChanceState chance) {
        if (amount <= 0) {
            return;
        }
        if (chance.chanced()) {
            ChanceMaterialCost cost = chanceRemainders.get(stack.key());
            if (cost == null) {
                cost = new ChanceMaterialCost(stack, amount, chance.chance());
                chanceRemainders.put(stack.key(), cost);
            } else {
                cost.merge(amount, chance.chance());
            }
        } else {
            FlatMaterialCost cost = remainders.get(stack.key());
            if (cost != null) {
                cost.amount += amount;
            } else {
                remainders.put(stack.key(), new FlatMaterialCost(stack, amount));
            }
        }
    }

    private void addRemainder(TreeIngredient.Variant stack, long amount, ChanceState chance) {
        addRemainder(TreeIngredient.single(stack), amount, chance);
    }

    private double getChancedRemainder(TreeIngredient.Variant stack,
                                       double desired,
                                       boolean catalyst,
                                       ChanceState chance) {
        double given = 0;
        ChanceMaterialCost chancedRemainder = chanceRemainders.get(stack.uid());
        if (chancedRemainder != null) {
            double remainderEff = chancedRemainder.amount * chancedRemainder.chance;
            if (remainderEff >= desired) {
                if (!catalyst) {
                    chancedRemainder.amount = 1;
                    chancedRemainder.chance = (float) (remainderEff - desired);
                    if (chancedRemainder.chance == 0) {
                        chanceRemainders.remove(stack.uid());
                    }
                }
                return desired;
            } else {
                given = remainderEff;
                if (!catalyst) {
                    double leftover = remainderEff - given * chance.chance();
                    if (leftover == 0) {
                        chanceRemainders.remove(stack.uid());
                    } else {
                        chancedRemainder.amount = 1;
                        chancedRemainder.chance = (float) leftover;
                    }
                }
            }
        } else {
            FlatMaterialCost remainder = remainders.get(stack.uid());
            if (remainder != null) {
                if (remainder.amount >= desired) {
                    if (!catalyst) {
                        remainder.amount -= (long) desired;
                        if (remainder.amount == 0) {
                            remainders.remove(stack.uid());
                        }
                    }
                    return desired;
                } else {
                    if (!catalyst) {
                        remainders.remove(stack.uid());
                    }
                    given += remainder.amount;
                }
            }
        }
        return given;
    }

    private long getRemainder(TreeIngredient.Variant stack, long desired, boolean catalyst) {
        FlatMaterialCost remainder = remainders.get(stack.uid());
        if (remainder != null) {
            if (remainder.amount >= desired) {
                if (!catalyst) {
                    remainder.amount -= desired;
                    if (remainder.amount == 0) {
                        remainders.remove(stack.uid());
                    }
                }
                return desired;
            } else {
                if (!catalyst) {
                    remainders.remove(stack.uid());
                }
                return remainder.amount;
            }
        }
        return 0;
    }

    private void complete(MaterialNode node) {
        node.progress = ProgressState.COMPLETED;
        node.totalNeeded = 0;
        node.neededBatches = 0;
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                complete(child);
            }
        }
    }

    private void calculateCost(MaterialNode node,
                               long amount,
                               ChanceState chance,
                               boolean trackProgress) {
        if (trackProgress) {
            node.progress = ProgressState.UNSTARTED;
            node.totalNeeded = 0;
            node.neededBatches = 0;
        }
        boolean catalyst = node.catalyst;
        if (catalyst) {
            amount = node.amount;
        }
        TreeRecipe recipe = node.recipe;
        if (recipe != null && recipe.isResolution()) {
            if (node.children != null && !node.children.isEmpty()) {
                calculateCost(node.children.get(0), amount, chance, trackProgress);
            }
            if (catalyst && recipe.resolutionOutput() != null) {
                addRemainder(recipe.resolutionOutput().displayVariant(), amount, chance);
            }
            return;
        }
        long original = amount;
        for (TreeIngredient.Variant stack : node.ingredient.variants()) {
            if (chance.chanced()) {
                double desired = amount * chance.chance();
                double given = getChancedRemainder(stack, desired, catalyst, chance);
                if (given > 0) {
                    double scaled = given / chance.chance();
                    amount -= (long) scaled;
                    if (amount > 0) {
                        chance = new ChanceState(
                                (float) ((amount - (scaled % 1)) * chance.chance() / amount), true);
                    }
                }
            } else {
                amount -= getRemainder(stack, amount, catalyst);
            }
        }
        if (amount == 0) {
            if (trackProgress) {
                complete(node);
            }
            return;
        }
        if (trackProgress && amount != original) {
            node.progress = ProgressState.PARTIAL;
        }

        long effectiveCrafts = amount;
        if (recipe != null) {
            long minBatches = (long) Math.ceil(amount / (double) node.divisor);
            effectiveCrafts = minBatches * node.divisor;
            if (trackProgress) {
                node.totalNeeded = amount;
                node.neededBatches = minBatches;
            }
            ChanceState produced = chance.produce(node.produceChance);
            if (node.children != null) {
                for (MaterialNode child : node.children) {
                    calculateCost(child, minBatches * child.amount,
                            produced.consume(child.consumeChance), trackProgress);
                }
            }
            addRemainder(node.ingredient.displayVariant(), effectiveCrafts - amount, produced);

            for (TreeIngredient output : recipe.outputs()) {
                if (!output.key().equals(node.ingredient.key())) {
                    addRemainder(output.displayVariant(), minBatches * output.amount(),
                            produced.consume(output.chance()));
                }
            }

            if (node.children != null) {
                for (MaterialNode child : node.children) {
                    if (!child.ingredient.toolLike() &&
                            child.remainder != null && child.remainderAmount > 0) {
                        if (child.catalyst) {
                            addRemainder(child.remainder, child.remainderAmount,
                                    produced.consume(child.consumeChance));
                        } else {
                            addRemainder(child.remainder, minBatches * child.remainderAmount,
                                    produced.consume(child.consumeChance));
                        }
                    }
                }
            }
        } else {
            addCost(node.ingredient, amount, node.amount, chance);
        }
    }

    long getIdealBatch(MaterialNode node, long total, long amount) {
        if (node.recipe == null) {
            return total;
        }
        if (node.divisor > 0) {
            long mod = node.divisor / gcd(node.divisor, amount);
            total *= mod / gcd(total, mod);
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                total = getIdealBatch(child, total, amount * child.amount);
            }
        }
        return total;
    }

    private static long gcd(long first, long second) {
        return second == 0 ? first : gcd(second, first % second);
    }
}
