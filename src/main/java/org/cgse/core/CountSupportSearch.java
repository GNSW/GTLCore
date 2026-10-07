package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Small support reachability aid; only an independently checked closed set is a cut. */
final class CountSupportSearch<K> implements AutoCloseable {

    enum Result {
        WITNESS,
        CLOSED,
        UNKNOWN
    }

    private record Node(List<BigInteger> marking, Node parent, int recipe, BigInteger gap, int depth) {}

    private final RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private final List<Integer> support = new ArrayList<>();
    private final Map<List<BigInteger>, Node> reached = new LinkedHashMap<>();
    private Queue<Node> pending = new ArrayDeque<>();
    private final BitSet exits = new BitSet();
    private final long allowance;
    private final int stateLimit;
    private final List<K> productionKeys;
    private BigInteger[][] inputs, changes;
    private BigInteger[] goals;
    private BigInteger[] gains;
    private List<BigInteger> initial;
    private Node active;
    private int cursor;
    private Iterator<Node> validating;
    private long memory, work;
    private Result result;
    private PlanStep witness;
    private BigInteger[] counts;
    private ExactLinearProgram.Constraint cut;
    private boolean repairing;
    private int certifiedStates;
    private long distanceWork;

    CountSupportSearch(RecipeCountModel<K> model, BigInteger[] candidate, PlanningBudget budget) {
        this(model, candidate, budget, false);
    }

    static <K> CountSupportSearch<K> allSources(RecipeCountModel<K> model, PlanningBudget budget) {
        BigInteger[] counts = new BigInteger[model.recipes.size()];
        Arrays.fill(counts, BigInteger.ONE);
        return new CountSupportSearch<>(model, counts, budget, true);
    }

