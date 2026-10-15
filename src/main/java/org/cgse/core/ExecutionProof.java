// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent checks for execution exclusions, without replaying the search algorithm. */
public final class ExecutionProof {

    private ExecutionProof() {}

    public enum Kind {
        BACKWARD_CLOSURE,
        FORWARD_BOUNDARY,
        STARTUP_BOX,
        // Append: ordinals of existing serialized proof kinds remain stable.
        FORWARD_DOMINANCE
    }

    public record Certificate(String scope, Kind kind, List<BigInteger> initial, List<BigInteger> goal,
                              List<List<BigInteger>> inputs, List<List<BigInteger>> outputs,
                              List<List<BigInteger>> states, Set<Integer> marked) {

        public Certificate {
            initial = List.copyOf(initial);
            goal = List.copyOf(goal);
            inputs = inputs.stream().map(List::copyOf).toList();
            outputs = outputs.stream().map(List::copyOf).toList();
            states = states.stream().map(List::copyOf).toList();
            marked = Set.copyOf(marked);
        }
    }

    public static CountProof.Verdict verify(Certificate proof, long maximumWork) {
        return verify(proof, maximumWork, unused -> {});
    }

    static CountProof.Verdict verify(Certificate proof, long maximumWork, java.util.function.LongConsumer charged) {
        if (proof.kind == null) return CountProof.Verdict.INVALID;
        if (proof.kind == Kind.FORWARD_BOUNDARY || proof.kind == Kind.FORWARD_DOMINANCE) {
            if (maximumWork <= 0) return CountProof.Verdict.INVALID;
            var check = new ForwardCheck(proof);
            check.step(maximumWork, charged);
            return check.result == null ? CountProof.Verdict.INCOMPLETE : check.result;
        }
        int size = proof.initial.size();
        if (size != proof.goal.size() || proof.inputs.size() != proof.outputs.size() || maximumWork <= 0) return CountProof.Verdict.INVALID;
        List<List<BigInteger>> vectors = new ArrayList<>(proof.inputs);
        vectors.addAll(proof.outputs);
        vectors.addAll(proof.states);
        vectors.add(proof.initial);
        vectors.add(proof.goal);
        if (vectors.stream().anyMatch(v -> v.size() != size || v.stream().anyMatch(n -> n.signum() < 0))) return CountProof.Verdict.INVALID;
        if (proof.marked.stream().anyMatch(i -> i < 0 || i >= (proof.kind == Kind.STARTUP_BOX ? size : proof.inputs.size()))) return CountProof.Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            if (proof.kind == Kind.STARTUP_BOX) {
                boolean excludes = false;
                for (int k = 0; k < size; k++) if (!proof.marked.contains(k) && proof.goal.get(k).compareTo(proof.initial.get(k)) > 0) excludes = true;
                if (!excludes) return CountProof.Verdict.INVALID;
                for (int r = 0; r < proof.inputs.size(); r++) {
                    boolean enabled = true;
                    for (int k = 0; k < size; k++) {
                        tick(work);
                        if (!proof.marked.contains(k) && proof.inputs.get(r).get(k).compareTo(proof.initial.get(k)) > 0) {
                            enabled = false;
                            break;
                        }
                    }
                    if (enabled) for (int k = 0; k < size; k++) {
                        tick(work);
                        if (!proof.marked.contains(k) && proof.outputs.get(r).get(k).compareTo(proof.inputs.get(r).get(k)) > 0) return CountProof.Verdict.INVALID;
                    }
                }
                return CountProof.Verdict.VERIFIED;
            }
            Set<List<BigInteger>> states = new HashSet<>(proof.states);
            if (proof.kind == Kind.BACKWARD_CLOSURE) {
                if (!covered(proof.goal, states, work)) return CountProof.Verdict.INVALID;
                for (var state : states) {
                    if (leq(state, proof.initial, work)) return CountProof.Verdict.INVALID;
                    for (int r = 0; r < proof.inputs.size(); r++) {
                        List<BigInteger> predecessor = new ArrayList<>();
                        for (int k = 0; k < size; k++) {
                            tick(work);
                            predecessor.add(proof.inputs.get(r).get(k).add(state.get(k).subtract(proof.outputs.get(r).get(k)).max(BigInteger.ZERO)));
                        }
                        if (!covered(predecessor, states, work)) return CountProof.Verdict.INVALID;
                    }
                }
            }
            return CountProof.Verdict.VERIFIED;
        } catch (Limit limit) {
            return CountProof.Verdict.INCOMPLETE;
        } finally {
            charged.accept(maximumWork - work[0]);
        }
    }

    /** Independent forward-boundary replay. Cursors survive a local work grant. */
    static final class ForwardCheck {
        private final Certificate proof;
        private final Set<List<BigInteger>> states = new HashSet<>();
        private final Iterator<Integer> marked;
        private final int size;
        private final BitSet[] required;
        private final BitSet nonIncreasing = new BitSet(), decreasing = new BitSet();
        private final Map<List<BigInteger>, BigInteger> covered = new HashMap<>();
        private int dominanceCoordinate = -1;
        private List<BigInteger> group;
        private BigInteger value;
        private int phase, vector, coordinate, state, recipe;
        private List<BigInteger> next;
        private CountProof.Verdict result;

        ForwardCheck(Certificate proof) {
            this.proof = proof;
            size = proof.initial.size();
            required = new BitSet[proof.inputs.size()];
            marked = proof.marked.iterator();
            if (proof.kind != Kind.FORWARD_BOUNDARY && proof.kind != Kind.FORWARD_DOMINANCE || size != proof.goal.size() ||
                    proof.inputs.size() != proof.outputs.size()) result = CountProof.Verdict.INVALID;
        }

        boolean step(long maximumWork, java.util.function.LongConsumer charged) {
            for (long used = 0; result == null && used < maximumWork; used++) {
                // Charge before changing a cursor. Cancellation and the shared
                // request limit remain effective during a large proof replay.
                charged.accept(1);
                advance();
            }
            return result != null;
        }

        CountProof.Verdict result() { return result; }

        private List<BigInteger> vector(int index) {
            if (index == 0) return proof.initial;
            if (index == 1) return proof.goal;
            index -= 2;
            if (index < proof.inputs.size()) return proof.inputs.get(index);
            index -= proof.inputs.size();
            if (index < proof.outputs.size()) return proof.outputs.get(index);
            return proof.states.get(index - proof.outputs.size());
        }

        private void advance() {
            switch (phase) {
                case 0 -> {
                    if (vector == 2 + proof.inputs.size() + proof.outputs.size() + proof.states.size()) {
                        nonIncreasing.and(decreasing);
                        if (proof.kind == Kind.FORWARD_DOMINANCE) dominanceCoordinate = nonIncreasing.nextSetBit(0);
                        phase = 1;
                        return;
                    }
                    var values = vector(vector);
                    if (values.size() != size || coordinate < size && values.get(coordinate).signum() < 0) {
                        result = CountProof.Verdict.INVALID;
                        return;
                    }
                    // Rebuild from the original certificate while independently
                    // validating every entry. Never trust the search's index.
                    if (vector >= 2 && vector < 2 + proof.inputs.size()) {
                        int input = vector - 2;
                        if (coordinate == 0) required[input] = new BitSet();
                        if (coordinate < size && values.get(coordinate).signum() > 0) required[input].set(coordinate);
                    }
                    if (proof.kind == Kind.FORWARD_DOMINANCE && coordinate < size &&
                            vector >= 2 + proof.inputs.size() && vector < 2 + proof.inputs.size() + proof.outputs.size()) {
                        int output = vector - 2 - proof.inputs.size();
                        int sign = values.get(coordinate).compareTo(proof.inputs.get(output).get(coordinate));
                        if (output == 0) nonIncreasing.set(coordinate);
                        if (sign > 0) nonIncreasing.clear(coordinate);
                        else if (sign < 0) decreasing.set(coordinate);
                    }
                    if (++coordinate >= size) { coordinate = 0; vector++; }
                }
                case 1 -> {
                    if (marked.hasNext()) {
                        int id = marked.next();
                        if (id < 0 || id >= proof.inputs.size()) result = CountProof.Verdict.INVALID;
                    } else phase = 2;
                }
                case 2 -> {
                    if (state < proof.states.size()) {
                        var marking = proof.states.get(state);
                        if (dominanceCoordinate < 0) { states.add(marking); state++; }
                        else if (coordinate < size) {
                            if (group == null) group = new ArrayList<>(size - 1);
                            if (coordinate != dominanceCoordinate) group.add(marking.get(coordinate));
                            coordinate++;
                        } else {
                            covered.merge(List.copyOf(group), marking.get(dominanceCoordinate), BigInteger::max);
                            states.add(marking); state++; coordinate = 0; group = null;
                        }
                    }
                    else if (!states.contains(proof.initial)) result = CountProof.Verdict.INVALID;
                    else { state = 0; phase = 3; }
                }
                case 3 -> {
                    if (state == proof.states.size()) result = CountProof.Verdict.VERIFIED;
                    else if (coordinate == size) result = CountProof.Verdict.INVALID; // Goal is reachable.
                    else if (proof.goal.get(coordinate).compareTo(proof.states.get(state).get(coordinate)) > 0) {
                        coordinate = 0;
                        recipe = 0;
                        phase = 4;
                    } else coordinate++;
                }
                case 4 -> {
                    if (recipe == proof.inputs.size()) { state++; coordinate = 0; phase = 3; }
                    else {
                        int input = required[recipe].nextSetBit(coordinate);
                        if (input < 0) { next = new ArrayList<>(size); coordinate = 0; phase = 5; }
                        else if (proof.inputs.get(recipe).get(input).compareTo(proof.states.get(state).get(input)) > 0) {
                            recipe++;
                            coordinate = 0;
                        } else coordinate = input + 1;
                    }
                }
                case 5 -> {
                    if (coordinate < size) {
                        next.add(proof.states.get(state).get(coordinate).subtract(proof.inputs.get(recipe).get(coordinate))
                                .add(proof.outputs.get(recipe).get(coordinate)));
                        coordinate++;
                    } else if (states.contains(next) || proof.marked.contains(recipe)) nextRecipe();
                    else if (dominanceCoordinate < 0) result = CountProof.Verdict.INVALID;
                    else { group = new ArrayList<>(size - 1); value = next.get(dominanceCoordinate); coordinate = 0; phase = 6; }
                }
                case 6 -> {
                    if (coordinate < size) {
                        if (coordinate != dominanceCoordinate) group.add(next.get(coordinate));
                        coordinate++;
                    } else {
                        var maximum = covered.get(group);
                        if (maximum == null || maximum.compareTo(value) < 0) result = CountProof.Verdict.INVALID;
                        else { group = null; value = null; nextRecipe(); }
                    }
                }
                default -> throw new IllegalStateException("Invalid forward proof phase");
            }
        }

        private void nextRecipe() {
            next = null; coordinate = 0; recipe++; phase = 4;
        }
    }

    private static boolean covered(List<BigInteger> value, Collection<List<BigInteger>> states, long[] work) {
        for (var state : states) if (leq(state, value, work)) return true;
        return false;
    }

    private static boolean leq(List<BigInteger> a, List<BigInteger> b, long[] work) {
        for (int i = 0; i < a.size(); i++) {
            tick(work);
            if (a.get(i).compareTo(b.get(i)) > 0) return false;
        }
        return true;
    }

    private static class Limit extends RuntimeException {

        Limit() {
            super(null, null, false, false);
        }
    }

    private static void tick(long[] work) {
        if (--work[0] < 0) throw new Limit();
    }
}
