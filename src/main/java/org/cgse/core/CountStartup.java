package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Separates a necessary startup implication from one candidate support. */
final class CountStartup<K> {

    record Proof<K>(int recipe, Set<K> unbounded, Set<Integer> repairs) {

        Proof {
            unbounded = Set.copyOf(unbounded);
            repairs = Set.copyOf(repairs);
        }

        CountGuard guard() {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            repairs.stream().sorted().forEach(i -> terms.put(i, BigInteger.ONE.negate()));
            return new CountGuard(recipe, new ExactLinearProgram.Constraint(terms, BigInteger.ONE.negate()));
        }
    }

    private static final class Stopped extends RuntimeException {

        private static final Stopped INSTANCE = new Stopped();

        private Stopped() {
            super(null, null, false, false);
        }
    }

    private final RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final List<Proof<K>> learned = new ArrayList<>();
    private long memory;

    private CountStartup(RecipeCountModel<K> model, PlanningBudget budget) {
        this.model = model;
        this.budget = budget;
        started = budget.threadWork();
        allowance = Math.min(65_536, budget.remainingWork() / 16);
    }

    static <K> Proof<K> separate(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget) {
        var proofs = separate(model, counts, budget, 1);
        return proofs.isEmpty() ? null : proofs.get(0);
    }

    /** One invariant can explain several blocked alternatives without repeating its closure. */
    static <K> List<Proof<K>> separateAll(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget) {
        return separate(model, counts, budget, 32);
    }

    private static <K> List<Proof<K>> separate(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget, int maximum) {
        var separating = new CountStartup<>(model, budget);
        try {
            if (separating.allowance >= 512) separating.run(counts, maximum);
        } catch (Stopped ignored) {
            // An unfinished closure supplies no cut. Once its invariant is
            // checked, stopping an optional sibling explanation keeps only the
            // already checked (and, when requested, archived) explanations.
        } finally {
            budget.release(separating.memory);
        }
        return List.copyOf(separating.learned);
    }

