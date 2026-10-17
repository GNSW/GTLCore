// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Catalog-owned derivations, not cached inventory-dependent right-hand sides or conflicts. */
final class CountRowTemplates {

    private static final int MAX_PARENTS = 16, MAX_TERMS = 128, MAX_BITS = 1024;
    private static final long SNAPSHOT_BYTES = 1024;
    // Catalog-owned retention never borrows the current request's memory. All
    // concurrent preparers and their evictable snapshots share this separate cap.
    private static final long RESIDENT_BYTES = 2L << 20, PREPARATION_BYTES = 3L << 20;
    private record Parent(Object key, Map<String, BigInteger> supply, BigInteger divisor, BigInteger weight) {}
    private record Template(List<Parent> parents, BigInteger divisor, long bytes) {}
    private record Snapshot(List<Template> templates, long bytes, long revision) {}
    private record Binding(int row, BigInteger divisor, BigInteger weight) {}
    record Consequence(ExactLinearProgram.Constraint row) {}
    private final Deque<Template> templates = new ArrayDeque<>();
    private long hits, misses, evictions, memory, revision, preparationMemory;

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private static final class Work {
        final PlanningBudget budget;
        final long started, allowance;
        final boolean retaining;
        Work(PlanningBudget budget, boolean retaining) {
            this.budget = budget;
            this.retaining = retaining;
            started = retaining ? 0 : budget.threadWork();
            allowance = retaining ? 0 : Math.min(65536, budget.remainingWork() / 32);
        }
        long remaining() {
            return retaining ? budget.remainingCachePreparationWork() : Math.max(0, allowance - (budget.threadWork() - started));
        }
        void check() {
            if (retaining) charge(1);
            else {
                if (remaining() == 0) throw new Stop();
                budget.check();
            }
        }
        void charge(long units) {
            if (retaining) {
                if (!budget.tryCacheCharge(units)) throw new Stop();
            } else budget.charge(units);
        }
        void operation(PlanningBudget.Operation operation, int bits) {
            if (retaining) {
                if (!budget.tryCacheOperations(operation, bits, 1)) throw new Stop();
            } else budget.operation(operation, bits);
        }
        BigInteger product(BigInteger a, BigInteger b) {
            check();
            if (a.bitLength() + b.bitLength() > 2 * MAX_BITS) throw new Stop();
            operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
            return a.multiply(b);
        }
    }

    synchronized GraphCompiler.CacheEntry cacheMetrics() {
        return new GraphCompiler.CacheEntry(templates.size(), memory / 256, memory + preparationMemory, hits, misses, evictions);
    }

