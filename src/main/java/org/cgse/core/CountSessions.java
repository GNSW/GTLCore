// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Persistent assumptions/learned cores owned by one immutable recipe catalog. */
final class CountSessions {

    private static final long RESIDENT_BYTES = 4L << 20, PREPARATION_BYTES = 4L << 20;

    private record Column(int source, String recipe) {}
    private record Entry(List<Column> columns, List<ExactLinearProgram.Constraint> assumptions,
                         List<CountConflict> conflicts, long terms, long bytes) {}
    private record Snapshot(List<Entry> entries, long terms, long bytes) {}
    private record Matched(List<ExactLinearProgram.Constraint> assumptions, List<CountConflict> conflicts) {}

    /** Most hot proofs keep their coordinates and need only one or two material rows. */
    private static final class Matching {
        final RecipeCountModel<?> model;
        final java.util.function.LongConsumer charge;
        Map<String, Integer> ids;
        Map<Map<Integer, BigInteger>, BigInteger> bounds;
        boolean scanned;

        Matching(RecipeCountModel<?> model, java.util.function.LongConsumer charge) {
            this.model = model; this.charge = charge;
        }

        Matched match(Entry entry) {
            boolean identity = true;
            for (var column : entry.columns) {
                charge.accept(1);
                if (column.source >= model.recipes.size() || !column.recipe.equals(model.recipes.get(column.source).id())) {
                    identity = false; break;
                }
            }
            if (identity) return new Matched(entry.assumptions, entry.conflicts);
            if (ids == null) {
                charge.accept(model.recipes.size());
                ids = new HashMap<>();
                for (int i = 0; i < model.recipes.size(); i++)
                    if (ids.put(model.recipes.get(i).id(), i) != null) { ids.clear(); break; }
            }
            int size = entry.columns.isEmpty() ? 0 : entry.columns.get(entry.columns.size() - 1).source + 1;
            int[] coordinates = new int[size]; Arrays.fill(coordinates, -1);
            for (var column : entry.columns) {
                charge.accept(1);
                Integer id = ids.get(column.recipe);
                if (id == null) return null;
                coordinates[column.source] = id;
            }
            List<ExactLinearProgram.Constraint> assumptions = new ArrayList<>();
            for (var row : entry.assumptions) assumptions.add(remap(row, coordinates, charge));
            List<CountConflict> conflicts = new ArrayList<>();
            for (var conflict : entry.conflicts) {
                List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
                for (var row : conflict.assumptions()) rows.add(remap(row, coordinates, charge));
                conflicts.add(new CountConflict(rows));
            }
            return new Matched(List.copyOf(assumptions), List.copyOf(conflicts));
        }

        boolean implies(List<ExactLinearProgram.Constraint> assumptions) {
            if (bounds == null && !scanned && assumptions.size() <= 4) {
                scanned = true;
                for (var needed : assumptions) {
                    boolean found = false;
                    for (var current : model.constraints) {
                        charge.accept(1L + current.terms().size());
                        if (current.terms().equals(needed.terms()) && current.upper().compareTo(needed.upper()) <= 0) {
                            found = true; break;
                        }
                    }
                    if (!found) return false;
                }
                return true;
            }
            if (bounds == null) {
                bounds = new HashMap<>();
                for (var row : model.constraints) {
                    charge.accept(1L + row.terms().size());
                    bounds.merge(row.terms(), row.upper(), BigInteger::min);
                }
            }
            for (var row : assumptions) {
                charge.accept(1L + row.terms().size());
                var current = bounds.get(row.terms());
                if (current == null || current.compareTo(row.upper()) > 0) return false;
            }
            return true;
        }
    }

    private volatile Snapshot retained = new Snapshot(List.of(), 0, 0);
    private boolean preparing;
    private long hits, misses, evictions;

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private static void retainCharge(PlanningBudget budget, long units) {
        if (!budget.tryCacheCharge(units)) throw new Stop();
    }

    synchronized GraphCompiler.CacheEntry cacheMetrics() {
        var snapshot = retained;
        return new GraphCompiler.CacheEntry(snapshot.entries.size(), snapshot.terms,
                snapshot.bytes + (preparing ? PREPARATION_BYTES : 0), hits, misses, evictions);
    }

