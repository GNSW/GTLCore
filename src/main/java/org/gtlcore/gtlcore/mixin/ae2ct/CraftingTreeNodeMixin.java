package org.gtlcore.gtlcore.mixin.ae2ct;

import org.gtlcore.gtlcore.integration.ae2.graph.CraftingTreeCycleNode;

import appeng.api.stacks.GenericStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.awt.Point;

@Pseudo
@Mixin(targets = "com.neuvillette.ae2ct.api.CraftingTreeHelper$Node", remap = false)
public abstract class CraftingTreeNodeMixin implements CraftingTreeCycleNode {

    @Shadow
    public GenericStack stack;
    @Shadow
    public Point point;
    @Unique
    private int gtlcore$cycleDistance;

    @Override
    public int gtlcore$cycleDistance() {
        return gtlcore$cycleDistance;
    }

    @Override
    public void gtlcore$setCycleDistance(int distance) {
        gtlcore$cycleDistance = distance;
    }

    @Override
    public GenericStack gtlcore$treeStack() {
        return stack;
    }

    @Override
    public Point gtlcore$treePoint() {
        return point;
    }
}