    private void tick() {
        if (budget.threadWork() - started >= allowance) throw Stopped.INSTANCE;
        budget.operation(PlanningBudget.Operation.SCAN, 1);
    }

    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) throw Stopped.INSTANCE;
        memory += bytes;
    }

    private void run(BigInteger[] counts, int maximum) {
        long incidences = 0;
        for (var recipe : model.recipes) {
            tick();
            incidences += recipe.inputs().size() + (long) recipe.outputs().size();
        }
        reserve(1024L + 48L * counts.length + 128L * model.keys.size() + 96L * incidences);
        int[] waiting = new int[counts.length];
        Arrays.fill(waiting, -1);
        Map<K, List<Integer>> consumers = new HashMap<>();
        Deque<Integer> ready = new ArrayDeque<>();
        Set<K> unbounded = new HashSet<>();
        for (int i = 0; i < counts.length; i++) {
            tick();
            if (counts[i].signum() == 0) continue;
            int missing = 0;
            for (var input : model.recipes.get(i).inputs().entrySet()) {
                tick();
                if (!available(input.getKey(), input.getValue(), unbounded)) {
                    missing++;
                    consumers.computeIfAbsent(input.getKey(), ignored -> new ArrayList<>()).add(i);
                }
            }
            waiting[i] = missing;
            if (missing == 0) ready.addLast(i);
        }
        while (!ready.isEmpty()) {
            tick();
            var recipe = model.recipes.get(ready.removeFirst());
            for (K key : recipe.outputs().keySet()) {
                tick();
                if (model.external.contains(key) || !increases(recipe, key) || !unbounded.add(key)) continue;
                for (int next : consumers.getOrDefault(key, List.of())) {
                    tick();
                    if (--waiting[next] == 0) ready.addLast(next);
                }
            }
        }
        int blocked = -1;
        for (int i = 0; i < waiting.length; i++) {
            tick();
            if (waiting[i] > 0) {
                blocked = i;
                break;
            }
        }
        if (blocked < 0) return;
        Set<Integer> repairs = new LinkedHashSet<>();
        for (int i = 0; i < model.recipes.size(); i++) {
            tick();
            if (exits(model.recipes.get(i), unbounded)) repairs.add(i);
        }
        var proof = new Proof<>(blocked, unbounded, repairs);
        // Check the invariant against ALL recipes, independently of the
        // candidate's reachability queue. Counts do not appear in this proof.
        if (!verify(proof)) return;
        for (int repair : repairs) {
            tick();
            if (counts[repair].signum() != 0) return;
        }
        if (!accept(proof)) return;
        // Keep the extra shared clauses small even when this box has many
        // repair alternatives. The mandatory first explanation is unchanged.
        maximum = Math.min(maximum, Math.max(1, 256 / (repairs.size() + 1)));
        // The verified box is invariant without any repair, independently of
        // the candidate counts. Every other transition disabled in that box
        // therefore needs a repair too, including currently unchosen sources.
        // This does not forbid a support merely because its schedule timed out.
        for (int i = 0; i < model.recipes.size() && learned.size() < maximum; i++) {
            tick();
            if (i != blocked && !enabled(model.recipes.get(i), proof.unbounded))
                if (!accept(new Proof<>(i, proof.unbounded, proof.repairs))) break;
        }
        budget.note("count_startup", "quantity_box; recipe=" + blocked + "; repairs=" + repairs.size() +
                "; learned=" + learned.size() + "; work=" + (budget.threadWork() - started));
    }

    private boolean accept(Proof<K> proof) {
        if (budget.proofJournal() != null && !archive(proof)) return false;
        learned.add(proof);
        return true;
    }

    private boolean available(K key, long amount, Set<K> unbounded) {
        return model.external.contains(key) || unbounded.contains(key) || model.stock.getOrDefault(key, 0L) >= amount;
    }

    private static <K> boolean increases(GraphRecipe<K> recipe, K key) {
        // Configuration charged per push is ignored in the gain, but its
        // entire first batch is still required before a recipe is enabled.
        long consumed = recipe.inputs().getOrDefault(key, 0L) - recipe.configurationInputs().getOrDefault(key, 0L) + recipe.reusableInputs().getOrDefault(key, 0L);
        return recipe.outputs().getOrDefault(key, 0L) > consumed;
    }

    private boolean enabled(GraphRecipe<K> recipe, Set<K> unbounded) {
        for (var input : recipe.inputs().entrySet()) {
            tick();
            if (!available(input.getKey(), input.getValue(), unbounded)) return false;
        }
        return true;
    }

    private boolean exits(GraphRecipe<K> recipe, Set<K> unbounded) {
        if (!enabled(recipe, unbounded)) return false;
        for (K key : recipe.outputs().keySet()) {
            tick();
            if (!model.external.contains(key) && !unbounded.contains(key) && increases(recipe, key)) return true;
        }
        return false;
    }

    private boolean verify(Proof<K> proof) {
        if (proof.recipe < 0 || proof.recipe >= model.recipes.size() ||
                enabled(model.recipes.get(proof.recipe), proof.unbounded))
            return false;
        for (int i = 0; i < model.recipes.size(); i++) {
            tick();
            // Without a repair every enabled transition stays inside the box.
            // The blocked recipe needs a marking outside it before its first use.
            if (!proof.repairs.contains(i) && exits(model.recipes.get(i), proof.unbounded)) return false;
        }
        return true;
    }

    static <K> boolean verify(RecipeCountModel<K> model, Proof<K> proof, PlanningBudget budget) {
        var checking = new CountStartup<>(model, budget);
        try {
            for (int repair : proof.repairs) if (repair < 0 || repair >= model.recipes.size()) return false;
            return checking.verify(proof);
        } catch (Stopped ignored) {
            return false;
        }
    }

    private boolean archive(Proof<K> proof) {
        long cells = (long) model.keys.size() * (model.recipes.size() - proof.repairs.size() + 2);
        if (cells > 32768) return false;
        reserve(512L + 96L * cells);
        List<BigInteger> initial = new ArrayList<>(), goal = new ArrayList<>();
        List<List<BigInteger>> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        Set<Integer> unlimited = new LinkedHashSet<>();
        for (int k = 0; k < model.keys.size(); k++) {
            tick();
            K key = model.keys.get(k);
            initial.add(BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
            goal.add(BigInteger.valueOf(model.recipes.get(proof.recipe).inputs().getOrDefault(key, 0L)));
            if (proof.unbounded.contains(key) || model.external.contains(key)) unlimited.add(k);
        }
        for (int i = 0; i < model.recipes.size(); i++) if (!proof.repairs.contains(i)) {
            var recipe = model.recipes.get(i);
            List<BigInteger> in = new ArrayList<>(), out = new ArrayList<>();
            for (K key : model.keys) {
                tick();
                in.add(BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L)));
                out.add(BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)).add(BigInteger.valueOf(
                        recipe.configurationInputs().getOrDefault(key, 0L) - recipe.reusableInputs().getOrDefault(key, 0L))));
            }
            inputs.add(in);
            outputs.add(out);
        }
        var certificate = new ExecutionProof.Certificate("startup_cut:recipe=" + proof.recipe + "; zero_repairs=" + proof.repairs,
                ExecutionProof.Kind.STARTUP_BOX, initial, goal, inputs, outputs, List.of(), unlimited);
        long remaining = Math.max(0, allowance - (budget.threadWork() - started));
        if (ExecutionProof.verify(certificate, remaining, budget::charge) != CountProof.Verdict.VERIFIED) return false;
        budget.proofJournal().add(certificate);
        return true;
    }
}