    private CountSupportSearch(RecipeCountModel<K> model, BigInteger[] candidate, PlanningBudget budget, boolean full) {
        this.model = model;
        this.budget = budget;
        // Support repair must satisfy physical production as well as final
        // stock. Otherwise a stocked target returns an empty "witness" and
        // permanently finishes this arm before it explores any firing.
        productionKeys = List.copyOf(model.productionGoals.keySet());
        allowance = Math.min(full ? 65_536 : 16_384, budget.remainingWork() / 16);
        stateLimit = full ? 4096 : 1024;
        for (int i = 0; i < candidate.length; i++) if (candidate[i].signum() > 0) support.add(i);
        if (model.keys.size() > 16 || support.size() > (full ? 32 : 16) || allowance < 512 ||
                model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) {
            result = Result.UNKNOWN;
            return;
        }
        int dimensions = model.keys.size() + productionKeys.size();
        long bytes = (long) stateLimit * (256 + 96L * dimensions);
        if (!budget.tryReserve(bytes)) {
            result = Result.UNKNOWN;
            return;
        }
        memory = bytes;
        long preparationStarted = budget.threadWork();
        try {
            inputs = new BigInteger[support.size()][dimensions];
            changes = new BigInteger[support.size()][dimensions];
            goals = new BigInteger[dimensions];
            var stock = new ArrayList<BigInteger>();
            for (int k = 0; k < model.keys.size(); k++) {
                K key = model.keys.get(k);
                boolean supplied = model.external.contains(key);
                stock.add(supplied ? BigInteger.ZERO : BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
                goals[k] = supplied ? BigInteger.ZERO : model.goal(key);
                for (int i = 0; i < support.size(); i++) {
                    budget.check();
                    var recipe = model.recipes.get(support.get(i));
                    inputs[i][k] = supplied ? BigInteger.ZERO : BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L));
                    changes[i][k] = supplied ? BigInteger.ZERO : RecipeCountModel.delta(recipe, key);
                }
            }
            for (K key : productionKeys) {
                int k = stock.size();
                goals[k] = model.productionGoals.get(key);
                for (int i = 0; i < support.size(); i++) {
                    budget.check();
                    inputs[i][k] = BigInteger.ZERO;
                    changes[i][k] = BigInteger.valueOf(model.recipes.get(support.get(i)).executionOutputs().getOrDefault(key, 0L));
                }
                stock.add(BigInteger.ZERO);
            }
            initial = List.copyOf(stock);
            Node root = new Node(initial, null, -1, BigInteger.ZERO, 0);
            reached.put(initial, root);
            pending.add(root);
            if (full) startRepair();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            work += budget.threadWork() - preparationStarted;
        }
    }

    boolean step() {
        if (result != null) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) return finish(cut == null ? Result.UNKNOWN : Result.CLOSED);
            if (active == null) {
                if (validating != null) {
                    if (!validating.hasNext()) {
                        Map<Integer, BigInteger> outside = new LinkedHashMap<>();
                        for (int i = exits.nextSetBit(0); i >= 0; i = exits.nextSetBit(i + 1)) outside.put(i, BigInteger.ONE.negate());
                        cut = new ExactLinearProgram.Constraint(outside, BigInteger.ONE.negate());
                        certifiedStates = reached.size();
                        return finish(Result.CLOSED);
                    }
                    active = validating.next();
                    if (materialGoal(active.marking())) throw new IllegalStateException("Invalid support certificate goal");
                } else {
                    if (pending.isEmpty()) {
                        // Capped production progress is not a material arc.
                        // Only project it away when the resulting material set
                        // still excludes every goal; then independently check
                        // closure using the original recipe inputs/outputs.
                        if (!productionKeys.isEmpty() && !projectMaterials()) return finish(Result.UNKNOWN);
                        // A second pass checks the certificate independently of
                        // discovery order and the candidate recipe counts.
                        if (!reached.containsKey(initial)) throw new IllegalStateException("Invalid support certificate initial");
                        validating = reached.values().iterator();
                        return false;
                    }
                    active = pending.remove();
                    if (goal(active.marking())) {
                        var path = new ArrayList<PlanStep>();
                        counts = new BigInteger[model.recipes.size()];
                        Arrays.fill(counts, BigInteger.ZERO);
                        for (Node n = active; n.parent() != null; n = n.parent()) {
                            int recipe = support.get(n.recipe());
                            path.add(PlanStep.batch(model.recipes.get(recipe).id(), BigInteger.ONE));
                            counts[recipe] = counts[recipe].add(BigInteger.ONE);
                        }
                        Collections.reverse(path);
                        witness = new PlanStep.Sequence(path);
                        return finish(Result.WITNESS);
                    }
                }
                cursor = 0;
            }
            if (cursor == (validating == null ? support.size() : model.recipes.size())) {
                active = null;
                return false;
            }
            int recipe = cursor++;
            List<BigInteger> next = validating == null ? successor(active.marking(), recipe) : checkedSuccessor(active.marking(), recipe);
            if (next == null) return false;
            if (validating != null) {
                if (!reached.containsKey(next)) {
                    if (support.contains(recipe)) throw new IllegalStateException("Invalid support certificate boundary");
                    exits.set(recipe);
                }
            } else if (!reached.containsKey(next)) {
                if (reached.size() == stateLimit) return finish(cut == null ? Result.UNKNOWN : Result.CLOSED);
                Node node = new Node(next, active, recipe, repairing ? distance(next) : BigInteger.ZERO, active.depth() + 1);
                reached.put(next, node);
                pending.add(node);
            }
            return false;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private List<BigInteger> successor(List<BigInteger> marking, int recipe) {
        var next = new ArrayList<BigInteger>();
        for (int k = 0; k < goals.length; k++) {
            budget.check();
            if (marking.get(k).compareTo(inputs[recipe][k]) < 0) return null;
            BigInteger value = marking.get(k).add(changes[recipe][k]);
            next.add(k < model.keys.size() ? value : value.min(goals[k]));
        }
        return List.copyOf(next);
    }

    private List<BigInteger> checkedSuccessor(List<BigInteger> marking, int recipeId) {
        // Verify with original recipe arcs, independently of the cached delta
        // arrays used by exploration. Every successful execution must leave the
        // checked set on one of these exact boundary transitions. Recipes that
        // are never enabled here cannot repair its missing startup sequence.
        var recipe = model.recipes.get(recipeId);
        for (var input : recipe.inputs().entrySet()) if (!model.external.contains(input.getKey())) {
            budget.check();
            if (marking.get(model.ids.get(input.getKey())).compareTo(BigInteger.valueOf(input.getValue())) < 0) return null;
        }
        var next = new ArrayList<>(marking);
        for (var input : recipe.inputs().entrySet()) if (!model.external.contains(input.getKey())) {
            budget.check();
            int key = model.ids.get(input.getKey());
            next.set(key, next.get(key).subtract(BigInteger.valueOf(input.getValue())));
        }
        for (var output : recipe.outputs().entrySet()) if (!model.external.contains(output.getKey())) {
            budget.check();
            Integer key = model.ids.get(output.getKey());
            if (key != null) next.set(key, next.get(key).add(BigInteger.valueOf(output.getValue())));
        }
        return List.copyOf(next);
    }

    private void startRepair() {
        // Owned by one request-level continuation, not restarted for each dead
        // count vector. Its marking cache and unused allowance survive slices.
        repairing = true;
        support.clear();
        for (int i = 0; i < model.recipes.size(); i++) support.add(i);
        inputs = new BigInteger[support.size()][goals.length];
        changes = new BigInteger[support.size()][goals.length];
        gains = new BigInteger[goals.length];
        Arrays.fill(gains, BigInteger.ONE);
        for (int i = 0; i < support.size(); i++) for (int k = 0; k < model.keys.size(); k++) {
            budget.check();
            K key = model.keys.get(k);
            inputs[i][k] = model.external.contains(key) ? BigInteger.ZERO : BigInteger.valueOf(model.recipes.get(i).inputs().getOrDefault(key, 0L));
            changes[i][k] = model.external.contains(key) ? BigInteger.ZERO : RecipeCountModel.delta(model.recipes.get(i), key);
            gains[k] = gains[k].max(changes[i][k]);
        }
        for (int i = 0; i < support.size(); i++) for (int p = 0; p < productionKeys.size(); p++) {
            budget.check();
            int k = model.keys.size() + p;
            inputs[i][k] = BigInteger.ZERO;
            changes[i][k] = BigInteger.valueOf(model.recipes.get(i).executionOutputs().getOrDefault(productionKeys.get(p), 0L));
            gains[k] = gains[k].max(changes[i][k]);
        }
        for (int p = 0; p < productionKeys.size(); p++) {
            int k = model.keys.size() + p;
            budget.check();
            // This aid explores individual firings, with at most stateLimit-1
            // edges in a simple witness path. Huge repeated orders belong to
            // the batched solvers, not an inevitably exhausted marking search.
            if (goals[k].compareTo(gains[k].multiply(BigInteger.valueOf(stateLimit - 1L))) > 0) {
                result = Result.UNKNOWN;
                return;
            }
        }
        reached.clear();
        pending = new PriorityQueue<>(Comparator.<Node, BigInteger>comparing(Node::gap).thenComparingInt(Node::depth));
        active = null;
        validating = null;
        Node root = new Node(initial, null, -1, distance(initial), 0);
        reached.put(initial, root);
        pending.add(root);
    }

    private BigInteger distance(List<BigInteger> marking) {
        // Search order only: ignoring competition and startup, how many firings
        // would the largest single-resource gain need? Keep exact magnitudes,
        // including differences of one above the double/long precision range.
        BigInteger result = BigInteger.ZERO;
        for (int k = 0; k < goals.length; k++) {
            budget.check();
            BigInteger gap = goals[k].subtract(marking.get(k)).max(BigInteger.ZERO);
            result = result.max(CheckedAmounts.ceilDiv(gap, gains[k]));
        }
        if (!repairing || distanceWork >= allowance / 8) return result;
        // A relaxed AND/OR graph: all inputs of a producer must be reachable,
        // while any producer may supply a resource. Ignore consumption and
        // competition. These distances rank states only; an unreachable hint
        // neither prunes a state nor contributes to a closed-set certificate.
        long before = budget.threadWork();
        BigInteger[] depths = new BigInteger[goals.length];
        try {
            boolean changed = true;
            for (int round = 0; changed && round < goals.length; round++) {
                changed = false;
                for (int i = 0; i < support.size(); i++) {
                    BigInteger depth = BigInteger.ZERO;
                    for (int k = 0; k < goals.length; k++) {
                        budget.check();
                        BigInteger deficit = inputs[i][k].subtract(marking.get(k));
                        if (deficit.signum() <= 0) continue;
                        if (depths[k] == null) {
                            depth = null;
                            break;
                        }
                        depth = depth.max(depths[k].add(CheckedAmounts.ceilDiv(deficit, gains[k])));
                    }
                    if (depth != null) for (int k = 0; k < goals.length; k++) {
                        budget.check();
                        if (changes[i][k].signum() > 0 && (depths[k] == null || depth.compareTo(depths[k]) < 0)) {
                            depths[k] = depth;
                            changed = true;
                        }
                    }
                    if (distanceWork + budget.threadWork() - before >= allowance / 8) return result;
                }
            }
            for (int k = 0; k < goals.length; k++) {
                BigInteger gap = goals[k].subtract(marking.get(k));
                if (gap.signum() > 0 && depths[k] != null) result = result.max(depths[k].add(CheckedAmounts.ceilDiv(gap, gains[k])));
            }
            return result;
        } finally {
            distanceWork += budget.threadWork() - before;
        }
    }

    private boolean goal(List<BigInteger> marking) {
        for (int k = 0; k < goals.length; k++) {
            budget.check();
            if (marking.get(k).compareTo(goals[k]) < 0) return false;
        }
        return true;
    }

    private boolean materialGoal(List<BigInteger> marking) {
        for (int k = 0; k < model.keys.size(); k++) {
            budget.check();
            if (marking.get(k).compareTo(goals[k]) < 0) return false;
        }
        return true;
    }

    private boolean projectMaterials() {
        for (Node node : reached.values()) if (materialGoal(node.marking())) return false;
        var projected = new LinkedHashMap<List<BigInteger>, Node>();
        for (Node node : reached.values()) {
            budget.charge(1 + model.keys.size());
            var marking = List.copyOf(node.marking().subList(0, model.keys.size()));
            projected.putIfAbsent(marking, new Node(marking, null, -1, BigInteger.ZERO, 0));
        }
        reached.clear();
        reached.putAll(projected);
        initial = List.copyOf(initial.subList(0, model.keys.size()));
        return true;
    }

    private boolean finish(Result value) {
        if (value == Result.CLOSED && budget.proofJournal() != null) {
            List<List<BigInteger>> originalInputs = new ArrayList<>(), originalOutputs = new ArrayList<>();
            for (var recipe : model.recipes) {
                originalInputs.add(model.keys.stream().map(key -> model.external.contains(key) ? BigInteger.ZERO : BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L))).toList());
                originalOutputs.add(model.keys.stream().map(key -> model.external.contains(key) ? BigInteger.ZERO : BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))).toList());
            }
            budget.proofJournal().add(new ExecutionProof.Certificate("execution_support:requires_boundary_recipe", ExecutionProof.Kind.FORWARD_BOUNDARY,
                    initial, List.of(Arrays.copyOf(goals, model.keys.size())), originalInputs, originalOutputs, reached.keySet().stream().toList(), exits.stream().boxed().collect(java.util.stream.Collectors.toSet())));
        }
        result = value;
        budget.note("count_support", "result=" + value + "; recipes=" + support.size() + "; states=" + reached.size() +
                "; certified_states=" + certifiedStates + "; exits=" + exits.cardinality() + "; repair=" + repairing + "; work=" + work);
        close();
        return true;
    }

    Result result() {
        return result;
    }

    PlanStep witness() {
        return witness;
    }

    BigInteger[] counts() {
        return counts;
    }

    ExactLinearProgram.Constraint cut() {
        return cut;
    }

    @Override
    public void close() {
        pending.clear();
        reached.clear();
        active = null;
        validating = null;
        budget.release(memory);
        memory = 0;
    }
}
