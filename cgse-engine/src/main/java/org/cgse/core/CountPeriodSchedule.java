// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Positive-only unit-firing beam over one primitive cyclic multiset.
 * Failure leaves the ordinary fixed-count scheduler and its proof space intact. */
final class CountPeriodSchedule<K> implements AutoCloseable {
    private static final int WIDTH = 16, MAX_PERIOD = 8192, MAX_RECIPES = 512;
    // This optional candidate targets sparse cycles. Bound the indivisible
    // score/materialization work too, not just the number of beam expansions.
    private static final int MAX_PORTS = 4096, MAX_AFFECTED_INPUTS = 512, MAX_INCIDENCE = 1024;
    private static final long COMPACT_MAX = Long.MAX_VALUE / 64;
    private final RecipeCountModel<K> model;
    private final BigInteger[] counts;
    private final PlanningBudget budget;
    private final long allowance;
    private long work, stepStart, memory, candidates;
    private CountScheduleTopology<K> topology;
    private int[] components, members, tail, primitive;
    private BigInteger repetitions;
    private int phase, cursor, length, layer, parent, action, materializing;
    private boolean complete;
    private String reason = "not_started";
    private final Map<K, Integer> keyIds = new LinkedHashMap<>();
    private final Map<K, Long> minima = new HashMap<>();
    private List<K> keys;
    private Action[] actions;
    private long[] initial, minimum, changes, weights;
    private int[][] affected, consumers;
    private State[] beam, nextBeam;
    private int beamSize, selectedSize, unique;
    private int[] selected, heads, links, parents, moves, ready, blocked, traceParents, traceActions;
    private long[] hashes, reserves;
    private List<PlanStep> body;
    private PlanStep candidate, witness;
    private SummaryComputation<K> summary;
    private Iterator<Map.Entry<K, BigInteger>> requirements;

    CountPeriodSchedule(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget) {
        this.model = model; this.counts = counts; this.budget = budget;
        allowance = Math.min(20_000_000, budget.remainingWork() / 4);
        if (model.graphMode != GraphMode.FULL || counts.length > 1024 || allowance < 4096) {
            complete = true; return;
        }
        long ports = 0;
        for (int i = 0; i < counts.length; i++) if (counts[i].signum() > 0) {
            budget.check();
            var recipe = model.recipes.get(i);
            if (!recipe.configurationInputs().isEmpty()) { complete = true; return; }
            ports += recipe.inputs().size() + (long) recipe.outputs().size();
        }
        if (ports > MAX_PORTS || !reserve(4096L + 256L * counts.length + 512L * ports)) {
            complete = true; return;
        }
        reason = "topology";
    }

