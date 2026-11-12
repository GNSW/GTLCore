// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/**
 * Resumable, inventory-independent domain selection. A target-first DFS orders
 * materials after their candidate inputs. All physical coproducts of an active
 * recipe reserve their position while its inputs are visited; producer back
 * edges through those coproducts are deferred without losing their closure.
 * A recipe is retained only when EVERY
 * physical output follows EVERY input in that one strict order. Thus even a
 * different integer combination cannot recreate a production cycle. Read arcs
 * remain inputs; only virtual reusable returns are absent from executionOutputs.
 *
 * This is a deterministic acyclic subset, not an exhaustive union of individually
 * acyclic plans. In a strongly connected region it preserves compatible source
 * alternatives instead of deleting the whole region. Work and storage are linear
 * in visited recipe ports; the all-input/all-output test uses extrema, not pairs.
 */
public final class GraphDomainCompilation<K> implements AutoCloseable {
    private final GraphCompiler<K> compiler;
    private final PlanningBudget budget;
    private final Iterator<K> roots;
    private final Map<K, Integer> order = new LinkedHashMap<>();
    private final Set<String> seenRecipes = new HashSet<>();
    private final List<GraphRecipe<K>> recipes = new ArrayList<>();
    private final List<GraphRecipe<K>> admitted = new ArrayList<>();
    private final Deque<Frame<K>> stack = new ArrayDeque<>();
    private final Map<K, List<GraphRecipe<K>>> producers = new LinkedHashMap<>();
    private final Set<K> extraOutputs = new LinkedHashSet<>();
    private final Map<K, Integer> activeOutputs = new HashMap<>();
    private final Set<K> deferredKeys = new HashSet<>();
    private final Deque<K> deferred = new ArrayDeque<>();
    private Iterator<K> inputs, outputs, extras;
    private Iterator<Map.Entry<K, List<GraphRecipe<K>>>> freezing;
    private GraphRecipe<K> current;
    private GraphCompiler<K> result;
    private int phase, cursor, rank, maxInput, minOutput;
    private long memory;
    private boolean closed;

    private static final class Frame<K> {
        final K key;
        final Iterator<GraphRecipe<K>> recipes;
        Iterator<K> inputs = Collections.emptyIterator();
        Iterator<K> outputs = Collections.emptyIterator();
        Iterator<K> releasing = Collections.emptyIterator();
        GraphRecipe<K> current;
        Frame(K key, List<GraphRecipe<K>> recipes) {
            this.key = key;
            this.recipes = recipes.iterator();
        }
    }

