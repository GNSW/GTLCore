// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Arrays;

/** Indexed maximum queue with the linear selector's stable variable-id tie break. */
final class CountDecisionQueue {

    private final int[] heap, positions;
    private final double[] keys;
    private final Runnable charge;
    private int size;

    CountDecisionQueue(int variables, Runnable charge) {
        heap = new int[variables];
        positions = new int[variables];
        keys = new double[variables];
        Arrays.fill(positions, -1);
        this.charge = charge;
    }

    static long bytes(int variables) { return 128L + 16L * variables; }

    void update(int id, double key) {
        charge.run();
        int at = positions[id];
        double previous = keys[id];
        keys[id] = key;
        if (at < 0) {
            at = size++;
            heap[at] = id;
            positions[id] = at;
            up(at);
        } else if (key > previous) up(at);
        else if (key < previous) down(at);
    }

    int take() {
        charge.run();
        if (size == 0) return -1;
        int result = heap[0];
        positions[result] = -1;
        if (--size > 0) {
            heap[0] = heap[size];
            positions[heap[0]] = 0;
            down(0);
        }
        return result;
    }

    private boolean before(int left, int right) {
        charge.run();
        return keys[left] > keys[right] || keys[left] == keys[right] && left < right;
    }

    private void up(int at) {
        int id = heap[at];
        while (at > 0) {
            int parent = (at - 1) / 2;
            if (!before(id, heap[parent])) break;
            heap[at] = heap[parent];
            positions[heap[at]] = at;
            at = parent;
        }
        heap[at] = id;
        positions[id] = at;
    }

    private void down(int at) {
        int id = heap[at];
        while (2 * at + 1 < size) {
            int child = 2 * at + 1;
            if (child + 1 < size && before(heap[child + 1], heap[child])) child++;
            if (!before(heap[child], id)) break;
            heap[at] = heap[child];
            positions[heap[at]] = at;
            at = child;
        }
        heap[at] = id;
        positions[id] = at;
    }
}
