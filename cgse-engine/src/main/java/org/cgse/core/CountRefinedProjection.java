// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A positive-only count proposal with original-coordinate startup refinement.
 * A necessary implication has two alternatives: omit its recipe, or fund the
 * implication. Neither a failed projection nor a rejected execution proves that
 * either alternative is empty. The ordinary owner retains its original domain. */
final class CountRefinedProjection<K> implements AutoCloseable {
    private record Path(List<ExactLinearProgram.Constraint> extra, long bytes) {}

    private static final int MAX_BRANCHES = 64;
    private final RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private final long allowance;
    private final int expressionLimit;
    private final boolean wide;
    private final List<ExactLinearProgram.Constraint> original;
    private final ArrayDeque<Path> pending = new ArrayDeque<>();
    private Path path;
    private List<ExactLinearProgram.Constraint> rows;
    private CountDemandProjection projection;
    private CountExecution<K> execution;
    private BigInteger[] checking, result;
    private long memory, rowMemory, pointMemory, work;
    private int branches;
    private boolean done, rejected, refinePools, refined, validateInitial, evidenceEnabled, bare;

    CountRefinedProjection(RecipeCountModel<K> model, PlanningBudget budget, long allowance,
                           int expressionLimit, boolean wide) {
        this(model, budget, allowance, expressionLimit, wide, null);
    }

    CountRefinedProjection(RecipeCountModel<K> model, PlanningBudget budget, long allowance,
                           int expressionLimit, boolean wide, BigInteger[] initialCounts) {
        this.model = model; this.budget = budget; this.allowance = Math.max(0, allowance);
        this.expressionLimit = expressionLimit; this.wide = wide;
        long bytes = 512L + 16L * model.constraints.size() + 256L * model.recipes.size();
        if (allowance <= 0) {
            original = List.of(); done = true; result = initialCounts; bare = true; return;
        }
        if (!budget.tryReserve(bytes)) {
            // Refinement is optional. Its bookkeeping cannot deny the old
            // projection or a previously completed original count candidate.
            original = model.constraints; checking = initialCounts; bare = true; return;
        }
        memory = bytes;
        original = List.copyOf(model.constraints);
        path = new Path(List.of(), 0);
        checking = initialCounts == null ? null : initialCounts.clone();
        validateInitial = initialCounts != null;
    }

    boolean step() {
        if (done) return true;
        long before = budget.threadWork();
        try {
            budget.checkpoint();
            if (work >= allowance) return finish();
            if (bare) return stepBare();
            if (checking != null) {
                if (validateInitial) {
                    validateInitial = false;
                    if (!valid(checking)) return finish();
                }
                // Preserve the original executable fast path. A count vector
                // is only a proposal: the caller tries its ordinary physical
                // orders before requesting this more expensive separation.
                if (!evidenceEnabled) {
                    result = checking; checking = null;
                    return done = true;
                }
                if (execution == null) execution = new CountExecution<>(model, budget);
                if (refinePools) { execution.refine(model); refinePools = false; }
                CountGuard guard = condition(checking);
                if (guard != null) {
                    refined = true;
                    branch(guard);
                    checking = null; rejected = false;
                    return false;
                }
                if (rejected) {
                    // An ordering/assembly failure alone is not an execution
                    // conflict. Only already retained alternatives may follow.
                    checking = null; rejected = false;
                    releasePath();
                    return nextPath();
                }
                result = checking; checking = null;
                return done = true;
            }
            if (projection != null) {
                if (!projection.step()) return false;
                checking = projection.result(); projection.close(); projection = null;
                releaseRows();
                if (checking != null) return false;
                releasePath();
                return nextPath();
            }
            if (path == null && nextPath()) return true;
            long remaining = allowance - work;
            if (remaining < 1024) return finish();
            rowMemory = 128L + 16L * (original.size() + path.extra().size());
            if (!budget.tryReserve(rowMemory)) { rowMemory = 0; return finish(); }
            rows = new ArrayList<>(original);
            rows.addAll(path.extra());
            projection = new CountDemandProjection(model.recipes.size(), rows, budget,
                    remaining, expressionLimit, wide);
            return false;
        } finally { work += budget.threadWork() - before; }
    }

