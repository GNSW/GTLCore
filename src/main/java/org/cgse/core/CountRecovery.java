package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional private-intermediate macro view. Failure never excludes original interleavings. */
final class CountRecovery<K> implements AutoCloseable {

    private final PlanningBudget budget;
    private final Map<String, PlanStep> bodies = new LinkedHashMap<>();
    private IntegerCountSearch<K> search;
    private final IntegerCountBranch<K> owner;
    private final Deque<Collection<GraphRecipe<K>>> candidates = new ArrayDeque<>();
    private CountRecoveryFuel<K> fuel;
    private CountRecoveryTemplates.Template<K> template;
    private boolean completeRetention;
    private boolean pricedFuel;
    private PlanStep witness;
    private long memory;
    private long viewWork, viewAllowance, viewMaximum;

    private record Signature<K>(Map<K, Long> inputs, Map<K, Long> outputs) {}

    CountRecovery(IntegerCountBranch<K> branch) {
        owner = branch;
        budget = branch.budget;
        try {
            prepare();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void prepare() {
        var branch = owner;
        var model = branch.model;
        long entries = model.recipes.stream().mapToLong(r -> r.inputs().size() + r.outputs().size()).sum();
        long bytes = 2048 + 512L * model.recipes.size() + 384L * entries;
        if (!budget.tryReserve(bytes)) return;
        memory = bytes;
        Map<String, GraphRecipe<K>> compiled = new LinkedHashMap<>();
        template = model.recoveryTemplates == null ? null : model.recoveryTemplates.reuse(model);
        if (template != null) {
            for (var recipe : template.recipes) {
                budget.check();
                compiled.put(recipe.id(), recipe);
            }
            bodies.putAll(template.bodies);
            if (template.retained.size() < template.recipes.size()) candidates.addLast(template.retained);
            candidates.addLast(template.recipes);
            budget.note("count_recovery_cache", "reused; recipes=" + compiled.size() + "; macros=" + bodies.size() + "; structural_only");
            prepareInterfaces(compiled, template.recipes, budget.threadWork(), Math.min(262144, budget.remainingWork() / 16));
            return;
        }
        Set<Signature<K>> signatures = new HashSet<>();
        int aliases = 0;
        for (var recipe : model.recipes) {
            budget.check();
            if (ordinary(recipe) && !signatures.add(new Signature<>(recipe.inputs(), recipe.outputs()))) aliases++;
            else compiled.put(recipe.id(), recipe);
        }
        // Preparation stays on this branch's worker; unrelated workers still
        // consume the shared order budget, but not this optional view's quota.
        long started = budget.threadWork(), allowance = Math.min(262144, budget.remainingWork() / 16);
        int stages = 0;
        boolean completeDiscovery = false;
        // Contract only private seams. Joint outputs remain on the interface;
        // every consumer/exit is retained, including destructive exits.
        for (int pass = 0; pass < 32 && budget.threadWork() - started < allowance; pass++) {
            Map<K, List<GraphRecipe<K>>> producers = new HashMap<>(), consumers = new HashMap<>();
            for (var recipe : compiled.values()) {
                budget.check();
                recipe.inputs().keySet().forEach(key -> consumers.computeIfAbsent(key, unused -> new ArrayList<>()).add(recipe));
                recipe.outputs().keySet().forEach(key -> producers.computeIfAbsent(key, unused -> new ArrayList<>()).add(recipe));
            }
            boolean changed = false;
            // Contract internal production stages before a joint-output return.
            // Otherwise a catalyst with one producer can rotate a whole route
            // into "return; start", hiding its funded forward entry point.
            var forwardStages = new ArrayList<>(compiled.values());
            forwardStages.sort(Comparator.comparingInt(recipe -> recipe.outputs().size()));
            for (var start : forwardStages) {
                budget.check();
                if (budget.threadWork() - started >= allowance) break;
                if (!ordinary(start) || !compiled.containsKey(start.id())) continue;
                for (K pending : start.outputs().keySet()) {
                    budget.check();
                    // Existing intermediate stock does not invalidate a complete
                    // call. This optional view may leave it unused; the original
                    // model still permits entry at any primitive phase.
                    if (model.goal(pending).signum() != 0 || model.external.contains(pending) ||
                            producers.get(pending).size() != 1 || start.inputs().containsKey(pending))
                        continue;
                    var exits = consumers.getOrDefault(pending, List.of());
                    if (exits.isEmpty() || exits.size() > 16 || exits.stream().anyMatch(exit -> !ordinary(exit) || exit == start ||
                            !compiled.containsKey(exit.id()) || exit.outputs().containsKey(pending)))
                        continue;
                    // The verified prefix summary also applies to an acyclic
                    // private production chain. Requiring a return path here
                    // left split sources uncompiled and amplified alias choices.
                    // This optional view still leaves every original phase and
                    // interleaving available to the caller.
                    var macros = new ArrayList<GraphRecipe<K>>();
                    var programs = new ArrayList<PlanStep>();
                    for (var exit : exits) {
                        budget.check();
                        long outputUnits = start.outputs().get(pending), inputUnits = exit.inputs().get(pending);
                        long divisor = BigInteger.valueOf(outputUnits).gcd(BigInteger.valueOf(inputUnits)).longValueExact();
                        long starts = inputUnits / divisor, finishes = outputUnits / divisor;
                        SequenceSummary<K> summary = SequenceSummary.recipe(start).repeat(starts).then(SequenceSummary.recipe(exit).repeat(finishes));
                        Map<K, Long> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
                        boolean large = false;
                        for (K key : summary.keys()) {
                            BigInteger need = summary.required(key), output = need.add(summary.delta(key));
                            if (need.compareTo(ExactAmounts.LONG_MAX) > 0 || output.compareTo(ExactAmounts.LONG_MAX) > 0) {
                                large = true;
                                break;
                            }
                            if (need.signum() > 0) inputs.put(key, need.longValueExact());
                            if (output.signum() > 0) outputs.put(key, output.longValueExact());
                        }
                        if (large || outputs.isEmpty()) break;
                        String id = "@recovery/" + stages + "/" + macros.size();
                        while (compiled.containsKey(id)) id += "/";
                        macros.add(new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs));
                        PlanStep first = bodies.getOrDefault(start.id(), new PlanStep.Batch(start.id(), 1));
                        PlanStep last = bodies.getOrDefault(exit.id(), new PlanStep.Batch(exit.id(), 1));
                        programs.add(new PlanStep.Sequence(List.of(PlanStep.repeat(first, BigInteger.valueOf(starts)), PlanStep.repeat(last, BigInteger.valueOf(finishes)))));
                    }
                    if (macros.size() != exits.size()) continue;
                    compiled.remove(start.id());
                    for (int i = 0; i < exits.size(); i++) {
                        compiled.remove(exits.get(i).id());
                        compiled.put(macros.get(i).id(), macros.get(i));
                        bodies.put(macros.get(i).id(), programs.get(i));
                    }
                    stages++;
                    changed = true;
                    break;
                }
                // Disjoint seams can contract in the same pass. A stale incidence
                // that mentions a removed recipe is skipped until the next pass.
            }
            if (!changed) {
                completeDiscovery = budget.threadWork() - started < allowance;
                break;
            }
        }
        if (bodies.isEmpty() && aliases == 0) return;
        budget.note("count_recovery", "recipes=" + model.recipes.size() + "->" + compiled.size() + "; macros=" + bodies.size() + "; aliases=" + aliases);
        // Every exit, including destructive ones, stays available. The macro
        // summary retains the real prefix seed requirement. It is a candidate
        // representation only: other interleavings remain in the caller.
        var retained = retainingView(compiled.values());
        // Never freeze a budget-truncated discovery (including a partial exit
        // ranking) as the only cached representation for later larger orders.
        if (completeDiscovery && completeRetention && model.recoveryTemplates != null)
            template = model.recoveryTemplates.remember(model, compiled.values(), retained, bodies);
        List<GraphRecipe<K>> complete = template == null ? List.copyOf(compiled.values()) : template.recipes;
        if (template != null) retained = template.retained;
        if (retained.size() < compiled.size()) candidates.addLast(retained);
        candidates.addLast(complete);
        prepareInterfaces(compiled, complete, started, allowance);
    }

    private void prepareInterfaces(Map<String, GraphRecipe<K>> compiled, List<GraphRecipe<K>> complete, long started, long allowance) {
        // Optional cross-route calls can obscure the small independent choice
        // model. Keep them in a later view, after complete recovery calls.
        int completeSize = compiled.size();
        fuel = CountRecoveryFuel.compile(owner.model, complete, budget);
        addOpenInterfaces(compiled, started, allowance);
        if (compiled.size() > completeSize) candidates.addLast(compiled.values());
        begin(fuel == null ? candidates.removeFirst() : fuel.recipes());
    }

    /** Prefer equal-cost returning exits, without excluding any original plan. */
    private Collection<GraphRecipe<K>> retainingView(Collection<GraphRecipe<K>> recipes) {
        completeRetention = true;
        Map<Map<K, Long>, List<GraphRecipe<K>>> groups = new LinkedHashMap<>();
        for (var recipe : recipes) if (ordinary(recipe)) groups.computeIfAbsent(recipe.inputs(), unused -> new ArrayList<>()).add(recipe);
        Set<String> omitted = new HashSet<>();
        long work = 0, allowance = Math.min(32768, budget.remainingWork() / 32);
        for (var group : groups.values()) for (var first : group) for (var other : group) {
            budget.check();
            if (++work > allowance) {
                completeRetention = false;
                return recipes.stream().filter(recipe -> !omitted.contains(recipe.id())).toList();
            }
            if (first == other || omitted.contains(other.id()) || first.outputs().equals(other.outputs())) continue;
            if (first.outputs().entrySet().stream().allMatch(e -> other.outputs().getOrDefault(e.getKey(), 0L) >= e.getValue())) {
                omitted.add(first.id());
                break;
            }
        }
        if (!omitted.isEmpty()) budget.note("count_recovery", "returning_candidate; optional_exits_deferred=" + omitted.size());
        return recipes.stream().filter(recipe -> !omitted.contains(recipe.id())).toList();
    }

    /** Public intermediate inventories keep primitive phase entry/exit points as well as complete calls. */
    private void addOpenInterfaces(Map<String, GraphRecipe<K>> compiled, long started, long allowance) {
        Map<K, List<GraphRecipe<K>>> consumers = new HashMap<>();
        List<GraphRecipe<K>> originals = List.copyOf(compiled.values());
        originals.forEach(recipe -> recipe.inputs().keySet().forEach(key -> consumers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(recipe)));
        Set<String> pairs = new HashSet<>();
        for (var first : originals) {
            if (!ordinary(first)) continue;
            for (K seam : first.outputs().keySet()) {
                if (owner.model.stock.getOrDefault(seam, 0L) == 0 && owner.model.goal(seam).signum() == 0 &&
                        consumers.getOrDefault(seam, List.of()).size() < 2)
                    continue;
                if (first.inputs().containsKey(seam)) continue;
                for (var last : consumers.getOrDefault(seam, List.of())) {
                    budget.check();
                    if (pairs.size() >= 32 || budget.threadWork() - started >= allowance) return;
                    if (last == first || !ordinary(last) || last.outputs().containsKey(seam) ||
                            last.outputs().keySet().stream().noneMatch(first.inputs()::containsKey) || !pairs.add(first.id() + "\n" + last.id()))
                        continue;
                    long produced = first.outputs().get(seam), consumed = last.inputs().get(seam);
                    long gcd = BigInteger.valueOf(produced).gcd(BigInteger.valueOf(consumed)).longValueExact();
                    long starts = consumed / gcd, finishes = produced / gcd;
                    SequenceSummary<K> summary = SequenceSummary.recipe(first).repeat(starts).then(SequenceSummary.recipe(last).repeat(finishes));
                    Map<K, Long> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
                    boolean fits = true;
                    for (K key : summary.keys()) {
                        BigInteger input = summary.required(key), output = input.add(summary.delta(key));
                        if (input.compareTo(ExactAmounts.LONG_MAX) > 0 || output.compareTo(ExactAmounts.LONG_MAX) > 0) {
                            fits = false;
                            break;
                        }
                        if (input.signum() > 0) inputs.put(key, input.longValueExact());
                        if (output.signum() > 0) outputs.put(key, output.longValueExact());
                    }
                    if (!fits || outputs.isEmpty()) continue;
                    String id = "@recovery/interface/" + pairs.size();
                    while (compiled.containsKey(id)) id += "/";
                    PlanStep start = bodies.getOrDefault(first.id(), new PlanStep.Batch(first.id(), 1));
                    PlanStep end = bodies.getOrDefault(last.id(), new PlanStep.Batch(last.id(), 1));
                    bodies.put(id, new PlanStep.Sequence(List.of(PlanStep.repeat(start, BigInteger.valueOf(starts)), PlanStep.repeat(end, BigInteger.valueOf(finishes)))));
                    compiled.put(id, new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs));
                }
            }
        }
        if (!pairs.isEmpty()) budget.note("count_recovery_interfaces", "optional_calls=" + pairs.size() + "; primitive_phases_retained");
    }

