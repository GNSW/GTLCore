package org.gtlcore.gtlcore.mixin.ae2.logic;

import org.gtlcore.gtlcore.integration.ae2.graph.*;

import net.minecraft.nbt.CompoundTag;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = CraftingCpuLogic.class, priority = 1100)
public abstract class GraphCraftingCpuMixin implements GraphCpuAccess {

    @Shadow(remap = false)
    @Final
    private ListCraftingInventory inventory;
    @Shadow(remap = false)
    private ExecutingCraftingJob job;
    @Unique
    private GraphCpuController gtlcore$graph;

    @Override
    @Invoker(value = "postChange", remap = false)
    public abstract void gtlcore$postGraphChange(AEKey key);

    @Override
    public GraphCpuController gtlcore$graphController() {
        return gtlcore$graph;
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtlcore$initGraph(CraftingCPUCluster cluster, CallbackInfo ci) {
        gtlcore$graph = new GraphCpuController(new NativeGraphCpuHost(cluster, inventory, this));
    }

    @Inject(method = "trySubmitJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$submit(IGrid grid, ICraftingPlan plan, IActionSource source, ICraftingRequester requester,
                                CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(CraftingSubmitResult.CPU_BUSY);
        else if (GraphCpuController.isGraphPlan(plan)) cir.setReturnValue(job == null ?
                gtlcore$graph.submit(grid, plan, source, requester) : CraftingSubmitResult.CPU_BUSY);
    }

    @Inject(method = "insert", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$insert(AEKey key, long amount, Actionable mode, CallbackInfoReturnable<Long> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(gtlcore$graph.insert(key, amount, mode));
    }

    @Inject(method = "cancel", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$cancel(CallbackInfo ci) {
        if (gtlcore$graph.ownsTask()) {
            gtlcore$graph.cancel();
            ci.cancel();
        }
    }

    @Inject(method = "storeItems", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$store(CallbackInfo ci) {
        if (gtlcore$graph.ownsTask()) ci.cancel();
    }

    @Inject(method = "hasJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$busy(CallbackInfoReturnable<Boolean> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(true);
    }

    @Inject(method = "getFinalJobOutput", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$output(CallbackInfoReturnable<GenericStack> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(gtlcore$graph.finalOutput());
    }

    @Inject(method = "getElapsedTimeTracker", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$time(CallbackInfoReturnable<ElapsedTimeTracker> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(gtlcore$graph.tracker());
    }

    @Inject(method = "getLastLink", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$link(CallbackInfoReturnable<ICraftingLink> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(gtlcore$graph.link());
    }

    @Inject(method = "getLastModifiedOnTick", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtlcore$modified(CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(Math.max(cir.getReturnValue(), gtlcore$graph.modifiedTick()));
    }

    @Inject(method = "getStored", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$stored(AEKey key, CallbackInfoReturnable<Long> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(gtlcore$graph.stored(key));
    }

    @Inject(method = "getWaitingFor", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$waiting(AEKey key, CallbackInfoReturnable<Long> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(gtlcore$graph.waiting(key));
    }

    @Inject(method = "getAllWaitingFor", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$waitingKeys(Set<AEKey> out, CallbackInfo ci) {
        if (gtlcore$graph.ownsTask()) {
            gtlcore$graph.waitingKeys(out);
            ci.cancel();
        }
    }

    @Inject(method = "getPendingOutputs", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$pending(AEKey key, CallbackInfoReturnable<Long> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(gtlcore$graph.pending(key));
    }

    @Inject(method = "getAllItems", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$items(KeyCounter out, CallbackInfo ci) {
        if (gtlcore$graph.ownsTask()) {
            gtlcore$graph.allItems(out);
            ci.cancel();
        }
    }

    @Inject(method = "isCantStoreItems", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$cantStore(CallbackInfoReturnable<Boolean> cir) {
        if (gtlcore$graph.ownsTask()) cir.setReturnValue(gtlcore$graph.cantStore());
    }

    @Inject(method = "getInventory", at = @At("HEAD"), remap = false)
    private void gtlcore$destructionInventory(CallbackInfoReturnable<ListCraftingInventory> cir) {
        gtlcore$graph.handoffCancelledInventory();
    }

    @Inject(method = "readFromNBT", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$read(CompoundTag data, CallbackInfo ci) {
        if (data.contains(GraphJobCodec.NBT_KEY, CompoundTag.TAG_COMPOUND)) {
            inventory.readFromNBT(data.getList("inventory", CompoundTag.TAG_COMPOUND));
            job = null;
            gtlcore$graph.read(data);
            ci.cancel();
        }
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"), remap = false)
    private void gtlcore$write(CompoundTag data, CallbackInfo ci) {
        gtlcore$graph.write(data);
    }
}
