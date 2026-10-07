package org.cgse.core;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable source decisions shared by sibling branches of one order. */
final class GraphSourceChoices<K> extends AbstractMap<K, Integer> {

    private static final int MAX_DEPTH = 32;

    private final Map<K, Integer> base;
    private final GraphSourceChoices<K> parent;
    private final K key;
    private final Integer value;
    private final int size, hash, depth;

    private GraphSourceChoices(Map<K, Integer> source) {
        base = Map.copyOf(source);
        parent = null;
        key = null;
        value = null;
        size = base.size();
        hash = base.hashCode();
        depth = 0;
    }

    private GraphSourceChoices(GraphSourceChoices<K> parent, K key, Integer value, Integer previous) {
        base = null;
        this.parent = parent;
        this.key = key;
        this.value = value;
        size = parent.size + (previous == null ? 0 : -1) + (value == null ? 0 : 1);
        hash = parent.hash - (previous == null ? 0 : key.hashCode() ^ previous.hashCode()) +
                (value == null ? 0 : key.hashCode() ^ value.hashCode());
        depth = parent.depth + 1;
    }

    static <K> GraphSourceChoices<K> retain(Map<K, Integer> source) {
        return source instanceof GraphSourceChoices<K> shared ? shared : new GraphSourceChoices<>(source);
    }

    static <K> Map<K, Integer> changed(Map<K, Integer> source, K key, int choice) {
        Objects.requireNonNull(key);
        if (choice < 0) throw new IllegalArgumentException("Negative source choice");
        Integer value = choice == 0 ? null : choice;
        Integer previous = source.get(key);
        if (Objects.equals(previous, value)) return source;
        if (source instanceof GraphSourceChoices<K> shared && shared.depth < MAX_DEPTH)
            return new GraphSourceChoices<>(shared, key, value, previous);
        // Bound lookup depth. Existing snapshots and their descendants remain
        // immutable; only this branch pays for a new materialized root.
        Map<K, Integer> changed = new LinkedHashMap<>(source);
        if (value == null) changed.remove(key);
        else changed.put(key, value);
        return new GraphSourceChoices<>(changed);
    }

    long retainedBytes() {
        // Ancestors stay owned by GraphPlanningWork.seen until order close.
        return parent == null ? 256L + 48L * size : 288L;
    }

    @Override
    public Integer get(Object sought) {
        GraphSourceChoices<K> cursor = this;
        while (cursor.parent != null) {
            if (cursor.key.equals(sought)) return cursor.value;
            cursor = cursor.parent;
        }
        return cursor.base.get(sought);
    }

    @Override
    public boolean containsKey(Object sought) {
        return get(sought) != null;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other instanceof GraphSourceChoices<?> shared) {
            if (size != shared.size || hash != shared.hash) return false;
            if (parent != null && parent == shared.parent)
                return key.equals(shared.key) && Objects.equals(value, shared.value);
            GraphSourceChoices<?> left = this, right = shared;
            while (left.parent != null) left = left.parent;
            while (right.parent != null) right = right.parent;
            if (left == right) {
                // Siblings commonly have identical map hashes (changing 1 to
                // 2 changes an entry hash by only a few values). Compare just
                // the decisions after their common ancestor, without building
                // full maps for every HashSet collision.
                left = this;
                right = shared;
                while (left != right) {
                    if (left.depth >= right.depth) {
                        if (!Objects.equals(get(left.key), shared.get(left.key))) return false;
                        left = left.parent;
                    } else {
                        if (!Objects.equals(get(right.key), shared.get(right.key))) return false;
                        right = right.parent;
                    }
                }
                return true;
            }
        }
        return super.equals(other);
    }

    @Override
    public Set<Entry<K, Integer>> entrySet() {
        if (parent == null) return base.entrySet();
        // Iteration is needed only when compiling or comparing assignments.
        // Do not cache this materialization on every retained sibling.
        GraphSourceChoices<K> cursor = this;
        var changes = new java.util.ArrayDeque<GraphSourceChoices<K>>(depth);
        while (cursor.parent != null) {
            changes.addFirst(cursor);
            cursor = cursor.parent;
        }
        Map<K, Integer> flattened = new LinkedHashMap<>(cursor.base);
        for (var change : changes) {
            if (change.value == null) flattened.remove(change.key);
            else flattened.put(change.key, change.value);
        }
        return Map.copyOf(flattened).entrySet();
    }
}
