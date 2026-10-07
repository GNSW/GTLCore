package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Persistent assumptions/learned cores owned by one immutable recipe catalog. */
final class CountSessions {

    private record Entry(List<String> recipes, List<ExactLinearProgram.Constraint> assumptions,
                         List<CountConflict> conflicts, CountProof.Certificate proof, long terms) {}

    private final Deque<Entry> entries = new ArrayDeque<>();

    synchronized <K> List<CountConflict> reuse(RecipeCountModel<K> model, PlanningBudget budget) {
        if (entries.isEmpty() || model.recipes.size() > 128) return List.of();
        budget.charge(model.recipes.size());
        var ids = model.recipes.stream().map(GraphRecipe::id).toList();
        Map<Map<Integer, BigInteger>, BigInteger> bounds = new HashMap<>();
        for (var row : model.constraints) {
            budget.charge(1L + row.terms().size());
            bounds.merge(row.terms(), row.upper(), BigInteger::min);
        }
        int rechecked = 0;
        for (var entry : entries) {
            if (!entry.recipes.equals(ids)) continue;
            boolean applicable = true;
            for (var row : entry.assumptions) {
                budget.check();
                var current = bounds.get(row.terms());
                if (current == null || current.compareTo(row.upper()) > 0) {
                    applicable = false;
                    break;
                }
            }
            if (!applicable) {
                // A different request or an unrelated inventory increase may
                // weaken an unused old assumption. Reuse requires a NEW proof
                // against this model, never trust the stale failed boundary.
                if (rechecked++ < 2 && revalidate(model, entry, budget)) return entry.conflicts;
                continue;
            }
            if (budget.proofJournal() != null) budget.proofJournal().add(entry.proof);
            budget.note("count_session", "reused_cores=" + entry.conflicts.size() + "; assumptions_rechecked");
            return entry.conflicts;
        }
        return List.of();
    }

    private <K> boolean revalidate(RecipeCountModel<K> model, Entry entry, PlanningBudget budget) {
        if (model.recipes.size() > 128 || model.constraints.size() > 512 || budget.remainingWork() < 32768) return false;
        long terms = model.constraints.stream().mapToLong(row -> row.terms().size()).sum();
        if (terms > 4096) return false;
        long bytes = 1024L + 192L * model.constraints.size() + 128L * terms;
        if (bytes > budget.availableBytes() / 16 || !budget.tryReserve(bytes)) return false;
        try {
            budget.charge(terms + model.constraints.size());
            for (var conflict : entry.conflicts) chargeCopy(conflict, budget);
            var proof = new CountProof.Certificate("revalidated_count_assumptions", model.recipes.size(),
                    model.constraints.stream().map(CountProof::row).toList(),
                    entry.conflicts.stream().map(c -> c.assumptions().stream().map(CountProof::row).toList()).toList(), List.of(), false);
            if (CountProof.verify(proof, 4096, budget::charge) != CountProof.Verdict.VERIFIED) return false;
            if (budget.proofJournal() != null) budget.proofJournal().add(proof);
            budget.note("count_session", "revalidated_cores=" + entry.conflicts.size() + "; current_model_certificate");
            return true;
        } finally {
            budget.release(bytes);
        }
    }

    synchronized <K> void remember(RecipeCountModel<K> model, List<CountConflict> conflicts, PlanningBudget budget) {
        if (model.recipes.size() > 128 || model.constraints.size() > 512 || conflicts.isEmpty() || budget.remainingWork() < 32768) return;
        long terms = model.constraints.stream().mapToLong(row -> row.terms().size()).sum();
        if (terms > 4096) return;
        budget.charge(terms + model.constraints.size() + model.recipes.size());
        List<CountProof.Row> axioms = new ArrayList<>(model.constraints.stream().map(CountProof::row).toList());
        for (int i = 0; i < model.recipes.size(); i++) axioms.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), BigInteger.ZERO));
        List<CountConflict> accepted = new ArrayList<>();
        List<List<CountProof.Row>> forbidden = new ArrayList<>();
        int attempts = 0;
        for (var conflict : conflicts) {
            if (attempts++ == 8 || budget.remainingWork() < 8192) break;
            if (conflict.assumptions().stream().mapToLong(row -> row.terms().size()).sum() > 16) continue;
            chargeCopy(conflict, budget);
            var trial = new ArrayList<>(forbidden);
            trial.add(conflict.assumptions().stream().map(CountProof::row).toList());
            var proof = new CountProof.Certificate("persistent_count_assumptions", model.recipes.size(), axioms, trial, List.of(), false);
            // Execution-only or unavailable derivations are not transferred.
            // This separate checker certifies the entire retained core chain.
            if (CountProof.verify(proof, 4096, budget::charge) != CountProof.Verdict.VERIFIED) continue;
            accepted.add(conflict);
            forbidden = trial;
        }
        if (accepted.isEmpty()) return;
        var proof = new CountProof.Certificate("persistent_count_assumptions", model.recipes.size(), axioms, forbidden, List.of(), false);
        var entry = new Entry(model.recipes.stream().map(GraphRecipe::id).toList(), List.copyOf(model.constraints), List.copyOf(accepted), proof, terms);
        entries.removeIf(old -> old.recipes.equals(entry.recipes) && old.assumptions.equals(entry.assumptions));
        entries.addFirst(entry);
        while (entries.size() > 8 || entries.stream().mapToLong(Entry::terms).sum() > 8192) entries.removeLast();
    }

    private static void chargeCopy(CountConflict conflict, PlanningBudget budget) {
        for (var row : conflict.assumptions()) budget.charge(1L + row.terms().size());
    }
}
