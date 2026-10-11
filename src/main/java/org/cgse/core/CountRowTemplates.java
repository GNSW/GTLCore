// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Catalog-owned derivations, not cached inventory-dependent right-hand sides or conflicts. */
final class CountRowTemplates {

    private static final int MAX_PARENTS = 16, MAX_TERMS = 128, MAX_BITS = 1024;
    private record Parent(Object key, Map<String, BigInteger> supply, BigInteger divisor, BigInteger weight) {}
    private record Template(List<Parent> parents, BigInteger divisor, long bytes) {}
    private record Binding(int row, BigInteger divisor, BigInteger weight) {}
    record Consequence(ExactLinearProgram.Constraint row) {}
    private final Deque<Template> templates = new ArrayDeque<>();
    private long hits, misses, evictions, memory;

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private static final class Work {
        final PlanningBudget budget;
        final long started, allowance;
        Work(PlanningBudget budget) {
            this.budget = budget;
            started = budget.threadWork();
            allowance = Math.min(65536, budget.remainingWork() / 32);
        }
        long remaining() { return Math.max(0, allowance - (budget.threadWork() - started)); }
        void check() {
            if (remaining() == 0) throw new Stop();
            budget.check();
        }
        BigInteger product(BigInteger a, BigInteger b) {
            check();
            if (a.bitLength() + b.bitLength() > 2 * MAX_BITS) throw new Stop();
            budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
            return a.multiply(b);
        }
    }

    synchronized GraphCompiler.CacheEntry cacheMetrics() {
        return new GraphCompiler.CacheEntry(templates.size(), memory / 256, memory, hits, misses, evictions);
    }

    /** Every premise must independently match a complete material row or its exact integer division. */
    synchronized void remember(RecipeCountModel<?> model, List<ExactLinearProgram.Constraint> source,
                               CountLpLearning.Cut cut, PlanningBudget budget) {
        if (cut.parents().isEmpty() || cut.parents().size() > MAX_PARENTS || cut.divisor().signum() <= 0 ||
                cut.divisor().bitLength() > MAX_BITS || cut.row().terms().size() > MAX_TERMS) return;
        var work = new Work(budget);
        if (work.allowance < 1024) return;
        long scratch = 262144;
        for (int id : cut.parents().keySet()) {
            if (id < 0 || id >= source.size() || source.get(id).terms().size() > MAX_TERMS) return;
            scratch += 1024L * source.get(id).terms().size();
        }
        if (scratch > budget.availableBytes() / 8 || !budget.tryReserve(scratch)) return;
        try {
            List<Parent> parents = new ArrayList<>();
            List<Binding> bindings = new ArrayList<>();
            for (var term : cut.parents().entrySet()) {
                work.check();
                int id = term.getKey();
                if (term.getValue().signum() <= 0 || term.getValue().bitLength() > MAX_BITS) return;
                var binding = match(model, source.get(id), id, term.getValue(), work);
                if (binding == null) return;
                var supply = signature(model, model.constraints.get(binding.row), work);
                if (supply == null) return;
                parents.add(new Parent(model.rowKeys.get(binding.row), supply, binding.divisor, binding.weight));
                bindings.add(binding);
            }
            var reconstructed = rebuild(model, bindings, cut.divisor(), work, false);
            if (reconstructed == null || !reconstructed.row().equals(cut.row())) return;
            var immutable = List.copyOf(parents);
            for (var old : templates) if (old.parents.equals(immutable) && old.divisor.equals(cut.divisor())) return;
            long bytes = 512L + 256L * parents.size();
            for (var parent : parents) for (var term : parent.supply.entrySet())
                bytes += 256L + 2L * term.getKey().length() + (term.getValue().bitLength() + 7L) / 8;
            if (bytes > 262144) return;
            while (!templates.isEmpty() && (templates.size() >= 32 || memory + bytes > (2L << 20))) {
                memory -= templates.removeLast().bytes;
                evictions++;
            }
            templates.addFirst(new Template(immutable, cut.divisor(), bytes));
            memory += bytes;
            long steps = 1 + parents.stream().filter(parent -> !parent.divisor.equals(BigInteger.ONE)).count();
            budget.note("count_row_template", "retained; parents=" + parents.size() + "; material_derivation_steps=" + steps);
        } catch (Stop ignored) {
            // Template admission is optional, independently bounded, and never a search conclusion.
        } finally { budget.release(scratch); }
    }

