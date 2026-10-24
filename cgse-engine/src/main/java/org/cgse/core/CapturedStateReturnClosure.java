// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Bounded exact closure of the original return callbacks. Guaranteed-use
 * counts are lower bounds, so absence of feedback is checked on the graph. */
final class CapturedStateReturnClosure<K> implements AutoCloseable {
    private record Frame<K>(K state, Iterator<StateFamily<K>> rules) {}
    private static final class Node {
        final int index;
        int low;
        boolean stacked = true;
        Node(int index) { this.index = this.low = index; }
    }
    private final Map<StateFamily<K>, List<CapturedRecipe.Candidate<K>>> states;
    private final PlanningBudget budget;
    private final Iterator<List<CapturedRecipe.Candidate<K>>> rules;
    private Iterator<CapturedRecipe.Candidate<K>> transitions;
    private final Set<K> seen = new HashSet<>(), returned = new HashSet<>(), cyclic = new HashSet<>();
    private final Deque<K> pending = new ArrayDeque<>();
    private final Deque<Frame<K>> frames = new ArrayDeque<>();
    private final Map<K, Node> nodes = new HashMap<>();
    private final Deque<K> component = new ArrayDeque<>();
    private long memory;
    private K componentRoot, firstMember;
    private boolean done, complete;

    CapturedStateReturnClosure(Map<StateFamily<K>, List<CapturedRecipe.Candidate<K>>> states,
                              PlanningBudget budget) {
        this.states = states; this.budget = budget; rules = states.values().iterator();
    }

    boolean step() {
        for (int work = 0; work < 16 && !done; work++) advance();
        return done;
    }

    private void advance() {
        budget.compilationCheck();
        if (componentRoot != null) {
            K state = component.removeLast(); nodes.get(state).stacked = false;
            if (firstMember == null) firstMember = state;
            else {
                markCycle(firstMember); markCycle(state);
            }
            if (state.equals(componentRoot)) { componentRoot = firstMember = null; }
        } else if (transitions != null) {
            if (transitions.hasNext()) {
                var transition = transitions.next();
                remember(transition.stack().what(), true); rememberReturn(transition.remaining(), true);
            } else transitions = null;
        } else if (rules.hasNext()) transitions = rules.next().iterator();
        else {
            if (frames.isEmpty()) {
                if (pending.isEmpty()) { complete = done = true; return; }
                K next = pending.removeFirst();
                if (nodes.containsKey(next)) return;
                enter(next);
                if (done) return;
            }
            var frame = frames.peekLast();
            if (!frame.rules().hasNext()) {
                frames.removeLast();
                var node = nodes.get(frame.state());
                if (!frames.isEmpty()) {
                    var parent = nodes.get(frames.peekLast().state()); parent.low = Math.min(parent.low, node.low);
                }
                if (node.low == node.index) {
                    if (component.peekLast().equals(frame.state())) {
                        // Ordinary wear is usually a long DAG of singleton
                        // components. Finish that constant-size case inline.
                        component.removeLast(); node.stacked = false;
                    } else componentRoot = frame.state();
                }
                return;
            }
            var rule = frame.rules().next();
            if (!rule.accepts(frame.state()) || rule.capacity(frame.state()) <= 0) return;
            K after = rule.nominalAfterOne(frame.state());
            rememberReturn(after, false);
            if (done || after == null || after.equals(frame.state())) return;
            var child = nodes.get(after);
            if (child == null) enter(after);
            else if (child.stacked) {
                var node = nodes.get(frame.state()); node.low = Math.min(node.low, child.index);
            }
        }
    }

    private void remember(K state, boolean enqueue) {
        if (state == null || seen.contains(state)) return;
        if (seen.size() == 32768 || !reserve(96)) { done = true; return; }
        seen.add(state); if (enqueue) pending.addLast(state);
    }
    private void rememberReturn(K state, boolean enqueue) {
        remember(state, enqueue);
        if (done || state == null || returned.contains(state)) return;
        if (reserve(48)) returned.add(state);
    }
    private void enter(K state) {
        if (!reserve(128)) return;
        nodes.put(state, new Node(nodes.size())); component.addLast(state);
        frames.addLast(new Frame<>(state, states.keySet().iterator()));
    }
    private void markCycle(K key) {
        if (!cyclic.contains(key) && reserve(48)) cyclic.add(key);
    }
    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { done = true; return false; }
        memory += bytes; return true;
    }
    boolean complete() { return complete; }
    Iterator<K> cycles() { return cyclic.iterator(); }
    boolean returns(K key) { return returned.contains(key); }
    @Override public void close() {
        budget.release(memory); memory = 0;
        transitions = null; componentRoot = firstMember = null;
        seen.clear(); returned.clear(); cyclic.clear(); pending.clear(); nodes.clear(); frames.clear(); component.clear();
    }
}