    /** Every premise must independently match a complete material row or its exact integer division. */
    void remember(RecipeCountModel<?> model, List<ExactLinearProgram.Constraint> source,
                  CountLpLearning.Cut cut, PlanningBudget budget) {
        if (cut.parents().isEmpty() || cut.parents().size() > MAX_PARENTS || cut.divisor().signum() <= 0 ||
                cut.divisor().bitLength() > MAX_BITS || cut.row().terms().size() > MAX_TERMS) return;
        var work = new Work(budget, true);
        long scratch = 262144 + SNAPSHOT_BYTES;
        for (int id : cut.parents().keySet()) {
            if (id < 0 || id >= source.size() || source.get(id).terms().size() > MAX_TERMS) return;
            scratch += 1024L * source.get(id).terms().size();
        }
        if (!reservePreparation(scratch)) return;
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
            var immutable = List.copyOf(parents);
            // These are full current material signatures, not stale RHS values.
            // An identical, already verified derivation needs no second saved
            // copy. Actual reuse below still reconstructs and checks current RHS.
            if (retained(immutable, cut.divisor(), work)) return;
            var reconstructed = rebuild(model, bindings, cut.divisor(), work, false);
            if (reconstructed == null || !reconstructed.row().equals(cut.row())) return;
            long bytes = 512L + 256L * parents.size();
            for (var parent : parents) for (var term : parent.supply.entrySet())
                bytes += 256L + 2L * term.getKey().length() + (term.getValue().bitLength() + 7L) / 8;
            if (bytes > 262144) return;
            if (!publish(new Template(immutable, cut.divisor(), bytes), work)) return;
            long steps = 1 + parents.stream().filter(parent -> !parent.divisor.equals(BigInteger.ONE)).count();
            budget.note("count_row_template", "retained; parents=" + parents.size() + "; material_derivation_steps=" + steps);
        } catch (Stop ignored) {
            // Template admission is optional, independently bounded, and never a search conclusion.
        } finally { releasePreparation(scratch); }
    }

    private synchronized boolean reservePreparation(long bytes) {
        if (bytes > PREPARATION_BYTES - preparationMemory) return false;
        preparationMemory += bytes;
        return true;
    }

    private synchronized void releasePreparation(long bytes) { preparationMemory -= bytes; }

    private synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(templates), memory, revision);
    }

    private synchronized Snapshot retentionSnapshot() {
        if (!reservePreparation(memory)) return null;
        return snapshot();
    }

    private boolean retained(List<Parent> parents, BigInteger divisor, Work work) {
        var snapshot = retentionSnapshot();
        if (snapshot == null) throw new Stop();
        try {
            for (var old : snapshot.templates) {
                work.check();
                if (old.parents.equals(parents) && old.divisor.equals(divisor)) return true;
            }
            return false;
        } finally { releasePreparation(snapshot.bytes); }
    }

    /** Signature comparisons and budget callbacks are outside the catalog monitor too. */
    private boolean publish(Template candidate, Work work) {
        for (int attempt = 0; attempt < 3; attempt++) {
            var snapshot = retentionSnapshot();
            if (snapshot == null) return false;
            try {
                for (var old : snapshot.templates) {
                    work.check();
                    if (old.parents.equals(candidate.parents) && old.divisor.equals(candidate.divisor)) return false;
                }
                synchronized (this) {
                    if (revision != snapshot.revision) continue;
                    while (!templates.isEmpty() && (templates.size() >= 32 || memory + candidate.bytes > RESIDENT_BYTES)) {
                        memory -= templates.removeLast().bytes;
                        evictions++;
                    }
                    templates.addFirst(candidate);
                    memory += candidate.bytes;
                    revision++;
                    return true;
                }
            } finally { releasePreparation(snapshot.bytes); }
        }
        // Admission is optional: catalog churn cannot create an unbounded retry.
        return false;
    }

    List<Consequence> reuse(RecipeCountModel<?> model, PlanningBudget budget) {
        var work = new Work(budget, false);
        if (work.allowance < 1024) return List.of();
        if (!budget.tryReserve(SNAPSHOT_BYTES)) return List.of();
        long scratch = SNAPSHOT_BYTES, localHits = 0, localMisses = 0;
        List<Consequence> result = new ArrayList<>();
        try {
            var snapshot = snapshot();
            if (snapshot.templates.isEmpty()) return List.of();
            long terms = 0, resultTerms = 0;
            for (var template : snapshot.templates) {
                long next = 0;
                for (var parent : template.parents) next += parent.supply.size();
                terms = Math.max(terms, next);
                resultTerms += Math.min(MAX_TERMS, next);
            }
            // One proof workspace plus all returned rows coexist. Charge the
            // immutable templates too: concurrent eviction may leave this request
            // as their sole owner. No exact work or host callback holds the lock.
            long workspace = 262144L + 96L * model.rowKeys.size() + snapshot.bytes + 1024L * terms + 512L * resultTerms;
            if (workspace > budget.availableBytes() / 8 || !budget.tryReserve(workspace)) return List.of();
            scratch += workspace;
            Map<Object, Integer> positions = new HashMap<>();
            for (int i = 0; i < model.rowKeys.size(); i++) { work.check(); positions.put(model.rowKeys.get(i), i); }
            Map<Integer, Map<String, BigInteger>> signatures = new HashMap<>();
            long signatureBytes = 0;
            for (var template : snapshot.templates) {
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
                if (!matched) { localMisses++; continue; }
                var cut = rebuild(model, parents, template.divisor, work, true);
                if (cut == null) { localMisses++; continue; }
                result.add(cut);
                localHits++;
                if (result.size() == 16) break;
            }
        } catch (Stop ignored) {
            // Already completed exact consequences remain valid.
        } finally {
            synchronized (this) { hits += localHits; misses += localMisses; }
            budget.release(scratch);
        }
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
                    work.operation(PlanningBudget.Operation.INTEGER, Math.max(value.bitLength(), term.getValue().bitLength()));
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
        if (CountProof.verify(proof, work.remaining(), work::charge) != CountProof.Verdict.VERIFIED) return null;
        if (journal && work.budget.proofJournal() != null) work.budget.proofJournal().add(proof);
        return new Consequence(row);
    }

    private static ExactLinearProgram.Constraint divide(Map<Integer, BigInteger> terms, BigInteger upper,
                                                        BigInteger divisor, Work work) {
        Map<Integer, BigInteger> divided = new TreeMap<>();
        for (var term : terms.entrySet()) {
            work.check();
            work.operation(PlanningBudget.Operation.INTEGER, Math.max(term.getValue().bitLength(), divisor.bitLength()));
            var qr = term.getValue().divideAndRemainder(divisor);
            if (qr[1].signum() != 0) return null;
            divided.put(term.getKey(), qr[0]);
        }
        return new ExactLinearProgram.Constraint(divided, floor(upper, divisor, work));
    }

    private static BigInteger floor(BigInteger upper, BigInteger divisor, Work work) {
        work.check();
        work.operation(PlanningBudget.Operation.INTEGER, Math.max(upper.bitLength(), divisor.bitLength()));
        var qr = upper.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
}