    boolean step() {
        if (complete) return true;
        stepStart = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) return stop("local_work_limit");
            return advance();
        } finally { work += budget.threadWork() - stepStart; }
    }

    private boolean advance() {
        if (phase == 0) {
            if (topology == null) topology = new CountScheduleTopology<>(model, counts, budget);
            if (!topology.step()) return false;
            components = topology.result();
            if (components == null) return stop("topology_unavailable");
            if (!selectComponent()) return true;
            topology.close(); topology = null;
            phase = 1; cursor = 0;
            return false;
        }
        if (phase == 1) {
            if (cursor < members.length) {
                if (!indexAction(cursor++)) return true;
                return false;
            }
            if (!prepareBeam()) return true;
            phase = 2; cursor = 0;
            return false;
        }
        if (phase == 2) {
            // Incremental dependency scores: only consumers of changed ports
            // can become enabled/blocked after a unit firing.
            if (cursor < actions.length) {
                int id = cursor++;
                var touched = new BitSet(actions.length); touched.set(id);
                int incidence = 0, inputs = actions[id].inputs.length;
                for (int key : actions[id].keys) for (int r : consumers[key]) {
                    scan(1);
                    if (++incidence > MAX_INCIDENCE) return stop("dense_action");
                    if (!touched.get(r)) {
                        inputs += actions[r].inputs.length;
                        if (inputs > MAX_AFFECTED_INPUTS) return stop("dense_action");
                        touched.set(r);
                    }
                }
                affected[id] = touched.stream().toArray();
                return false;
            }
            phase = 3;
            return false;
        }
        if (phase == 3) {
            long start = budget.threadWork();
            for (int turn = 0; turn < 32 && budget.threadWork() - start < 256; turn++) {
                if (parent == beamSize) { phase = 4; materializing = 0; return false; }
                State state = beam[parent];
                int r = action++;
                if (state.remaining[r] > 0 && state.missing[r] == 0) offer(parent, r);
                else scan(1);
                if (action == actions.length) { parent++; action = 0; }
            }
            return false;
        }
        if (phase == 4) {
            if (selectedSize == 0) return stop("beam_exhausted");
            if (materializing < selectedSize) { materialize(materializing++); return false; }
            State[] swap = beam; beam = nextBeam; nextBeam = swap;
            Arrays.fill(nextBeam, null); beamSize = selectedSize; layer++;
            if (layer == length) {
                cursor = beam[0].trace; body = new ArrayList<>(); phase = 5;
            } else {
                scan(heads.length); Arrays.fill(heads, -1);
                selectedSize = unique = parent = action = 0; phase = 3;
            }
            return false;
        }
        if (phase == 5) {
            // Parent links are bounded by width * period length, not by the
            // total order quantity. Reconstruct in charged continuations.
            if (cursor >= 0) {
                body.add(new PlanStep.Batch(model.recipes.get(members[traceActions[cursor]]).id(), 1));
                cursor = traceParents[cursor]; return false;
            }
            scan(body.size()); Collections.reverse(body);
            var program = new ArrayList<PlanStep>();
            program.add(PlanStep.repeat(new PlanStep.Sequence(body), repetitions));
            for (int id : tail) { budget.check(); program.add(PlanStep.batch(model.recipes.get(id).id(), counts[id])); }
            candidate = new PlanStep.Sequence(program);
            var recipes = new LinkedHashMap<String, GraphRecipe<K>>();
            for (int i = 0; i < counts.length; i++) if (counts[i].signum() > 0) {
                budget.check(); recipes.put(model.recipes.get(i).id(), model.recipes.get(i));
            }
            summary = new SummaryComputation<>(candidate, recipes, budget); phase = 6;
            return false;
        }
        if (phase == 6) {
            if (!summary.step()) return false;
            requirements = summary.result().required().entrySet().iterator(); phase = 7;
            return false;
        }
        if (requirements.hasNext()) {
            var need = requirements.next();
            if (!model.external.contains(need.getKey()) &&
                    need.getValue().compareTo(PlanningInventory.exactAmount(model.stock, need.getKey())) > 0)
                return stop("complete_repeat_unfunded");
            return false;
        }
        witness = candidate;
        return stop("verified_repeat");
    }

    private boolean selectComponent() {
        int cyclic = -1;
        for (int i = 0; i < counts.length; i++) if (counts[i].signum() > 0) {
            for (K input : model.recipes.get(i).inputs().keySet()) {
                budget.check();
                if (!model.external.contains(input) && topology.component(input) == components[i]) {
                    if (cyclic != -1 && cyclic != components[i]) { stop("multiple_cycles"); return false; }
                    cyclic = components[i];
                }
            }
        }
        if (cyclic == -1) { stop("acyclic_multiset"); return false; }
        var cycle = new ArrayList<Integer>(); var after = new ArrayList<Integer>();
        repetitions = BigInteger.ZERO;
        for (int i = 0; i < counts.length; i++) if (counts[i].signum() > 0) {
            budget.check();
            if (components[i] < cyclic) { stop("upstream_prefix"); return false; }
            if (components[i] == cyclic) { cycle.add(i); repetitions = repetitions.gcd(counts[i]); }
            else after.add(i);
        }
        if (cycle.size() > MAX_RECIPES || repetitions.compareTo(BigInteger.ONE) <= 0) { stop("no_short_period"); return false; }
        members = cycle.stream().mapToInt(Integer::intValue).toArray(); primitive = new int[members.length];
        for (int i = 0; i < members.length; i++) {
            budget.check();
            BigInteger value = counts[members[i]].divide(repetitions);
            if (value.compareTo(BigInteger.valueOf(MAX_PERIOD - length)) > 0) { stop("long_period"); return false; }
            primitive[i] = value.intValueExact(); length += primitive[i];
        }
        after.sort((a, b) -> { scan(1); return Integer.compare(components[a], components[b]); });
        tail = after.stream().mapToInt(Integer::intValue).toArray(); actions = new Action[members.length];
        return true;
    }

    private boolean indexAction(int local) {
        var recipe = model.recipes.get(members[local]);
        if (recipe.inputs().size() + recipe.outputs().size() > 32) { stop("wide_action"); return false; }
        var inputs = new ArrayList<Integer>(); var need = new ArrayList<Long>();
        var delta = new LinkedHashMap<Integer, Long>();
        for (var port : recipe.inputs().entrySet()) if (!model.external.contains(port.getKey())) {
            budget.check();
            if (port.getValue() > COMPACT_MAX) { stop("wide_quantity"); return false; }
            int id = keyIds.computeIfAbsent(port.getKey(), key -> keyIds.size());
            inputs.add(id); need.add(port.getValue()); delta.put(id, -port.getValue());
            minima.merge(port.getKey(), port.getValue(), Math::min);
        }
        for (var port : recipe.outputs().entrySet()) if (!model.external.contains(port.getKey())) {
            budget.check();
            if (port.getValue() > COMPACT_MAX) { stop("wide_quantity"); return false; }
            int id = keyIds.computeIfAbsent(port.getKey(), key -> keyIds.size());
            delta.merge(id, port.getValue(), Long::sum);
        }
        delta.values().removeIf(v -> v == 0);
        actions[local] = new Action(inputs.stream().mapToInt(Integer::intValue).toArray(),
                need.stream().mapToLong(Long::longValue).toArray(), delta.keySet().stream().mapToInt(Integer::intValue).toArray(),
                delta.values().stream().mapToLong(Long::longValue).toArray());
        return true;
    }

    private boolean prepareBeam() {
        int n = actions.length, m = keyIds.size(), capacity = WIDTH * n, table = 1;
        while (table < capacity * 2) table <<= 1;
        // Includes both materialized layers, all virtual candidates, exact hash
        // chains, sparse indices, trace arrays and the final summary workspace.
        long bytes = 8192L + 64L * WIDTH * (n + m) + 128L * capacity + 8L * table +
                16L * WIDTH * length + 128L * length + 8L * n * n + 1024L * m;
        if (m > 4096 || !reserve(bytes)) { stop("workspace"); return false; }
        keys = List.copyOf(keyIds.keySet()); initial = new long[m]; minimum = new long[m]; changes = new long[m];
        for (int i = 0; i < m; i++) {
            budget.check(); BigInteger held = PlanningInventory.exactAmount(model.stock, keys.get(i));
            if (held.signum() < 0 || held.compareTo(BigInteger.valueOf(COMPACT_MAX)) > 0) { stop("wide_stock"); return false; }
            initial[i] = held.longValueExact(); minimum[i] = minima.getOrDefault(keys.get(i), 0L);
        }
        long[] upper = initial.clone();
        for (int r = 0; r < n; r++) for (int p = 0; p < actions[r].keys.length; p++) {
            budget.check(); long gain = Math.max(0, actions[r].delta[p]); int key = actions[r].keys[p];
            if (gain > (COMPACT_MAX - upper[key]) / primitive[r]) { stop("wide_period_stock"); return false; }
            upper[key] += gain * primitive[r];
        }
        weights = new long[n]; affected = new int[n][];
        var byMaterial = new ArrayList<List<Integer>>(m);
        for (int i = 0; i < m; i++) byMaterial.add(new ArrayList<>());
        for (int r = 0; r < n; r++) for (int key : actions[r].inputs) { scan(1); byMaterial.get(key).add(r); }
        consumers = new int[m][];
        for (int i = 0; i < m; i++) { scan(1); consumers[i] = byMaterial.get(i).stream().mapToInt(Integer::intValue).toArray(); }
        beam = new State[WIDTH]; nextBeam = new State[WIDTH];
        State state = new State(primitive.clone(), initial.clone(), new int[n], -1);
        for (int r = 0; r < n; r++) {
            scan(1); weights[r] = mix(r + 1L); state.hash += weights[r] * primitive[r];
            state.missing[r] = deficits(state.held, actions[r]);
            if (state.missing[r] == 0) state.ready++;
            if (state.missing[r] >= 2) state.blocked++;
        }
        for (int k = 0; k < m; k++) { scan(1); state.reserve += reserveScore(k, state.held[k]); }
        beam[0] = state; beamSize = 1;
        selected = new int[WIDTH]; heads = new int[table]; Arrays.fill(heads, -1);
        links = new int[capacity]; parents = new int[capacity]; moves = new int[capacity];
        ready = new int[capacity]; blocked = new int[capacity]; hashes = new long[capacity]; reserves = new long[capacity];
        traceParents = new int[WIDTH * length]; traceActions = new int[WIDTH * length];
        budget.note("count_period_schedule", "start; scc=" + n + "; gcd=" + repetitions + "; period=" + length + "; width=" + WIDTH);
        return true;
    }

    private void offer(int p, int r) {
        State state = beam[p]; long hash = state.hash - weights[r];
        int slot = (int) mix(hash) & (heads.length - 1);
        for (int old = heads[slot]; old >= 0; old = links[old]) {
            scan(1);
            if (hash == hashes[old] && equal(p, r, parents[old], moves[old])) return;
        }
        int id = unique++; candidates++;
        parents[id] = p; moves[id] = r; hashes[id] = hash;
        links[id] = heads[slot]; heads[slot] = id;
        ready[id] = state.ready; blocked[id] = state.blocked; reserves[id] = state.reserve;
        var firing = actions[r];
        for (int i = 0; i < firing.keys.length; i++) {
            int k = firing.keys[i]; long change = firing.delta[i]; changes[k] = change;
            scan(4); reserves[id] += reserveScore(k, state.held[k] + change) - reserveScore(k, state.held[k]);
        }
        for (int consumer : affected[r]) if (state.remaining[consumer] > 0) {
            var rule = actions[consumer]; int missing = 0;
            for (int i = 0; i < rule.inputs.length; i++) {
                int k = rule.inputs[i]; scan(3);
                if (state.held[k] + changes[k] < rule.need[i]) missing++;
            }
            if (state.missing[consumer] == 0) ready[id]--;
            if (state.missing[consumer] >= 2) blocked[id]--;
            if (consumer != r || state.remaining[r] > 1) {
                if (missing == 0) ready[id]++;
                if (missing >= 2) blocked[id]++;
            }
        }
        for (int key : firing.keys) changes[key] = 0;
        if (selectedSize == WIDTH && !better(id, selected[WIDTH - 1])) return;
        int position = Math.min(selectedSize, WIDTH - 1);
        while (position > 0 && better(id, selected[position - 1])) {
            selected[position] = selected[position - 1]; position--;
        }
        selected[position] = id; selectedSize = Math.min(WIDTH, selectedSize + 1);
    }

    private boolean equal(int p, int r, int q, int s) {
        if (p == q) return r == s;
        int[] a = beam[p].remaining, b = beam[q].remaining;
        int read = 0; boolean equal = true;
        for (int i = 0; i < a.length; i++) {
            read += 2;
            if (a[i] - (i == r ? 1 : 0) != b[i] - (i == s ? 1 : 0)) { equal = false; break; }
        }
        scan(read); return equal;
    }

    private boolean better(int a, int b) {
        scan(1);
        if (ready[a] != ready[b]) return ready[a] > ready[b];
        if (blocked[a] != blocked[b]) return blocked[a] < blocked[b];
        return reserves[a] > reserves[b]; // Stable generation order breaks ties.
    }

    private void materialize(int index) {
        int id = selected[index], r = moves[id]; State old = beam[parents[id]];
        scan(old.remaining.length + old.held.length + old.missing.length);
        State state = new State(old.remaining.clone(), old.held.clone(), old.missing.clone(), layer * WIDTH + index);
        state.remaining[r]--; state.ready = ready[id]; state.blocked = blocked[id];
        state.reserve = reserves[id]; state.hash = hashes[id];
        var firing = actions[r];
        for (int i = 0; i < firing.keys.length; i++) { scan(1); state.held[firing.keys[i]] += firing.delta[i]; }
        for (int consumer : affected[r]) state.missing[consumer] = deficits(state.held, actions[consumer]);
        traceParents[state.trace] = old.trace; traceActions[state.trace] = r; nextBeam[index] = state;
    }

    private int deficits(long[] held, Action rule) {
        scan(rule.inputs.length); int missing = 0;
        for (int i = 0; i < rule.inputs.length; i++) if (held[rule.inputs[i]] < rule.need[i]) missing++;
        return missing;
    }

    private long reserveScore(int key, long held) {
        long unit = minimum[key];
        return unit == 0 ? 0 : 16 * Math.min(held, 2 * unit) / unit;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private void scan(int n) { budget.operations(PlanningBudget.Operation.SCAN, 0, n); }
    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes; return true;
    }
    private boolean stop(String why) {
        reason = why; complete = true;
        budget.note("count_period_schedule", why + "; candidates=" + candidates + "; layers=" + layer +
                "; work=" + (work + budget.threadWork() - stepStart));
        return true;
    }
    PlanStep witness() { return witness; }
    String reason() { return reason; }

    @Override public void close() {
        if (topology != null) { topology.close(); topology = null; }
        if (summary != null) { summary.close(); summary = null; }
        budget.release(memory); memory = 0;
        beam = nextBeam = null; actions = null; affected = consumers = null;
        parents = moves = links = heads = traceParents = traceActions = null;
        keyIds.clear(); minima.clear();
    }

    private record Action(int[] inputs, long[] need, int[] keys, long[] delta) {}
    private static final class State {
        final int[] remaining, missing;
        final long[] held;
        final int trace;
        long hash, reserve;
        int ready, blocked;
        State(int[] remaining, long[] held, int[] missing, int trace) {
            this.remaining = remaining; this.held = held; this.missing = missing; this.trace = trace;
        }
    }
}
