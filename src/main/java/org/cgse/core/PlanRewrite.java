// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongConsumer;

/** Replace leaves once per shared node, preserving order and unchanged program identities. */
final class PlanRewrite {

    private PlanRewrite() {}

    private static final class Frame {

        final PlanStep step;
        int next;

        Frame(PlanStep step) {
            this.step = step;
        }

        PlanStep child() {
            if (step instanceof PlanStep.Repeat repeat) return next++ == 0 ? repeat.body() : null;
            if (step instanceof PlanStep.Sequence sequence && next < sequence.children().size()) return sequence.children().get(next++);
            return null;
        }
    }

    /** Replacement bodies are already compiled; only the original program is traversed. */
    static PlanStep batches(PlanStep root, Function<PlanStep.Batch, PlanStep> replace, PlanningBudget budget) {
        return batches(root, replace, budget, ignored -> {});
    }

    /** Transfer successful result reservations to the owning strategy's lifetime. */
    static PlanStep batches(PlanStep root, Function<PlanStep.Batch, PlanStep> replace, PlanningBudget budget, LongConsumer retain) {
        var memo = new IdentityHashMap<PlanStep, PlanStep>();
        var stack = new ArrayDeque<Frame>();
        long workspace = 0, retained = 0;
        boolean complete = false;
        try {
            budget.reserve(128);
            workspace += 128;
            stack.push(new Frame(root));
            while (!stack.isEmpty()) {
                budget.check();
                Frame frame = stack.peek();
                PlanStep child = frame.child();
                if (child != null) {
                    if (!memo.containsKey(child)) {
                        budget.reserve(128);
                        workspace += 128;
                        stack.push(new Frame(child));
                    }
                    continue;
                }
                PlanStep result = frame.step;
                if (frame.step instanceof PlanStep.Batch batch) result = Objects.requireNonNull(replace.apply(batch));
                else if (frame.step instanceof PlanStep.Repeat repeat) {
                    PlanStep body = memo.get(repeat.body());
                    if (body != repeat.body()) {
                        budget.reserve(64);
                        retained += 64;
                        result = new PlanStep.Repeat(body, repeat.times());
                    }
                } else if (frame.step instanceof PlanStep.Sequence sequence) {
                    ArrayList<PlanStep> children = null;
                    for (int i = 0; i < sequence.children().size(); i++) {
                        budget.check();
                        PlanStep original = sequence.children().get(i), replacement = memo.get(original);
                        if (children == null && original != replacement) {
                            long bytes = 64L + 8L * sequence.children().size();
                            budget.reserve(bytes);
                            retained += bytes;
                            children = new ArrayList<>(sequence.children().size());
                            children.addAll(sequence.children().subList(0, i));
                        }
                        if (children != null) children.add(replacement);
                    }
                    if (children != null) result = new PlanStep.Sequence(children);
                }
                memo.put(frame.step, result);
                stack.pop();
            }
            retain.accept(retained);
            complete = true;
            return memo.get(root);
        } finally {
            budget.release(workspace);
            if (!complete) budget.release(retained);
        }
    }
}
