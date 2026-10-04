package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Request-scoped, exclusively leased fixed-multiset work; never a negative result cache. */
final class CountScheduleContinuations<K> implements AutoCloseable {

    private final RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private final List<CountSchedule<K>> paused = new ArrayList<>();
    private final LinkedHashMap<List<BigInteger>, Witness> witnesses = new LinkedHashMap<>();
    private final boolean sequential;
    final CountProgramSummaries<K> programs;

    private record Witness(PlanStep program, long bytes) {}

    CountScheduleContinuations(RecipeCountModel<K> model, PlanningBudget budget) {
        this.model = model;
        this.budget = budget;
        sequential = model.recipes.stream().noneMatch(GraphRecipe::batchSensitiveInputs);
        programs = new CountProgramSummaries<>(budget);
    }

    synchronized CountSchedule<K> acquire(BigInteger[] counts) {
        for (var iterator = paused.iterator(); iterator.hasNext();) {
            CountSchedule<K> result = iterator.next();
            if (!result.sameCounts(counts)) continue;
            iterator.remove();
            budget.note("count_schedule_reuse", "resumed; exact_original_counts; same_request_model");
            return result;
        }
        // Optional retained work must give way before admitting another
        // scheduler when the shared request is already using most of its memory.
        trim();
        return new CountSchedule<>(model, counts, budget).retainWitnessForAssembly();
    }

    synchronized boolean retain(CountSchedule<K> schedule) {
        budget.checkpoint();
        if (!schedule.resumableIn(model, budget) || budget.availableBytes() < budget.reservedBytes()) return false;
        // A supplied macro program bypasses this pool. Batch grouping is also
        // semantic: do not identify batch-sensitive candidates by counts alone.
        if (!sequential) return false;
        if (paused.contains(schedule)) return true;
        // Leases are removed on acquisition, so parallel workers never mutate
        // one continuation concurrently. Reservations remain with the owner.
        if (paused.size() == 4) paused.remove(0).close();
        paused.add(schedule);
        budget.note("count_schedule_reuse", "paused; next_duplicate_can_continue");
        return true;
    }

    synchronized void trim() {
        if (budget.availableBytes() < budget.reservedBytes()) close();
    }

    @Override
    public synchronized void close() {
        paused.forEach(CountSchedule::close);
        paused.clear();
        witnesses.values().forEach(w -> budget.release(w.bytes));
        witnesses.clear();
        programs.close();
    }

    synchronized PlanStep witness(BigInteger[] counts) {
        if (!sequential) return null;
        Witness result = witnesses.get(Arrays.asList(counts));
        if (result == null) return null;
        budget.note("count_schedule_reuse", "positive_program; same_request_model; assembly_and_verification_required");
        return result.program;
    }

    synchronized void remember(BigInteger[] counts, PlanStep program) {
        // Call only after the complete candidate passes seed/force assembly
        // and final verification. A schedulable prefix alone is insufficient.
        try {
            rememberWithinBudget(counts, program);
        } catch (PlanningBudget.Exhausted exhausted) {
            // Optional reuse cannot discard an already verified witness. The
            // shared budget stays exhausted, stopping any later mandatory work.
            budget.note("count_schedule_reuse", "verified_witness_cache_declined; " + exhausted.limit());
        }
    }

    private void rememberWithinBudget(BigInteger[] counts, PlanStep program) {
        if (!sequential || budget.availableBytes() < budget.reservedBytes()) return;
        List<BigInteger> key = List.copyOf(Arrays.asList(counts));
        if (witnesses.containsKey(key)) return;
        long bytes = 256 + counts.length * 64L;
        for (var count : counts) {
            budget.check();
            bytes += (count.bitLength() + 7L) / 8;
        }
        Deque<PlanStep> pending = new ArrayDeque<>();
        pending.add(program);
        int nodes = 0;
        while (!pending.isEmpty()) {
            budget.check();
            if (++nodes > 4096) return;
            PlanStep next = pending.removeLast();
            bytes += 128;
            if (next instanceof PlanStep.Sequence s) {
                if (pending.size() + s.children().size() > 4096) return;
                pending.addAll(s.children());
            } else if (next instanceof PlanStep.Repeat r) pending.add(r.body());
        }
        if (bytes > budget.availableBytes() / 16 || !budget.tryReserve(bytes)) return;
        if (witnesses.size() == 16) {
            var iterator = witnesses.values().iterator();
            budget.release(iterator.next().bytes);
            iterator.remove();
        }
        witnesses.put(key, new Witness(program, bytes));
    }
}
