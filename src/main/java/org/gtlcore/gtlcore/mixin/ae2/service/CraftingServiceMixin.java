package org.gtlcore.gtlcore.mixin.ae2.service;

import org.gtlcore.gtlcore.common.machine.multiblock.electric.TransfiniteComputationArrayMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MECraftingCPUInterfacePartMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.crafting.IMaxFastCraftingProviderVersion;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.TransfiniteCraftingCPU;
import org.gtlcore.gtlcore.integration.ae2.graph.CraftingEngineRouter;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphProviderVersion;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphRequestTracker;
import org.gtlcore.gtlcore.integration.ae2.graph.GtlPatternCatalog;
import org.gtlcore.gtlcore.utils.NumberUtils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.hooks.ticking.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.mutable.MutableObject;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;

@Mixin(CraftingService.class)
public abstract class CraftingServiceMixin implements IMaxFastCraftingProviderVersion,
                                           GraphRequestTracker {

    @Override
    public void gtlcore$expectGraphOutput(AEKey key) {
        this.currentlyCrafting.add(key);
        this.lastProcessedCraftingLogicChangeTick = -1;
    }

    @Unique
    private GtlPatternCatalog gtlcore$graphCatalog;

    @Override
    public void gtlcore$invalidateGraphBinding(String binding) {
        if (this.gtlcore$graphCatalog != null) this.gtlcore$graphCatalog.invalidateBinding(binding);
    }

    @Override
    public long gtlcore$graphProviderGeneration() {
        return ((GraphProviderVersion) this.craftingProviders).gtlcore$providerGeneration();
    }

    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$beginGraphCalculation(Level level,
                                               ICraftingSimulationRequester requester, AEKey what, long amount,
                                               CalculationStrategy strategy,
                                               CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        if (!CraftingEngineRouter.useGraph()) return;
        if (this.gtlcore$graphCatalog == null) this.gtlcore$graphCatalog = new GtlPatternCatalog();
        cir.setReturnValue(CraftingEngineRouter.begin(
                this.gtlcore$graphCatalog, this.grid, (CraftingService) (Object) this,
                level, requester, what, amount, strategy));
    }

    @Unique
    private static final int CRAFT_MASK = NumberUtils.nearestPow2Lookup(
            ConfigHolder.INSTANCE.ae2CraftingServiceUpdateInterval) - 1;

    @Unique
    private static final Comparator<TransfiniteComputationArrayMachine> TRANSFINITE_CPU_COMPARATOR = Comparator.comparingLong(TransfiniteComputationArrayMachine::getParallelism)
            .reversed()
            .thenComparingLong(TransfiniteComputationArrayMachine::getAvailableStorage);

    @Unique
    private Set<TransfiniteComputationArrayMachine> gtlcore$transfiniteControllers;

    @Unique
    private Set<AEKey> gtlcore$lastTransfiniteRequests;

    @Shadow(remap = false)
    @Final
    private IGrid grid;

    @Shadow(remap = false)
    @Final
    private IEnergyService energyGrid;

    @Shadow(remap = false)
    @Final
    private NetworkCraftingProviders craftingProviders;

    @Shadow(remap = false)
    @Final
    private Set<AEKey> currentlyCrafting;

    @Shadow(remap = false)
    @Final
    private Set<CraftingCPUCluster> craftingCPUClusters;

    @Shadow(remap = false)
    private long lastProcessedCraftingLogicChangeTick;

    @Shadow(remap = false)
    private boolean updateList;

    @Shadow(remap = false)
    public abstract void addLink(CraftingLink link);

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtlcore$initializeTransfiniteState(IGrid grid, IStorageService storageService,
                                                    IEnergyService energyService, CallbackInfo ci) {
        this.gtlcore$transfiniteControllers = new HashSet<>();
        this.gtlcore$lastTransfiniteRequests = Set.of();
    }

    @Override
    @Unique
    public long gtlcore$getMaxFastCraftingProviderVersionTick() {
        return this.craftingProviders.getLastModifiedOnTick();
    }

    @Inject(method = "onServerEndTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$limitCraftingServiceUpdates(CallbackInfo ci) {
        if ((TickHandler.instance().getCurrentTick() & CRAFT_MASK) != 0) {
            ci.cancel();
        }
    }

    @Inject(method = "onServerEndTick",
            at = @At(value = "FIELD",
                     target = "Lappeng/me/service/CraftingService;lastProcessedCraftingLogicChangeTick:J",
                     opcode = Opcodes.GETFIELD,
                     ordinal = 0),
            locals = LocalCapture.CAPTURE_FAILHARD,
            remap = false)
    private void gtlcore$tickTransfiniteCpus(CallbackInfo ci, long latestChange) {
        long latestTransfiniteChange = 0;
        Set<AEKey> currentTransfiniteRequests = new HashSet<>();
        CraftingService craftingService = (CraftingService) (Object) this;
        for (TransfiniteComputationArrayMachine controller : this.gtlcore$transfiniteControllers) {
            latestTransfiniteChange = Math.max(latestTransfiniteChange,
                    controller.tickCraftingCpus(this.energyGrid, craftingService));
            controller.collectRequestingKeys(currentTransfiniteRequests);
        }
        boolean requestsChanged = !currentTransfiniteRequests.equals(this.gtlcore$lastTransfiniteRequests);
        if (requestsChanged) {
            this.gtlcore$lastTransfiniteRequests = Set.copyOf(currentTransfiniteRequests);
        }
        if (latestTransfiniteChange > latestChange || requestsChanged) {
            this.lastProcessedCraftingLogicChangeTick = -1;
        }
    }

    @Inject(method = "onServerEndTick",
            at = @At(value = "FIELD",
                     target = "Lappeng/me/service/CraftingService;interests:Lcom/google/common/collect/Multimap;",
                     opcode = Opcodes.GETFIELD,
                     ordinal = 0),
            remap = false)
    private void gtlcore$collectTransfiniteWaitingItems(CallbackInfo ci) {
        for (TransfiniteComputationArrayMachine controller : this.gtlcore$transfiniteControllers) {
            controller.collectRequestingKeys(this.currentlyCrafting);
        }
    }

    @Inject(method = "removeNode", at = @At("TAIL"), remap = false)
    private void gtlcore$onRemoveNode(IGridNode gridNode, CallbackInfo ci) {
        if (gridNode.getOwner() instanceof MECraftingCPUInterfacePartMachine) {
            this.updateList = true;
        }
    }

    @Inject(method = "addNode", at = @At("TAIL"), remap = false)
    private void gtlcore$onAddNode(IGridNode gridNode, CompoundTag savedData, CallbackInfo ci) {
        if (gridNode.getOwner() instanceof MECraftingCPUInterfacePartMachine) {
            this.updateList = true;
        }
    }

    @Inject(method = "updateCPUClusters", at = @At("TAIL"), remap = false)
    private void gtlcore$updateTransfiniteControllers(CallbackInfo ci) {
        this.gtlcore$transfiniteControllers.clear();
        for (MECraftingCPUInterfacePartMachine networkInterface : this.grid.getMachines(
                MECraftingCPUInterfacePartMachine.class)) {
            for (TransfiniteComputationArrayMachine controller : networkInterface.getTransfiniteControllers()) {
                if (this.gtlcore$transfiniteControllers.add(controller)) {
                    controller.forEachActiveCpu(cpu -> {
                        if (cpu.getCraftingLogic().getLastLink() instanceof CraftingLink link) {
                            addLink(link);
                        }
                    });
                }
            }
        }
    }

    @Inject(method = "insertIntoCpus", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtlcore$insertIntoTransfiniteCpus(AEKey what, long amount, Actionable mode,
                                                   CallbackInfoReturnable<Long> cir) {
        long inserted = cir.getReturnValue();
        for (TransfiniteComputationArrayMachine controller : this.gtlcore$transfiniteControllers) {
            if (inserted >= amount) {
                break;
            }
            inserted += controller.insertIntoCpus(what, amount - inserted, mode);
        }
        cir.setReturnValue(inserted);
    }

    @Inject(method = "submitJob",
            at = @At(value = "INVOKE_ASSIGN",
                     target = "Lappeng/me/service/CraftingService;findSuitableCraftingCPU(Lappeng/api/networking/crafting/ICraftingPlan;ZLappeng/api/networking/security/IActionSource;Lorg/apache/commons/lang3/mutable/MutableObject;)Lappeng/me/cluster/implementations/CraftingCPUCluster;"),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD,
            remap = false)
    private void gtlcore$submitToTransfiniteCpu(ICraftingPlan plan, ICraftingRequester requester,
                                                ICraftingCPU target, boolean prioritizePower,
                                                IActionSource source,
                                                CallbackInfoReturnable<ICraftingSubmitResult> cir,
                                                CraftingCPUCluster nativeCpu,
                                                MutableObject<UnsuitableCpus> unsuitableCpusResult) {
        if (target instanceof TransfiniteCraftingCPU transfiniteCpu) {
            if (transfiniteCpu.isCapacityView()) {
                cir.setReturnValue(transfiniteCpu.getHost().submitJob(
                        this.grid, plan, source, requester));
            } else {
                cir.setReturnValue(CraftingSubmitResult.CPU_BUSY);
            }
            return;
        }

        TransfiniteComputationArrayMachine controller = findSuitableTransfiniteController(
                plan, source, unsuitableCpusResult, nativeCpu == null);
        if (controller != null) {
            this.updateList = true;
            cir.setReturnValue(controller.submitJob(this.grid, plan, source, requester));
        }
    }

    @Unique
    private TransfiniteComputationArrayMachine findSuitableTransfiniteController(
                                                                                 ICraftingPlan plan, IActionSource source, MutableObject<UnsuitableCpus> unsuitableCpusResult,
                                                                                 boolean recordUnsuitable) {
        var candidates = new ArrayList<TransfiniteComputationArrayMachine>(
                this.gtlcore$transfiniteControllers.size());
        int offline = 0;
        int tooSmall = 0;
        int excluded = 0;
        for (TransfiniteComputationArrayMachine controller : this.gtlcore$transfiniteControllers) {
            if (!controller.isOperational()) {
                offline++;
            } else if (controller.getAvailableStorage() < plan.bytes()) {
                tooSmall++;
            } else if (!controller.canBeAutoSelectedFor(source)) {
                excluded++;
            } else {
                candidates.add(controller);
            }
        }
        if (candidates.isEmpty()) {
            if (recordUnsuitable && (offline > 0 || tooSmall > 0 || excluded > 0)) {
                UnsuitableCpus previous = unsuitableCpusResult.getValue();
                unsuitableCpusResult.setValue(previous == null ?
                        new UnsuitableCpus(offline, 0, tooSmall, excluded) :
                        new UnsuitableCpus(previous.offline() + offline, previous.busy(),
                                previous.tooSmall() + tooSmall, previous.excluded() + excluded));
            }
            return null;
        }

        candidates.sort((first, second) -> {
            boolean firstPreferred = first.isPreferredFor(source);
            boolean secondPreferred = second.isPreferredFor(source);
            if (firstPreferred != secondPreferred) {
                return Boolean.compare(secondPreferred, firstPreferred);
            }
            return TRANSFINITE_CPU_COMPARATOR.compare(first, second);
        });
        return candidates.get(0);
    }

    @Inject(method = "getCpus", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtlcore$getTransfiniteCpus(CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir) {
        ImmutableSet.Builder<ICraftingCPU> cpus = ImmutableSet.builder();
        cpus.addAll(cir.getReturnValue());
        for (TransfiniteComputationArrayMachine controller : this.gtlcore$transfiniteControllers) {
            controller.forEachActiveCpu(cpus::add);
            cpus.add(controller.getCapacityCpu());
        }
        cir.setReturnValue(cpus.build());
    }

    @Inject(method = "getRequestedAmount", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtlcore$getTransfiniteRequestedAmount(AEKey what, CallbackInfoReturnable<Long> cir) {
        BigInteger total = BigInteger.ZERO;
        for (CraftingCPUCluster cpu : this.craftingCPUClusters) {
            total = total.add(BigInteger.valueOf(cpu.craftingLogic.getWaitingFor(what)));
        }
        for (TransfiniteComputationArrayMachine controller : this.gtlcore$transfiniteControllers) {
            total = total.add(BigInteger.valueOf(controller.getRequestedAmount(what)));
        }
        // The service API can advertise at most one long-sized transfer. Job
        // ledgers retain their independent exact obligations beyond this bound.
        cir.setReturnValue(total.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact());
    }

    @Inject(method = "isRequesting", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtlcore$isTransfiniteRequesting(AEKey what, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }
        for (TransfiniteComputationArrayMachine controller : this.gtlcore$transfiniteControllers) {
            if (controller.isRequesting(what)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Inject(method = "isRequestingAny", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtlcore$isTransfiniteRequestingAny(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }
        for (TransfiniteComputationArrayMachine controller : this.gtlcore$transfiniteControllers) {
            if (controller.isRequestingAny()) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Inject(method = "hasCpu", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$hasTransfiniteCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
        if (!(cpu instanceof TransfiniteCraftingCPU transfiniteCpu)) {
            return;
        }
        TransfiniteComputationArrayMachine host = transfiniteCpu.getHost();
        if (this.gtlcore$transfiniteControllers.contains(host) &&
                (transfiniteCpu.isCapacityView() || host.containsCpu(transfiniteCpu))) {
            cir.setReturnValue(true);
        }
    }
}