    <K> List<CountConflict> reuse(RecipeCountModel<K> model, PlanningBudget budget) {
        var snapshot = retained;
        List<CountConflict> result = List.of();
        // A reader may finish an immutable snapshot after a writer evicts it.
        // Charge that pinned lifetime to this request, never resurrect the entry.
        long bytes = snapshot.bytes + 512L + 128L * (model.recipes.size() + model.constraints.size());
        if (!snapshot.entries.isEmpty() && model.recipes.size() <= 8192 && model.constraints.size() <= 8192 && budget.tryReserve(bytes)) {
            try { result = reuseChecked(snapshot, model, budget); }
            finally { budget.release(bytes); }
        }
        synchronized (this) {
            if (result.isEmpty()) misses++; else hits++;
        }
        return result;
    }

    private <K> List<CountConflict> reuseChecked(Snapshot snapshot, RecipeCountModel<K> model, PlanningBudget budget) {
        var matching = new Matching(model, budget::charge);
        int rechecked = 0;
        for (var entry : snapshot.entries) {
            var matched = matching.match(entry);
            if (matched == null) continue;
            if (!matching.implies(matched.assumptions)) {
                // A different request or an unrelated inventory increase may
                // weaken an unused old assumption. Reuse requires a NEW proof
                // against this model, never trust the stale failed boundary.
                if (rechecked++ < 2 && revalidate(model, matched.conflicts, budget)) return matched.conflicts;
                continue;
            }
            // Every retained parent matches a COMPLETE current row. A new
            // producer changes that row's left side and cannot be ignored.
            if (budget.proofJournal() != null) {
                var proof = certificate("reused_count_support", model.recipes.size(), matched.assumptions, matched.conflicts);
                budget.charge(matched.assumptions.size() + matched.conflicts.size());
                budget.proofJournal().add(proof);
            }
            budget.note("count_session", "reused_cores=" + matched.conflicts.size() + "; assumptions_rechecked; support=" + entry.columns.size());
            return matched.conflicts;
        }
        return List.of();
    }

    private <K> boolean revalidate(RecipeCountModel<K> model, List<CountConflict> conflicts, PlanningBudget budget) {
        if (model.recipes.size() > 128 || model.constraints.size() > 512 || budget.remainingWork() < 32768) return false;
        long terms = model.constraints.stream().mapToLong(row -> row.terms().size()).sum();
        if (terms > 4096) return false;
        long bytes = 1024L + 192L * model.constraints.size() + 128L * terms;
        if (bytes > budget.availableBytes() / 16 || !budget.tryReserve(bytes)) return false;
        try {
            budget.charge(terms + model.constraints.size());
            for (var conflict : conflicts) chargeCopy(conflict, budget);
            var proof = new CountProof.Certificate("revalidated_count_assumptions", model.recipes.size(),
                    model.constraints.stream().map(CountProof::row).toList(),
                    conflicts.stream().map(c -> c.assumptions().stream().map(CountProof::row).toList()).toList(), List.of(), false);
            if (CountProof.verify(proof, 4096, budget::charge) != CountProof.Verdict.VERIFIED) return false;
            if (budget.proofJournal() != null) budget.proofJournal().add(proof);
            budget.note("count_session", "revalidated_cores=" + conflicts.size() + "; current_model_certificate");
            return true;
        } finally {
            budget.release(bytes);
        }
    }