    synchronized List<Consequence> reuse(RecipeCountModel<?> model, PlanningBudget budget) {
        if (templates.isEmpty()) return List.of();
        var work = new Work(budget);
        if (work.allowance < 1024) return List.of();
        long scratch = 262144L + 96L * model.rowKeys.size();
        long terms = 0, resultTerms = 0;
        for (var template : templates) {
            long next = 0;
            for (var parent : template.parents) next += parent.supply.size();
            terms = Math.max(terms, next);
            resultTerms += Math.min(MAX_TERMS, next);
        }
        // One proof workspace plus all returned rows coexist. The base scratch
        // also covers the bounded signature cache and one unmatched full row.
        scratch += 1024L * terms + 512L * resultTerms;
        if (scratch > budget.availableBytes() / 8 || !budget.tryReserve(scratch)) return List.of();
        List<Consequence> result = new ArrayList<>();
        try {
            Map<Object, Integer> positions = new HashMap<>();
            for (int i = 0; i < model.rowKeys.size(); i++) { work.check(); positions.put(model.rowKeys.get(i), i); }
            Map<Integer, Map<String, BigInteger>> signatures = new HashMap<>();
            long signatureBytes = 0;
            for (var template : templates) {
                work.check();
                List<Binding> parents = new ArrayList<>();
                boolean matched = true;
                for (var parent : template.parents) {
                    work.check();
                    Integer id = positions.get(parent.key);
                    if (id == null) { matched = false; break; }
                    var supply = signatures.get(id);
                    if (supply == null) {
                        supply = signature(model, model.constraints.get(id), work);
                        if (supply != null) {
                            long bytes = 256L + 192L * supply.size();
                            if (signatureBytes + bytes <= 65536) {
                                signatures.put(id, supply);
                                signatureBytes += bytes;
                            }
                        }
                    }
                    // Compare the ENTIRE material row by recipe identity, not just
                    // the previously referenced columns. A new producer changes it.
                    if (!parent.supply.equals(supply)) { matched = false; break; }
                    parents.add(new Binding(id, parent.divisor, parent.weight));
                }
                if (!matched) { misses++; continue; }
                var cut = rebuild(model, parents, template.divisor, work, true);
                if (cut == null) { misses++; continue; }
                result.add(cut);
                hits++;
                if (result.size() == 16) break;
            }
        } catch (Stop ignored) {
            // Already completed exact consequences remain valid.
        } finally { budget.release(scratch); }
        if (!result.isEmpty()) budget.note("count_row_template", "replayed=" + result.size() + "; current_rhs; complete_supply_match");
        return List.copyOf(result);
    }

    private static Binding match(RecipeCountModel<?> model, ExactLinearProgram.Constraint source, int hint,
                                 BigInteger weight, Work work) {
        if (hint < model.rowKeys.size() && source.equals(model.constraints.get(hint)))
            return new Binding(hint, BigInteger.ONE, weight);
        if (source.terms().isEmpty() || source.upper().bitLength() > MAX_BITS) return null;
        for (int id = 0; id < model.rowKeys.size(); id++) {
            work.check();
            var row = model.constraints.get(id);
            if (row.terms().size() != source.terms().size() || row.upper().bitLength() > MAX_BITS) continue;
            BigInteger divisor = null;
            boolean matched = true;
            for (var term : source.terms().entrySet()) {
                work.check();
                var value = row.terms().get(term.getKey());
                if (value == null || value.bitLength() > MAX_BITS || term.getValue().signum() == 0 ||
                        term.getValue().bitLength() > MAX_BITS) { matched = false; break; }
                if (divisor == null) {
                    work.budget.operation(PlanningBudget.Operation.INTEGER, Math.max(value.bitLength(), term.getValue().bitLength()));
                    var qr = value.divideAndRemainder(term.getValue());
                    if (qr[1].signum() != 0 || qr[0].signum() <= 0) { matched = false; break; }
                    divisor = qr[0];
                } else if (!value.equals(work.product(term.getValue(), divisor))) { matched = false; break; }
            }
            // Fixed-variable substitution, coefficient capping and branch cutoffs
            // do not pass this check unless the SAME row has an unconditional proof.
            if (matched && source.upper().equals(floor(row.upper(), divisor, work)))
                return new Binding(id, divisor, weight);
        }
        return null;
    }

