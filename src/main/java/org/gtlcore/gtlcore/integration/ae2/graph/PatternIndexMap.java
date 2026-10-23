package org.gtlcore.gtlcore.integration.ae2.graph;

import java.util.*;

/** Server-owned index with constant-time immutable views and path-copying updates. */
final class PatternIndexMap<K, V> {

    private Node<List<Value<K, V>>> hashes;
    private Node<Value<K, V>> ordered;
    private long nextOrder;
    private int size;

    V get(K key) {
        return lookup(hashes, key);
    }

    void clear() {
        hashes = null;
        ordered = null;
        size = 0;
        nextOrder = 0;
    }

    void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        long hash = key.hashCode();
        var bucket = new ArrayList<>(Objects.requireNonNullElse(find(hashes, hash), List.of()));
        for (int i = 0; i < bucket.size(); i++) {
            var old = bucket.get(i);
            if (!old.key().equals(key)) continue;
            var entry = new Value<>(key, value, old.order());
            bucket.set(i, entry);
            hashes = set(hashes, hash, List.copyOf(bucket));
            ordered = set(ordered, old.order(), entry);
            return;
        }
        var entry = new Value<>(key, value, nextOrder++);
        bucket.add(entry);
        hashes = set(hashes, hash, List.copyOf(bucket));
        ordered = set(ordered, entry.order(), entry);
        size++;
    }

    void remove(K key) {
        long hash = key.hashCode();
        var values = find(hashes, hash);
        if (values == null) return;
        var bucket = new ArrayList<>(values);
        for (int i = 0; i < bucket.size(); i++) {
            var old = bucket.get(i);
            if (!old.key().equals(key)) continue;
            bucket.remove(i);
            hashes = set(hashes, hash, bucket.isEmpty() ? null : List.copyOf(bucket));
            ordered = set(ordered, old.order(), null);
            size--;
            return;
        }
    }

    Map<K, V> snapshot() {
        var capturedHashes = hashes;
        var capturedOrder = ordered;
        int capturedSize = size;
        return new AbstractMap<>() {

            @Override
            public V get(Object key) {
                return lookup(capturedHashes, key);
            }

            @Override
            public boolean containsKey(Object key) {
                return lookup(capturedHashes, key) != null;
            }

            @Override
            public V getOrDefault(Object key, V defaultValue) {
                V value = lookup(capturedHashes, key);
                return value == null ? defaultValue : value;
            }

            @Override
            public int size() {
                return capturedSize;
            }

            @Override
            public Set<Entry<K, V>> entrySet() {
                return new AbstractSet<>() {

                    @Override
                    public int size() {
                        return capturedSize;
                    }

                    @Override
                    public Iterator<Entry<K, V>> iterator() {
                        return new Iterator<>() {

                            final Deque<Node<Value<K, V>>> path = new ArrayDeque<>();
                            {
                                descend(capturedOrder);
                            }

                            private void descend(Node<Value<K, V>> node) {
                                while (node != null) {
                                    path.push(node);
                                    node = node.left;
                                }
                            }

                            public boolean hasNext() {
                                return !path.isEmpty();
                            }

                            public Entry<K, V> next() {
                                if (path.isEmpty()) throw new NoSuchElementException();
                                var node = path.pop();
                                descend(node.right);
                                return Map.entry(node.value.key(), node.value.value());
                            }
                        };
                    }
                };
            }
        };
    }

    private record Value<K, V>(K key, V value, long order) {}

    private static <K, V> V lookup(Node<List<Value<K, V>>> root, Object key) {
        if (key == null) return null;
        var bucket = find(root, key.hashCode());
        if (bucket != null) for (var entry : bucket) if (entry.key().equals(key)) return entry.value();
        return null;
    }

    private static final class Node<V> {

        final long key;
        final V value;
        final Node<V> left, right;
        final int height;

        Node(long key, V value, Node<V> left, Node<V> right) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            height = 1 + Math.max(height(left), height(right));
        }
    }

    private static int height(Node<?> node) {
        return node == null ? 0 : node.height;
    }

    private static <V> V find(Node<V> node, long key) {
        while (node != null) {
            if (key == node.key) return node.value;
            node = key < node.key ? node.left : node.right;
        }
        return null;
    }

    private static <V> Node<V> set(Node<V> node, long key, V value) {
        if (node == null) return value == null ? null : new Node<>(key, value, null, null);
        if (key < node.key) return balance(new Node<>(node.key, node.value, set(node.left, key, value), node.right));
        if (key > node.key) return balance(new Node<>(node.key, node.value, node.left, set(node.right, key, value)));
        if (value != null) return new Node<>(key, value, node.left, node.right);
        if (node.left == null) return node.right;
        if (node.right == null) return node.left;
        var successor = node.right;
        while (successor.left != null) successor = successor.left;
        return balance(new Node<>(successor.key, successor.value, node.left, set(node.right, successor.key, null)));
    }

    private static <V> Node<V> balance(Node<V> node) {
        if (height(node.left) > height(node.right) + 1) {
            var left = node.left;
            if (height(left.right) > height(left.left)) left = rotateLeft(left);
            return rotateRight(new Node<>(node.key, node.value, left, node.right));
        }
        if (height(node.right) > height(node.left) + 1) {
            var right = node.right;
            if (height(right.left) > height(right.right)) right = rotateRight(right);
            return rotateLeft(new Node<>(node.key, node.value, node.left, right));
        }
        return node;
    }

    private static <V> Node<V> rotateLeft(Node<V> node) {
        var top = node.right;
        return new Node<>(top.key, top.value, new Node<>(node.key, node.value, node.left, top.left), top.right);
    }

    private static <V> Node<V> rotateRight(Node<V> node) {
        var top = node.left;
        return new Node<>(top.key, top.value, top.left, new Node<>(node.key, node.value, top.right, node.right));
    }
}
