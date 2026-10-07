package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded startup/return-path ordering hints. Never removes a firing or proves failure. */
final class CountScheduleOrder<K> implements AutoCloseable {

    private final RecipeCountModel<K> model;
    private final List<SequenceSummary<K>> actions;
    private final PlanningBudget budget;
    private final long allowance;
    private final Map<K, BitSet> returns = new HashMap<>();
    private long memory, work;

    CountScheduleOrder(RecipeCountModel<K> model, List<SequenceSummary<K>> actions, PlanningBudget budget) {
        this.model = model;
        this.actions = actions;
        this.budget = budget;
        allowance = Math.min(16384, budget.remainingWork() / 32);
        long incidences = actions.stream().mapToLong(a -> a.required().size() + a.delta().size()).sum();
        long bytes = 512 + 96L * model.keys.size() + 16L * actions.size() * actions.size();
        if (actions.size() > 64 || model.keys.size() > 256 || incidences > 1024 || allowance < 1024 || !budget.tryReserve(bytes)) return;
        memory = bytes;
        long before = budget.threadWork();
        try {
            BitSet[] paths = new BitSet[actions.size()];
            for (int i = 0; i < paths.length; i++) {
                paths[i] = new BitSet();
                paths[i].set(i);
                for (int j = 0; j < paths.length; j++) {
                    budget.check();
                    for (K key : actions.get(i).delta().keySet()) {
                        budget.check();
                        if (actions.get(i).delta(key).signum() > 0 && actions.get(j).required(key).signum() > 0) paths[i].set(j);
                    }
                    if (budget.threadWork() - before >= allowance / 2) {
                        close();
                        return;
                    }
                }
            }
            for (int via = 0; via < paths.length; via++) for (int from = 0; from < paths.length; from++) {
                budget.check();
                if (budget.threadWork() - before >= allowance / 2) {
                    close();
                    return;
                }
                if (paths[from].get(via)) paths[from].or(paths[via]);
            }
            for (K key : model.keys) {
                BitSet suppliers = new BitSet();
                for (int i = 0; i < paths.length; i++) if (actions.get(i).delta(key).signum() > 0) suppliers.set(i);
                BitSet capable = new BitSet();
                for (int i = 0; i < paths.length; i++) {
                    budget.check();
                    if (paths[i].intersects(suppliers)) capable.set(i);
                }
                returns.put(key, capable);
            }
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    boolean available() {
        return memory != 0 && work < allowance;
    }

    int choose(Map<K, BigInteger> held, BigInteger[] remaining, BitSet visited) {
        int fallback = visited.nextClearBit(0);
        if (!available()) return fallback;
        long before = budget.threadWork();
        int best = -1, bestUnlock = Integer.MIN_VALUE, bestFunded = Integer.MIN_VALUE;
        try {
            for (int i = 0; i < remaining.length; i++) if (!visited.get(i) && remaining[i].signum() > 0 && enabled(i, held, null)) {
                int unlocked = 0, funded = 0;
                for (int j = 0; j < remaining.length; j++) if (remaining[j].signum() > 0 && (j != i || remaining[j].compareTo(BigInteger.ONE) > 0)) {
                    boolean old = enabled(j, held, null), next = enabled(j, held, actions.get(i));
                    if (next) funded++;
                    if (next && !old) unlocked++;
                    if (!next && old) unlocked--;
                    if (work + budget.threadWork() - before >= allowance) return best < 0 ? i : best;
                }
                if (best < 0 || unlocked > bestUnlock || unlocked == bestUnlock && funded > bestFunded) {
                    best = i;
                    bestUnlock = unlocked;
                    bestFunded = funded;
                }
            }
            return best < 0 ? fallback : best;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean enabled(int recipe, Map<K, BigInteger> held, SequenceSummary<K> change) {
        for (var need : actions.get(recipe).required().entrySet()) {
            budget.check();
            if (model.external.contains(need.getKey())) continue;
            BigInteger amount = held.getOrDefault(need.getKey(), BigInteger.ZERO);
            if (change != null) amount = amount.add(change.delta(need.getKey()));
            if (amount.compareTo(need.getValue()) < 0) return false;
        }
        return true;
    }

    BigInteger batch(int action, BigInteger maximum, Map<K, BigInteger> held, BigInteger[] remaining) {
        if (!available()) return maximum;
        long before = budget.threadWork();
        try {
            for (var change : actions.get(action).delta().entrySet()) {
                if (change.getValue().signum() >= 0 || model.external.contains(change.getKey())) continue;
                BitSet capable = returns.get(change.getKey());
                if (capable == null) continue;
                BigInteger reserve = BigInteger.ZERO;
                for (int other = capable.nextSetBit(0); other >= 0; other = capable.nextSetBit(other + 1)) {
                    budget.check();
                    if (other != action && remaining[other].signum() > 0) reserve = reserve.max(actions.get(other).required(change.getKey()));
                    if (work + budget.threadWork() - before >= allowance) return maximum;
                }
                if (reserve.signum() > 0) {
                    BigInteger spare = held.getOrDefault(change.getKey(), BigInteger.ZERO).subtract(reserve).max(BigInteger.ZERO);
                    // Even an unhelpful pool hint leaves at least one enabled
                    // firing. The unguided/exhaustive continuations remain.
                    maximum = maximum.min(spare.divide(change.getValue().negate()).max(BigInteger.ONE));
                }
            }
            return maximum;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    @Override
    public void close() {
        returns.clear();
        budget.release(memory);
        memory = 0;
    }
}