    <K> void remember(RecipeCountModel<K> model, List<CountConflict> conflicts, PlanningBudget budget) {
        if (model.recipes.size() > 128 || model.constraints.size() > 512 || conflicts.isEmpty() || !prepare()) return;
        try {
            retainCharge(budget, model.constraints.size() + model.recipes.size());
            Set<String> ids = new HashSet<>();
            for (var recipe : model.recipes) if (!ids.add(recipe.id())) return;
            long terms = 0;
            for (var row : model.constraints) {
                retainCharge(budget, 1);
                terms += row.terms().size();
                if (terms > 4096) return;
            }
            retainCharge(budget, terms);
            if (duplicate(model, conflicts, budget)) return;
            List<CountProof.Row> axioms = new ArrayList<>(model.constraints.stream().map(CountProof::row).toList());
            List<CountConflict> accepted = new ArrayList<>();
            List<List<CountProof.Row>> forbidden = new ArrayList<>();
            int attempts = 0;
            for (var conflict : conflicts) {
                if (attempts++ == 8) break;
                retainCharge(budget, conflict.assumptions().size());
                if (conflict.assumptions().stream().mapToLong(row -> row.terms().size()).sum() > 16) continue;
                for (var row : conflict.assumptions()) retainCharge(budget, 1L + row.terms().size());
                var trial = new ArrayList<>(forbidden);
                trial.add(conflict.assumptions().stream().map(CountProof::row).toList());
                var proof = new CountProof.Certificate("persistent_count_assumptions", model.recipes.size(), axioms, trial, List.of(), false);
                // Certifying a future cache entry is independently bounded. It
                // must never consume the current order's remaining search work.
                long allowance = Math.min(4096, budget.remainingCachePreparationWork());
                if (allowance <= 0) break;
                if (CountProof.verify(proof, allowance, units -> retainCharge(budget, units)) != CountProof.Verdict.VERIFIED) continue;
                accepted.add(conflict);
                forbidden = trial;
            }
            if (accepted.isEmpty()) return;
            var support = proofSupport(model, accepted, budget);
            var entry = capture(model, support, accepted, budget);
            if (entry.bytes > RESIDENT_BYTES) return;
            var previous = retained;
            List<Entry> entries = new ArrayList<>();
            entries.add(entry);
            long totalTerms = entry.terms, totalBytes = entry.bytes, removed = 0;
            for (var old : previous.entries) {
                retainCharge(budget, 1);
                if (old.columns.equals(entry.columns) && old.assumptions.equals(entry.assumptions)) continue;
                if (entries.size() >= 8 || totalTerms + old.terms > 8192 || totalBytes + old.bytes > RESIDENT_BYTES) {
                    removed++;
                    continue;
                }
                entries.add(old); totalTerms += old.terms; totalBytes += old.bytes;
            }
            var next = new Snapshot(List.copyOf(entries), totalTerms, totalBytes);
            retainCharge(budget, 1);
            synchronized (this) {
                retained = next;
                evictions += removed;
            }
        } catch (Stop ignored) {
            // Failed optional admission is neither a search limit nor a proof.
        } finally {
            synchronized (this) { preparing = false; }
        }
    }

    /** A second writer skips optional preparation instead of waiting for a proof. */
    private synchronized boolean prepare() {
        if (preparing) return false;
        preparing = true;
        return true;
    }

    private boolean duplicate(RecipeCountModel<?> model, List<CountConflict> conflicts, PlanningBudget budget) {
        var snapshot = retained;
        if (snapshot.entries.isEmpty()) return false;
        var matching = new Matching(model, units -> retainCharge(budget, units));
        List<CountConflict> proposed = new ArrayList<>();
        for (int i = 0; i < Math.min(8, conflicts.size()); i++) {
            var conflict = conflicts.get(i);
            retainCharge(budget, 1L + conflict.assumptions().size());
            long terms = conflict.assumptions().stream().mapToLong(row -> row.terms().size()).sum();
            if (terms <= 16) { retainCharge(budget, terms); proposed.add(conflict); }
        }
        if (proposed.isEmpty()) return false;
        for (var entry : snapshot.entries) {
            var matched = matching.match(entry);
            if (matched == null) continue;
            retainCharge(budget, proposed.size());
            if (!matched.conflicts.containsAll(proposed)) continue;
            if (matching.implies(matched.assumptions)) return true;
        }
        return false;
    }

