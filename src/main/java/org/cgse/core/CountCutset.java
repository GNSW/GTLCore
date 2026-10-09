// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Arrays;
import java.util.BitSet;

/** Linear-time single-vertex separator scores on the live undirected primal graph. */
final class CountCutset {

    private final BitSet[] graph;
    private final BitSet live;
    private final Runnable charge;
    private final int[] discovered, low, size, detached, largest, component, componentSize;
    private int clock;

    private CountCutset(BitSet[] graph, BitSet live, Runnable charge) {
        this.graph = graph;
        this.live = live;
        this.charge = charge;
        int n = graph.length;
        discovered = new int[n];
        low = new int[n];
        size = new int[n];
        detached = new int[n];
        largest = new int[n];
        component = new int[n];
        componentSize = new int[n];
    }

    /** Caller owns the bounded O(vertices) workspace as well as the adjacency. */
    static int[] largestParts(BitSet[] graph, BitSet live, Runnable charge) {
        return new CountCutset(graph, live, charge).score();
    }

    private int[] score() {
        int biggest = 0, second = 0, biggestRoot = -1;
        for (int id = live.nextSetBit(0); id >= 0; id = live.nextSetBit(id + 1)) {
            charge.run();
            if (discovered[id] != 0) continue;
            visit(id, -1, id);
            componentSize[id] = size[id];
            if (size[id] > biggest) {
                second = biggest;
                biggest = size[id];
                biggestRoot = id;
            } else second = Math.max(second, size[id]);
        }
        int[] result = new int[graph.length];
        Arrays.fill(result, -1);
        for (int id = live.nextSetBit(0); id >= 0; id = live.nextSetBit(id + 1)) {
            charge.run();
            int root = component[id];
            int remainder = componentSize[root] - 1 - detached[id];
            result[id] = Math.max(Math.max(largest[id], remainder), root == biggestRoot ? second : biggest);
        }
        return result;
    }

    private void visit(int id, int parent, int root) {
        charge.run();
        discovered[id] = low[id] = ++clock;
        component[id] = root;
        size[id] = 1;
        for (int next = graph[id].nextSetBit(0); next >= 0; next = graph[id].nextSetBit(next + 1)) {
            charge.run();
            if (!live.get(next) || next == parent) continue;
            if (discovered[next] == 0) {
                visit(next, id, root);
                size[id] += size[next];
                low[id] = Math.min(low[id], low[next]);
                if (low[next] >= discovered[id]) {
                    detached[id] += size[next];
                    largest[id] = Math.max(largest[id], size[next]);
                }
            } else low[id] = Math.min(low[id], discovered[next]);
        }
    }
}
