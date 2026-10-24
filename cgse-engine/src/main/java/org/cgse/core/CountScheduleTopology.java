// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Sparse recipe/material SCCs of one proposed multiset, never shared between requests. */
final class CountScheduleTopology<K> implements AutoCloseable {
    private final RecipeCountModel<K> model;
    private final BigInteger[] counts;
    private final PlanningBudget budget;
    private final Map<K, Integer> keys = new HashMap<>();
    private final List<List<Integer>> forward = new ArrayList<>(), reverse = new ArrayList<>();
    private final ArrayList<Integer> finish = new ArrayList<>();
    private int[] stack, edge, component;
    private byte[] seen;
    private int phase, recipe, root, depth, nextComponent;
    private long memory;
    private boolean done;

    CountScheduleTopology(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget) {
        this(model, counts, budget, false);
    }

    CountScheduleTopology(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget,
                          boolean allowExternalConfiguration) {
        this.model = model; this.counts = counts; this.budget = budget;
        if (!allowExternalConfiguration) {
            if (model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) { done = true; return; }
        } else {
            // Unused recipes and unlimited configuration supplies introduce no
            // finite per-push resource constraint in this proposed multiset.
            for (int i = 0; i < counts.length; i++) if (counts[i].signum() > 0) {
                for (var slot : model.recipes.get(i).slots()) {
                    budget.check();
                    if (slot.configuration() && !slot.reusable() && !model.external.contains(slot.key())) {
                        done = true; return;
                    }
                }
            }
        }
        // Include coproduct keys outside the backward constrained closure.
        long ports = 0;
        for (int i = 0; i < counts.length; i++) {
            budget.check();
            if (counts[i].signum() > 0) ports += model.recipes.get(i).inputs().size() + (long) model.recipes.get(i).outputs().size();
        }
        long bytes = 2048L + 160L * counts.length + 320L * ports;
        if (!budget.tryReserve(bytes)) { done = true; return; }
        memory = bytes;
        for (int i = 0; i < counts.length; i++) { forward.add(new ArrayList<>()); reverse.add(new ArrayList<>()); }
    }

    boolean step() {
        if (done) return true;
        for (int operation = 0; operation < 64; operation++) {
            budget.check();
            if (phase == 0) {
                if (recipe < counts.length) {
                    int id = recipe++;
                    if (counts[id].signum() == 0) continue;
                    var rule = model.recipes.get(id);
                    for (K key : rule.inputs().keySet()) { budget.check(); if (!model.external.contains(key)) link(node(key), id); }
                    for (K key : rule.outputs().keySet()) { budget.check(); if (!model.external.contains(key)) link(id, node(key)); }
                } else {
                    int n = forward.size(); stack = new int[n]; edge = new int[n]; component = new int[n]; seen = new byte[n];
                    phase = 1;
                }
            } else if (phase == 1) {
                if (depth == 0) {
                    if (root == forward.size()) { phase = 2; root = finish.size() - 1; continue; }
                    int id = root++; if (seen[id] == 0) { seen[id] = 1; stack[depth++] = id; }
                } else {
                    int id = stack[depth - 1];
                    if (edge[id] == forward.get(id).size()) { finish.add(id); depth--; }
                    else { int child = forward.get(id).get(edge[id]++); if (seen[child] == 0) { seen[child] = 1; stack[depth++] = child; } }
                }
            } else {
                if (depth == 0) {
                    if (root < 0) return done = true;
                    int id = finish.get(root--);
                    if (seen[id] != 2) { nextComponent++; visit(id); }
                } else {
                    int id = stack[depth - 1];
                    if (edge[id] == reverse.get(id).size()) depth--;
                    else { int child = reverse.get(id).get(edge[id]++); if (seen[child] != 2) visit(child); }
                }
            }
        }
        return false;
    }

    private int node(K key) {
        Integer old = keys.get(key);
        if (old != null) return old;
        int id = forward.size(); keys.put(key, id);
        forward.add(new ArrayList<>()); reverse.add(new ArrayList<>());
        return id;
    }

    private void link(int from, int to) { forward.get(from).add(to); reverse.get(to).add(from); }
    private void visit(int id) { seen[id] = 2; component[id] = nextComponent; edge[id] = 0; stack[depth++] = id; }

    int[] result() { return component == null ? null : Arrays.copyOf(component, counts.length); }

    int component(K key) {
        Integer node = keys.get(key);
        return component == null || node == null ? -1 : component[node];
    }

    @Override public void close() { budget.release(memory); memory = 0; }
}
