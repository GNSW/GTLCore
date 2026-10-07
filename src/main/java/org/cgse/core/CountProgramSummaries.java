package org.cgse.core;

import java.util.*;

/** Exact ordered programs, including batch boundaries, within one immutable recipe model. */
final class CountProgramSummaries<K> implements AutoCloseable {

    private record Entry<K>(SequenceSummary<K> summary, long bytes) {}

    private record Visit(PlanStep step, int depth) {}

    private final PlanningBudget budget;
    private final LinkedHashMap<PlanStep, Entry<K>> entries = new LinkedHashMap<>();

    CountProgramSummaries(PlanningBudget budget) {
        this.budget = budget;
    }

    private long size(PlanStep program) {
        Deque<Visit> pending = new ArrayDeque<>();
        pending.add(new Visit(program, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            budget.check();
            Visit visit = pending.removeLast();
            if (++nodes > 2048 || visit.depth > 64) return 0;
            if (visit.step instanceof PlanStep.Sequence s) {
                if (pending.size() + s.children().size() > 2048) return 0;
                for (PlanStep child : s.children()) pending.add(new Visit(child, visit.depth + 1));
            } else if (visit.step instanceof PlanStep.Repeat r) pending.add(new Visit(r.body(), visit.depth + 1));
        }
        return 256 + 160L * nodes;
    }

    synchronized SequenceSummary<K> get(PlanStep program) {
        if (entries.isEmpty() || size(program) == 0) return null;
        var entry = entries.get(program);
        if (entry != null) budget.note("count_program_reuse", "exact_order_and_batch_boundaries; same_request_recipes");
        return entry == null ? null : entry.summary;
    }

    synchronized void put(PlanStep program, SequenceSummary<K> summary) {
        if (budget.availableBytes() < budget.reservedBytes()) return;
        long bytes = size(program);
        if (bytes == 0 || entries.containsKey(program)) return;
        bytes += 384L * (summary.required().size() + summary.delta().size() + summary.peak().size());
        for (var amounts : List.of(summary.required(), summary.delta(), summary.peak()))
            for (var amount : amounts.values()) {
                budget.check();
                bytes += (amount.bitLength() + 7L) / 8;
            }
        if (bytes > budget.availableBytes() / 16 || !budget.tryReserve(bytes)) return;
        if (entries.size() == 16) {
            var iterator = entries.values().iterator();
            budget.release(iterator.next().bytes);
            iterator.remove();
        }
        entries.put(program, new Entry<>(summary, bytes));
    }

    synchronized void trim() {
        if (budget.availableBytes() < budget.reservedBytes()) close();
    }

    @Override
    public synchronized void close() {
        entries.values().forEach(e -> budget.release(e.bytes));
        entries.clear();
    }
}