    /** Remove premises only after an independent proof, retaining every term in every kept row. */
    private static List<ExactLinearProgram.Constraint> proofSupport(RecipeCountModel<?> model,
                                                                  List<CountConflict> conflicts, PlanningBudget budget) {
        List<ExactLinearProgram.Constraint> support = new ArrayList<>(model.constraints);
        BitSet involved = new BitSet(model.recipes.size());
        for (var conflict : conflicts) for (var row : conflict.assumptions())
            for (int id : row.terms().keySet()) involved.set(id);
        List<ExactLinearProgram.Constraint> adjacent = new ArrayList<>();
        for (var row : support) {
            retainCharge(budget, 1L + row.terms().size());
            if (row.terms().keySet().stream().anyMatch(involved::get)) adjacent.add(row);
        }
        if (adjacent.size() < support.size() && certifies(model.recipes.size(), adjacent, conflicts, budget)) support = adjacent;
        // A few deletion checks remove demand/unrelated-stock premises from
        // small cores. This is optional preparation, never current inference.
        for (int at = support.size() - 1, trials = 0; at >= 0 && trials < 16; at--, trials++) {
            if (budget.remainingCachePreparationWork() < 1024) break;
            var trial = new ArrayList<>(support); trial.remove(at);
            if (certifies(model.recipes.size(), trial, conflicts, budget)) support = trial;
        }
        return List.copyOf(support);
    }

    private static boolean certifies(int variables, List<ExactLinearProgram.Constraint> rows,
                                     List<CountConflict> conflicts, PlanningBudget budget) {
        long copy = rows.size() + conflicts.size();
        for (var row : rows) copy += row.terms().size();
        long available = budget.remainingCachePreparationWork();
        if (available < copy + 1024) return false;
        retainCharge(budget, copy);
        var proof = certificate("retained_support_check", variables, rows, conflicts);
        return CountProof.verify(proof, Math.min(2048, available - copy - 512), units -> retainCharge(budget, units)) == CountProof.Verdict.VERIFIED;
    }

    private static Entry capture(RecipeCountModel<?> model, List<ExactLinearProgram.Constraint> support,
                                 List<CountConflict> conflicts, PlanningBudget budget) {
        BitSet used = new BitSet(model.recipes.size());
        for (var row : support) {
            retainCharge(budget, 1L + row.terms().size());
            for (int id : row.terms().keySet()) used.set(id);
        }
        for (var conflict : conflicts) for (var row : conflict.assumptions()) {
            retainCharge(budget, 1L + row.terms().size());
            for (int id : row.terms().keySet()) used.set(id);
        }
        List<Column> columns = new ArrayList<>();
        for (int id = used.nextSetBit(0); id >= 0; id = used.nextSetBit(id + 1)) {
            retainCharge(budget, 1); columns.add(new Column(id, model.recipes.get(id).id()));
        }
        long terms = support.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 512L * (terms + columns.size()) + 256L * (support.size() + 1);
        for (var column : columns) bytes += 2L * column.recipe.length();
        for (var conflict : conflicts) for (var row : conflict.assumptions()) bytes += 512L * (1 + row.terms().size());
        return new Entry(List.copyOf(columns), List.copyOf(support), List.copyOf(conflicts), terms, bytes);
    }

    private static ExactLinearProgram.Constraint remap(ExactLinearProgram.Constraint row, int[] coordinates,
                                                       java.util.function.LongConsumer charge) {
        charge.accept(1L + row.terms().size());
        Map<Integer, BigInteger> terms = new TreeMap<>();
        for (var term : row.terms().entrySet()) {
            int id = coordinates[term.getKey()];
            if (id < 0) throw new IllegalArgumentException("Missing retained proof coordinate");
            terms.put(id, term.getValue());
        }
        return new ExactLinearProgram.Constraint(terms, row.upper());
    }

    private static CountProof.Certificate certificate(String scope, int variables, List<ExactLinearProgram.Constraint> rows,
                                                       List<CountConflict> conflicts) {
        return new CountProof.Certificate(scope, variables, rows.stream().map(CountProof::row).toList(),
                conflicts.stream().map(c -> c.assumptions().stream().map(CountProof::row).toList()).toList(), List.of(), false);
    }

    private static void chargeCopy(CountConflict conflict, PlanningBudget budget) {
        for (var row : conflict.assumptions()) budget.charge(1L + row.terms().size());
    }
}
