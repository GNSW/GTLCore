package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Exact count propagation and compressed scheduling within one selected SCC. */
final class RegionCounts<K> implements AutoCloseable {

    private final List<GraphRecipe<K>> recipes;
    private final Map<K, BigInteger> demand, goals;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final boolean preserve;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final Map<String, GraphRecipe<K>> byId = new LinkedHashMap<>();
    private final Map<K, Long> reserve = new LinkedHashMap<>();
    private RecipeCountModel<K> model;
    private CountBounds bounds;
    private CountSchedule<K> schedule;
    private CountProgram<K> countedProgram;
    private SummaryComputation<K> computation;
    private RegionSelection.Choice<K> result;
    private boolean complete;
    private int refinements;
    private BigInteger[] counts;
    private PlanStep body;

    RegionCounts(List<GraphRecipe<K>> recipes, Map<K, BigInteger> demand, Map<K, Long> stock,
                 Set<K> external, K target, long amount, boolean force, boolean preserve, PlanningBudget budget) {
        this.recipes = recipes;
        this.demand = new LinkedHashMap<>(demand);
        if (force && recipes.stream().anyMatch(recipe -> recipe.outputs().containsKey(target)))
            this.demand.merge(target, BigInteger.valueOf(stock.getOrDefault(target, 0L)).add(BigInteger.valueOf(amount)), BigInteger::max);
        this.goals = new LinkedHashMap<>(this.demand);
        this.stock = stock;
        this.external = external;
        this.preserve = preserve;
        this.budget = budget;
        started = budget.nodes();
        allowance = Math.min(262_144, budget.remainingWork() / 4);
        recipes.forEach(recipe -> byId.put(recipe.id(), recipe));
    }

    boolean step() {
        budget.check();
        if (complete) return true;
        if (budget.nodes() - started >= allowance) return finish("work_limit");
        if (model == null) {
            model = RecipeCountModel.region(recipes, goals, stock, external, budget);
            if (model == null) return finish("model_limit");
            bounds = new CountBounds(recipes.size(), model.constraints, budget);
        }
        if (bounds != null) {
            if (!bounds.step()) return false;
            if (bounds.blocked()) return finish("selected_counts_blocked");
            counts = bounds.lowerBounds();
            bounds.close();
            bounds = null;
            // Multiple producers or a propagation cutoff can leave a lower
            // bound that is not a solution. It is never an infeasibility proof.
            for (var row : model.constraints) {
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
                }
                if (sum.compareTo(row.upper()) > 0) return finish("counts_incomplete");
            }
            countedProgram = new CountProgram<>(recipes, counts, budget);
        }
        if (countedProgram != null) {
            if (!countedProgram.step()) return false;
            SequenceSummary<K> summary = countedProgram.available() ? countedProgram.summary() : null;
            Map<K, Long> seeds = summary == null ? null : seedRequirements(summary);
            if (seeds != null) {
                result = new RegionSelection.Choice<>(countedProgram.program(), summary, BigInteger.ONE, seeds);
            }
            countedProgram.close();
            countedProgram = null;
            // Prefer a schedule with large dispatchable batches. The binary
            // program remains a verified fallback, rather than replacing a
            // readily available bulk schedule with many small alternations.
            schedule = new CountSchedule<>(model, counts, budget, true);
        }
        if (computation == null) {
            if (!schedule.step()) return false;
            if (schedule.result() == CountSchedule.Result.WITNESS) body = schedule.witness();
            else return finish("schedule_" + schedule.result() + "; funded_preview=" + (result != null));
            computation = new SummaryComputation<>(body, byId, budget);
        }
        if (!computation.step()) return false;
        SequenceSummary<K> summary = computation.result();
        boolean changed = false;
        Map<K, Long> seeds = seedRequirements(summary);
        if (seeds == null) return finish("seed_capacity");
        seeds.forEach((key, seed) -> reserve.merge(key, seed, Math::max));
        for (var entry : reserve.entrySet()) {
            K key = entry.getKey();
            BigInteger goal = demand.getOrDefault(key, BigInteger.ZERO).add(BigInteger.valueOf(entry.getValue()));
            if (BigInteger.valueOf(stock.getOrDefault(key, 0L)).add(summary.delta(key)).compareTo(goal) < 0) {
                goals.merge(key, goal, BigInteger::max);
                changed = true;
            }
        }
        // Retain a concrete funded preview while refining its seed demand.
        // A later local limit must not discard an already verified program.
        result = new RegionSelection.Choice<>(body, summary, BigInteger.ONE, Map.copyOf(reserve));
        if (changed) {
            if (++refinements >= 4) return finish("seed_refinement_limit");
            close();
            model = null;
            schedule = null;
            computation = null;
            return false;
        }
        return finish("witness");
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("region_counts", detail + "; recipes=" + recipes.size() + "; work=" + (budget.nodes() - started));
        close();
        return true;
    }

    RegionSelection.Choice<K> result() { return result; }

    private Map<K, Long> seedRequirements(SequenceSummary<K> summary) {
        Map<K, Long> seeds = new LinkedHashMap<>();
        if (preserve) for (K key : model.keys) if (!model.external.contains(key) &&
                summary.delta(key).signum() >= 0 && summary.required(key).signum() > 0) {
            budget.check();
            if (summary.required(key).compareTo(ExactAmounts.LONG_MAX) > 0) return null;
            seeds.put(key, summary.required(key).longValueExact());
        }
        return Map.copyOf(seeds);
    }

    @Override
    public void close() {
        if (bounds != null) bounds.close();
        if (schedule != null) schedule.close();
        if (countedProgram != null) countedProgram.close();
        if (model != null) model.close();
    }
}