    GraphDomainCompilation(GraphCompiler<K> compiler, K target, Set<K> additional,
                           GraphMode mode, PlanningBudget budget) {
        this.compiler = Objects.requireNonNull(compiler);
        this.budget = Objects.requireNonNull(budget);
        Objects.requireNonNull(mode);
        // Do not copy an arbitrarily large root set before admitting its memory.
        roots = new Iterator<>() {
            boolean first = true;
            final Iterator<K> rest = additional.iterator();
            public boolean hasNext() { return first || rest.hasNext(); }
            public K next() { if (first) { first = false; return target; } return rest.next(); }
        };
        if (mode == GraphMode.FULL) {
            if (compiler.mode() != GraphMode.FULL)
                throw new IllegalArgumentException("A restricted compiler cannot recover the FULL search model");
            result = compiler;
        }
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    private void enter(K key) {
        if (order.containsKey(key)) return;
        if (activeOutputs.containsKey(key)) {
            // This dependency is a coproduct of an ancestor recipe whose inputs
            // are still being ordered. Visiting it now would rank the back edge
            // before that recipe and discard an otherwise ordinary source route.
            // Keep its whole producer closure, but explore it after this route.
            if (!deferredKeys.contains(key)) {
                reserve(96);
                deferredKeys.add(key);
                deferred.addLast(key);
            }
            return;
        }
        reserve(192);
        order.put(key, -1);
        stack.push(new Frame<>(key, compiler.producers(key)));
    }

    /** One bounded port/state transition; true once the immutable compiler exists. */
    public boolean step() {
        if (closed) throw new IllegalStateException("Domain compilation closed");
        try {
            if (result != null) return true;
            budget.phase(PlanningBudget.Phase.BUILD);
            budget.compilationCheck();
            switch (phase) {
                case 0 -> traverse();
                case 1 -> {
                    if (extras.hasNext()) {
                        K key = extras.next();
                        if (!order.containsKey(key)) { reserve(64); order.put(key, rank++); }
                    } else { phase = 2; }
                }
                case 2 -> filter();
                case 3 -> {
                    if (freezing.hasNext()) {
                        var entry = freezing.next();
                        // Lists remain privately owned and are never mutated after publication.
                        entry.setValue(Collections.unmodifiableList(entry.getValue()));
                    } else {
                        reserve(16L * admitted.size());
                        result = new GraphCompiler<>(admitted, Collections.unmodifiableMap(producers),
                                compiler.verificationCatalog());
                        budget.note("recipe_domain", "ACYCLIC; closure=" + recipes.size() +
                                "; admitted=" + admitted.size() + "; materials=" + order.size());
                        return true;
                    }
                }
                case 4 -> {
                    if (outputs.hasNext()) {
                        K key = outputs.next();
                        reserve(producers.containsKey(key) ? 16 : 112);
                        producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(current);
                    } else { current = null; phase = 2; }
                }
                default -> throw new IllegalStateException();
            }
            return false;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void traverse() {
        if (stack.isEmpty()) {
            if (roots.hasNext()) enter(roots.next());
            else if (!deferred.isEmpty()) enter(deferred.removeFirst());
            else { extras = extraOutputs.iterator(); phase = 1; }
            return;
        }
        var frame = stack.peek();
        // Register all physical outputs BEFORE visiting any input, including
        // coproducts that are not the key through which this recipe was found.
        if (frame.outputs.hasNext()) {
            K key = frame.outputs.next();
            if (!extraOutputs.contains(key)) { reserve(64); extraOutputs.add(key); }
            if (!activeOutputs.containsKey(key)) reserve(64);
            activeOutputs.merge(key, 1, Integer::sum);
            return;
        }
        if (frame.inputs.hasNext()) { enter(frame.inputs.next()); return; }
        if (frame.current != null) {
            frame.releasing = frame.current.executionOutputs().keySet().iterator();
            frame.current = null;
        }
        if (frame.releasing.hasNext()) {
            K key = frame.releasing.next();
            int count = activeOutputs.get(key);
            if (count == 1) activeOutputs.remove(key);
            else activeOutputs.put(key, count - 1);
            return;
        }
        if (frame.recipes.hasNext()) {
            var recipe = frame.recipes.next();
            if (!seenRecipes.contains(recipe.id())) {
                reserve(96);
                seenRecipes.add(recipe.id());
                recipes.add(recipe);
                frame.current = recipe;
                frame.inputs = recipe.inputs().keySet().iterator();
                frame.outputs = recipe.executionOutputs().keySet().iterator();
            }
            return;
        }
        order.put(frame.key, rank++);
        stack.pop();
    }

    private void filter() {
        if (current == null) {
            if (cursor == recipes.size()) {
                freezing = producers.entrySet().iterator(); phase = 3; return;
            }
            current = recipes.get(cursor++);
            inputs = current.inputs().keySet().iterator();
            outputs = current.executionOutputs().keySet().iterator();
            maxInput = -1;
            minOutput = Integer.MAX_VALUE;
            return;
        }
        if (inputs.hasNext()) { maxInput = Math.max(maxInput, order.get(inputs.next())); return; }
        if (outputs.hasNext()) { minOutput = Math.min(minOutput, order.get(outputs.next())); return; }
        if (maxInput >= minOutput) { current = null; return; }
        // Publication indexing is itself sliced one physical output at a time.
        reserve(16);
        admitted.add(current);
        outputs = current.executionOutputs().keySet().iterator();
        phase = 4;
    }

    public GraphCompiler<K> result() {
        if (result == null) throw new IllegalStateException("Domain compilation incomplete");
        return result;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        budget.release(memory);
        memory = 0;
        order.clear(); seenRecipes.clear(); recipes.clear(); stack.clear(); extraOutputs.clear();
        activeOutputs.clear(); deferredKeys.clear(); deferred.clear();
        // The compiler owns an immutable copy of admitted, and borrows the
        // frozen producer map. Never clear that published producer map.
        admitted.clear();
        if (result == null) producers.clear();
        inputs = outputs = extras = null;
        freezing = null;
        current = null;
    }
}
