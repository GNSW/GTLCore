package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent checks for execution exclusions, without replaying the search algorithm. */
public final class ExecutionProof {

    private ExecutionProof() {}

    public enum Kind {
        BACKWARD_CLOSURE,
        FORWARD_BOUNDARY,
        STARTUP_BOX
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
            } else {
                if (!states.contains(proof.initial)) return CountProof.Verdict.INVALID;
                for (var state : states) {
                    if (leq(proof.goal, state, work)) return CountProof.Verdict.INVALID;
                    for (int r = 0; r < proof.inputs.size(); r++) {
                        if (!leq(proof.inputs.get(r), state, work)) continue;
                        List<BigInteger> next = new ArrayList<>();
                        for (int k = 0; k < size; k++) {
                            tick(work);
                            next.add(state.get(k).subtract(proof.inputs.get(r).get(k)).add(proof.outputs.get(r).get(k)));
                        }
                        if (!states.contains(next) && !proof.marked.contains(r)) return CountProof.Verdict.INVALID;
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
