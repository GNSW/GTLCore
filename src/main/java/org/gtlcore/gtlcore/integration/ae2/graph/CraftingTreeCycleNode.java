package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.stacks.GenericStack;

import java.awt.Point;

/** Optional AE2CT node metadata, computed locally without changing its wire format. */
public interface CraftingTreeCycleNode {

    int gtlcore$cycleDistance();

    void gtlcore$setCycleDistance(int distance);

    GenericStack gtlcore$treeStack();

    Point gtlcore$treePoint();
}