    private boolean stepBare() {
        if (checking != null) {
            result = checking; checking = null;
            return done = true;
        }
        if (projection == null) {
            projection = new CountDemandProjection(model.recipes.size(), original, budget,
                    allowance - work, expressionLimit, wide);
            return false;
        }
        if (!projection.step()) return false;
        result = projection.result(); projection.close(); projection = null;
        return done = true;
    }

    private CountGuard condition(BigInteger[] counts) {
        long bytes = 128L + 80L * counts.length;
        // Lack of optional evidence workspace means "no guard obtained",
        // never executable. The caller still owes full physical validation.
        if (!budget.tryReserve(bytes)) return null;
        pointMemory = bytes;
        try {
            var point = new ExactRational[counts.length];
            for (int i = 0; i < counts.length; i++) {
                budget.check(); point[i] = ExactRational.of(counts[i]);
            }
            CountGuard condition = execution.violated(point);
            if (condition != null) return condition;
            var startup = CountStartup.separate(model, counts, budget);
            return startup == null ? null : startup.guard();
        } finally { budget.release(pointMemory); pointMemory = 0; }
    }

    private boolean valid(BigInteger[] counts) {
        if (counts.length != model.recipes.size()) return false;
        for (var count : counts) {
            budget.check();
            if (count == null || count.signum() < 0) return false;
        }
        for (var row : original) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check(); total = total.add(term.getValue().multiply(counts[term.getKey()]));
            }
            if (total.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private void branch(CountGuard guard) {
        if (branches >= MAX_BRANCHES) { releasePath(); finish(); return; }
        // Preserve the entire disjunction. The active alternative must not be
        // silently turned into an unconditional stock or seed requirement.
        var absent = new ExactLinearProgram.Constraint(Map.of(guard.recipe(), BigInteger.ONE), BigInteger.ZERO);
        var present = new ExactLinearProgram.Constraint(Map.of(guard.recipe(), BigInteger.ONE.negate()), BigInteger.ONE.negate());
        List<ExactLinearProgram.Constraint> prefix = path.extra();
        Path without = extend(prefix, List.of(absent));
        if (without != null) pending.addFirst(without);
        Path funded = extend(prefix, List.of(present, guard.required()));
        if (funded != null) pending.addFirst(funded);
        releasePath();
        branches++;
        budget.note("count_refinement", "necessary_startup; recipe=" + guard.recipe() +
                "; branch=" + branches + "; alternatives=" + pending.size());
        nextPath();
    }

    private Path extend(List<ExactLinearProgram.Constraint> prefix, List<ExactLinearProgram.Constraint> extra) {
        long bytes = 192L + 16L * (prefix.size() + extra.size());
        // Each retained path owns its accounting even when immutable row
        // objects are shared with a parent that has already been released.
        for (var part : List.of(prefix, extra)) {
            for (var row : part) {
                budget.check(); bytes += 192L + 80L * row.terms().size();
                bytes += (row.upper().bitLength() + 7L) / 8;
                for (var coefficient : row.terms().values()) {
                    budget.check(); bytes += (coefficient.bitLength() + 7L) / 8;
                }
            }
        }
        if (!budget.tryReserve(bytes)) return null;
        var rows = new ArrayList<>(prefix);
        rows.addAll(extra);
        return new Path(List.copyOf(rows), bytes);
    }

    private boolean nextPath() {
        path = pending.pollFirst();
        return path == null && finish();
    }

    /** Continue only after the caller has tried its executable orders. A
     * timeout/failed order is not published as a count exclusion or a proof. */
    boolean reject() {
        if (bare || result == null || work >= allowance) return false;
        checking = result; result = null;
        rejected = true; refinePools = true; evidenceEnabled = true; done = false;
        return true;
    }

    boolean refined() { return refined; }
    BigInteger[] result() { return result; }

    private boolean finish() { done = true; result = null; return true; }

    private void releaseRows() {
        rows = null; budget.release(rowMemory); rowMemory = 0;
    }

    private void releasePath() {
        if (path != null) budget.release(path.bytes());
        path = null;
    }

    @Override public void close() {
        if (projection != null) projection.close(); projection = null;
        if (execution != null) execution.close(); execution = null;
        releasePath();
        for (var queued : pending) budget.release(queued.bytes());
        pending.clear();
        releaseRows();
        budget.release(memory + pointMemory); memory = pointMemory = 0;
        checking = result = null; done = true;
    }
}