    private static Map<String, BigInteger> signature(RecipeCountModel<?> model, ExactLinearProgram.Constraint row, Work work) {
        if (row.terms().size() > MAX_TERMS) return null;
        Map<String, BigInteger> result = new TreeMap<>();
        for (var term : row.terms().entrySet()) {
            work.check();
            if (term.getValue().bitLength() > MAX_BITS || term.getKey() < 0 || term.getKey() >= model.recipes.size()) return null;
            String id = model.recipes.get(term.getKey()).id();
            if (result.put(id, term.getValue()) != null) return null;
        }
        return Map.copyOf(result);
    }

    /** Recompute RHS and independently verify the same exact integer derivation on current axioms. */
    private static Consequence rebuild(RecipeCountModel<?> model, List<Binding> parents,
                                       BigInteger divisor, Work work, boolean journal) {
        Map<Integer, BigInteger> sum = new TreeMap<>(), localParents = new TreeMap<>();
        List<CountProof.Row> axioms = new ArrayList<>();
        List<CountProof.Combination> steps = new ArrayList<>();
        BigInteger upper = BigInteger.ZERO;
        for (var parent : parents) {
            var row = model.constraints.get(parent.row);
            if (row.upper().bitLength() > MAX_BITS) return null;
            int premise = axioms.size();
            axioms.add(CountProof.row(row));
            if (!parent.divisor.equals(BigInteger.ONE)) {
                row = divide(row.terms(), row.upper(), parent.divisor, work);
                if (row == null) return null;
                steps.add(new CountProof.Combination(Map.of(premise, BigInteger.ONE), parent.divisor, CountProof.row(row)));
                premise = parents.size() + steps.size() - 1;
            }
            localParents.put(premise, parent.weight);
            upper = upper.add(work.product(row.upper(), parent.weight));
            for (var term : row.terms().entrySet()) {
                var value = sum.getOrDefault(term.getKey(), BigInteger.ZERO).add(work.product(term.getValue(), parent.weight));
                if (value.signum() == 0) sum.remove(term.getKey());
                else sum.put(term.getKey(), value);
                if (sum.size() > MAX_TERMS || value.bitLength() > 2 * MAX_BITS) return null;
            }
            if (upper.bitLength() > 2 * MAX_BITS) return null;
        }
        var row = divide(sum, upper, divisor, work);
        if (row == null) return null;
        steps.add(new CountProof.Combination(localParents, divisor, CountProof.row(row)));
        var proof = new CountProof.Derivation("current_inventory_template", model.recipes.size(), axioms, steps);
        if (CountProof.verify(proof, work.remaining(), work.budget::charge) != CountProof.Verdict.VERIFIED) return null;
        if (journal && work.budget.proofJournal() != null) work.budget.proofJournal().add(proof);
        return new Consequence(row);
    }

    private static ExactLinearProgram.Constraint divide(Map<Integer, BigInteger> terms, BigInteger upper,
                                                        BigInteger divisor, Work work) {
        Map<Integer, BigInteger> divided = new TreeMap<>();
        for (var term : terms.entrySet()) {
            work.check();
            work.budget.operation(PlanningBudget.Operation.INTEGER, Math.max(term.getValue().bitLength(), divisor.bitLength()));
            var qr = term.getValue().divideAndRemainder(divisor);
            if (qr[1].signum() != 0) return null;
            divided.put(term.getKey(), qr[0]);
        }
        return new ExactLinearProgram.Constraint(divided, floor(upper, divisor, work));
    }

    private static BigInteger floor(BigInteger upper, BigInteger divisor, Work work) {
        work.check();
        work.budget.operation(PlanningBudget.Operation.INTEGER, Math.max(upper.bitLength(), divisor.bitLength()));
        var qr = upper.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
}