    private void begin(Collection<GraphRecipe<K>> recipes) {
        viewWork = 0;
        viewAllowance = pricedFuel && fuel != null ? Math.min(4_000_000, budget.remainingWork() / 4) :
                Math.min(1_000_000, budget.remainingWork() / 8);
        viewMaximum = Math.min(4_000_000, budget.remainingWork() / 4);
        GraphCompiler<K> compiler = template == null ? null : template.compiler(recipes);
        if (compiler == null) compiler = new GraphCompiler<>(List.copyOf(recipes));
        search = new IntegerCountSearch<>(compiler, owner.target, owner.amount,
                pricedFuel && fuel != null ? fuel.pricedStock() : owner.stock, owner.seeds, owner.external, Set.of(), owner.preserve, owner.force, budget, owner.started, null, false);
        if (fuel == null) search.importProgramConflicts(owner.model, bodies, owner.knownChoices());
    }

    private static boolean ordinary(GraphRecipe<?> recipe) {
        return recipe.configurationInputs().isEmpty() && recipe.reusableInputs().isEmpty();
    }

    boolean step() {
        if (search == null) return true;
        long before = budget.threadWork();
        boolean done = false;
        if (viewWork < viewAllowance) done = search.step();
        viewWork += budget.threadWork() - before;
        if (search.hasIndependentProgress()) {
            if (viewWork >= viewAllowance && viewAllowance < viewMaximum) {
                viewAllowance = Math.min(viewMaximum, viewAllowance + 1_000_000);
                budget.note("count_recovery", "independent_components_progress; allowance=" + viewAllowance);
            }
            if (done && search.paused() && viewWork < viewAllowance) {
                search.resume();
                done = false;
            }
        }
        if (pricedFuel && fuel != null && done && search.paused() && viewWork < viewAllowance) {
            search.resume();
            done = false;
        }
        if (!done && viewWork < viewAllowance) return false;
        if (!done) budget.note("count_recovery", "candidate_work_limit; work=" + viewWork + "; alternatives=" + candidates.size());
        var plan = search.result();
        if (plan != null && plan.feasible()) {
            PlanStep candidate = fuel == null ? plan.steps() : fuel.lift(plan, bytes -> memory += bytes);
            if (candidate != null) witness = PlanRewrite.batches(candidate, batch -> bodies.containsKey(batch.recipe()) ?
                    PlanStep.repeat(bodies.get(batch.recipe()), BigInteger.valueOf(batch.runs())) : batch, budget, bytes -> memory += bytes);
            budget.note("count_recovery", (witness != null ? "lifted_witness" : "account_allocation_unresolved") + "; macros=" + bodies.size());
        }
        search.close();
        search = null;
        if (witness == null && fuel != null && !pricedFuel) {
            pricedFuel = true;
            begin(fuel.pricedRecipes());
            return false;
        }
        if (witness == null && !candidates.isEmpty()) {
            fuel = null;
            begin(candidates.removeFirst());
            return false;
        }
        return true;
    }

    PlanStep witness() {
        return witness;
    }

    boolean hasIndependentProgress() {
        return search != null && search.hasIndependentProgress();
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        budget.release(memory);
        memory = 0;
    }
}
