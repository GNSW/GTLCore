package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.common.CraftAmountReturnState;
import org.gtlcore.gtlcore.integration.ae2.common.IConfirmStartMenu;
import org.gtlcore.gtlcore.integration.ae2.common.ILongCraftAmountMenu;
import org.gtlcore.gtlcore.integration.ae2.common.ILongCraftConfirmMenu;
import org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.MissingCraftingPlan;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.TransfiniteCraftingCPU;
import org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphPlanMenu;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphPlanningRequest;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.TypeFilter;
import appeng.api.config.ViewItems;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.ISubMenuHost;
import appeng.client.gui.me.common.Repo;
import appeng.client.gui.widgets.ISortSource;
import appeng.core.sync.packets.MEInventoryUpdatePacket;
import appeng.helpers.IMenuCraftingPacket;
import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.guisync.GuiSync;
import appeng.menu.me.common.IClientRepo;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummary;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

@Mixin(CraftConfirmMenu.class)
public abstract class CraftConfirmMenuMixin extends AEBaseMenu implements IConfirmStartMenu, ILongCraftConfirmMenu, GraphPlanMenu {

    @Override
    public AeGraphPlan gtlcore$graphPlan() {
        return result instanceof AeGraphPlan graph ? graph : null;
    }

    protected CraftConfirmMenuMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Shadow(remap = false)
    protected abstract IGrid getGrid();

    @Shadow(remap = false)
    private Future<ICraftingPlan> job;

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void gtlcore$planningProgress(CallbackInfo ci) {
        if (job instanceof GraphPlanningRequest request &&
                request.noticeDue(ConfigHolder.INSTANCE.ae2GraphPlannerNoticeAfterMs) && getPlayer() instanceof ServerPlayer player) {
            var phase = request.budget().phase().name().toLowerCase(java.util.Locale.ROOT);
            player.displayClientMessage(Component.translatable("gtlcore.ae.graph.planning_status",
                    Component.translatable("gtlcore.ae.graph.phase." + phase), request.budget().elapsedNanos() / 1_000_000_000L), true);
        }
    }

    @Shadow(remap = false)
    private ICraftingPlan result;

    @Shadow(remap = false)
    private ICraftingCPU selectedCpu;

    @Shadow(remap = false)
    private AEKey whatToCraft;

    @Shadow(remap = false)
    private int amount;

    @Shadow(remap = false)
    private CraftingPlanSummary plan;

    @Shadow(remap = false)
    @Final
    private ISubMenuHost host;

    @Shadow(remap = false)
    private List<IMenuCraftingPacket.AutoCraftEntry> autoCraftingQueue;

    @Shadow(remap = false)
    public abstract void clearError();

    @Unique
    private IClientRepo gtlcore$repo;

    @Unique
    private int gtlcore$storedSyncCooldown = 0;

    @Unique
    private @Nullable KeyCounter gtlcore$lastSentStored = null;

    @Unique
    private final IncrementalUpdateHelper gtlcore$updateHelper = new IncrementalUpdateHelper();

    @Unique
    private int gtlcore$liveVersion = 0;

    @Unique
    private int gtlcore$liveStoredVersion = -1;

    @Unique
    private @Nullable KeyCounter gtlcore$liveStoredCache;

    @Unique
    private int gtlcore$missingVersion = -1;

    @Unique
    private @Nullable CraftingPlanSummary gtlcore$missingPlan;

    @Unique
    private List<AEKey> gtlcore$missingCache = List.of();

    @Unique
    private long gtlcore$longAmount = CraftAmountReturnState.NO_LONG_AMOUNT;

    @Unique
    private @Nullable ManualCraftingInventoryLock.Reservation gtlcore$inventoryReservation;

    @Unique
    private @Nullable ICraftingPlan gtlcore$reservedPlan;

    @Unique
    private CalculationStrategy gtlcore$calculationStrategy = CalculationStrategy.REPORT_MISSING_ITEMS;

    @Unique
    @GuiSync(IConfirmStartMenu.GUI_SYNC_MISSING_CRAFT_AVAILABLE)
    private boolean gtlcore$missingCraftAvailable;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void onConstructed(int id, Inventory ip, ISubMenuHost host, CallbackInfo ci) {
        var repo = new Repo(() -> 0, new ISortSource() {

            @Override
            public SortOrder getSortBy() {
                return SortOrder.AMOUNT;
            }

            @Override
            public SortDir getSortDir() {
                return SortDir.ASCENDING;
            }

            @Override
            public ViewItems getSortDisplay() {
                return ViewItems.ALL;
            }

            @Override
            public TypeFilter getTypeFilter() {
                return TypeFilter.ALL;
            }
        });
        // repo 每次收到更新回调此监听器，客户端缓存按版本号惰性失效
        repo.setUpdateViewListener(() -> gtlcore$liveVersion++);
        this.gtlcore$repo = repo;
    }

    @Override
    public IClientRepo gtlcore$getClientRepo() {
        return gtlcore$repo;
    }

    @Override
    public @Nullable KeyCounter gtlcore$getLiveStored() {
        // 首个同步包到达前 repo 是空的，不能拿来判定"库存已被消耗"
        if (gtlcore$liveVersion == 0) {
            return null;
        }
        if (gtlcore$liveStoredCache == null || gtlcore$liveStoredVersion != gtlcore$liveVersion) {
            var live = new KeyCounter();
            for (var entry : gtlcore$repo.getAllEntries()) {
                if (entry.getWhat() != null && entry.getStoredAmount() > 0) {
                    live.add(entry.getWhat(), entry.getStoredAmount());
                }
            }
            gtlcore$liveStoredCache = live;
            gtlcore$liveStoredVersion = gtlcore$liveVersion;
        }
        return gtlcore$liveStoredCache;
    }

    @Override
    public List<AEKey> gtlcore$getMissingNow() {
        var currentPlan = this.plan;
        if (currentPlan == null) {
            return List.of();
        }
        if (currentPlan != gtlcore$missingPlan || gtlcore$missingVersion != gtlcore$liveVersion) {
            // 计划里的缺失量是算料时的快照，用实时库存复核被其他产线消耗的部分
            var live = gtlcore$getLiveStored();
            var missing = new ArrayList<AEKey>();
            for (var entry : currentPlan.getEntries()) {
                if (entry.getMissingAmount() > 0 ||
                        (live != null && entry.getStoredAmount() > live.get(entry.getWhat()))) {
                    missing.add(entry.getWhat());
                }
            }
            gtlcore$missingCache = missing;
            gtlcore$missingPlan = currentPlan;
            gtlcore$missingVersion = gtlcore$liveVersion;
        }
        return gtlcore$missingCache;
    }

    @Override
    public boolean gtlcore$isMissingCraftAvailable() {
        return this.gtlcore$missingCraftAvailable;
    }

    @Override
    public boolean gtlcore$planLongAmountJob(AEKey whatToCraft, long amount, CalculationStrategy strategy) {
        this.gtlcore$releaseInventoryReservation();
        this.gtlcore$calculationStrategy = strategy;
        if (this.job != null) {
            this.job.cancel(true);
        }
        this.result = null;
        this.clearError();
        this.whatToCraft = whatToCraft;
        this.amount = CraftAmountReturnState.legacyAmount(amount);
        this.gtlcore$longAmount = CraftAmountReturnState.rememberedAmount(amount);

        var grid = this.getGrid();
        if (grid == null) {
            return false;
        }

        var player = this.getPlayer();
        this.job = grid.getCraftingService().beginCraftingCalculation(
                player.level(), this::getActionSource, whatToCraft, amount, strategy);
        return true;
    }

    @Inject(method = "broadcastChanges", at = @At("RETURN"))
    private void onBroadcastChanges(CallbackInfo ci) {
        if (this.isClientSide() || this.plan == null) return;
        if (!ConfigHolder.INSTANCE.enableAe2ManualCraftingInventoryLock) {
            this.gtlcore$releaseInventoryReservation();
        } else if (this.result != null && this.result != this.gtlcore$reservedPlan &&
                !this.gtlcore$tryReserveInventory(this.result)) {
                    this.gtlcore$restartCalculationAfterReservationConflict();
                    return;
                }
        // 计划算完后库存仍可能被其他产线消耗，持续同步实时库存供客户端复核缺失
        if (gtlcore$lastSentStored != null && --gtlcore$storedSyncCooldown > 0) return;
        gtlcore$storedSyncCooldown = 10;
        KeyCounter current = gtlcore$getRelevantStoredAmounts();

        if (gtlcore$lastSentStored == null) {
            var builder = MEInventoryUpdatePacket.builder(containerId, true);
            builder.addFull(gtlcore$updateHelper, current, Set.of(), new KeyCounter());
            builder.buildAndSend(this::sendPacketToClient);
            gtlcore$lastSentStored = current;
            return;
        }

        // 增量同步：只发送数量变化的键（current 只含 >0 的量，归零的键走第二个循环）
        for (var entry : current) {
            if (gtlcore$lastSentStored.get(entry.getKey()) != entry.getLongValue()) {
                gtlcore$updateHelper.addChange(entry.getKey());
            }
        }
        for (var entry : gtlcore$lastSentStored) {
            if (current.get(entry.getKey()) == 0) {
                gtlcore$updateHelper.addChange(entry.getKey());
            }
        }
        if (!gtlcore$updateHelper.hasChanges()) return;

        var builder = MEInventoryUpdatePacket.builder(containerId, false);
        builder.addChanges(gtlcore$updateHelper, current, Set.of(), new KeyCounter());
        builder.buildAndSend(this::sendPacketToClient);
        gtlcore$updateHelper.commitChanges();
        gtlcore$lastSentStored = current;
    }

    @Inject(method = "broadcastChanges",
            at = @At(value = "INVOKE", target = "Lappeng/menu/AEBaseMenu;broadcastChanges()V"))
    private void gtlcore$syncMissingCraftAvailability(CallbackInfo ci) {
        this.gtlcore$missingCraftAvailable = ConfigHolder.INSTANCE.enableAe2MissingCrafting &&
                gtlcore$findMissingCraftCpu(null) != null;
    }

    @Unique
    private KeyCounter gtlcore$getRelevantStoredAmounts() {
        KeyCounter relevantStored = new KeyCounter();
        var grid = getGrid();
        if (grid == null) {
            return relevantStored;
        }

        var cachedInventory = grid.getStorageService().getCachedInventory();
        for (var entry : this.plan.getEntries()) {
            long stored = cachedInventory.get(entry.getWhat());
            if (stored > 0) {
                relevantStored.add(entry.getWhat(), stored);
            }
        }
        return relevantStored;
    }

    @Inject(method = "planJob", at = @At("HEAD"), remap = false)
    private void gtlcore$prepareForPlan(AEKey whatToCraft, int amount, CalculationStrategy strategy,
                                        CallbackInfoReturnable<Boolean> cir) {
        this.gtlcore$releaseInventoryReservation();
        this.gtlcore$calculationStrategy = strategy;
    }

    @Redirect(
              method = "startJob",
              at = @At(
                       value = "INVOKE",
                       target = "Lappeng/api/networking/crafting/ICraftingService;submitJob(Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;ZLappeng/api/networking/security/IActionSource;)Lappeng/api/networking/crafting/ICraftingSubmitResult;"),
              remap = false)
    private ICraftingSubmitResult gtlcore$submitWithReservedInventory(
                                                                      ICraftingService craftingService,
                                                                      ICraftingPlan plan,
                                                                      ICraftingRequester requester,
                                                                      ICraftingCPU target,
                                                                      boolean prioritizePower,
                                                                      IActionSource source) {
        TransfiniteCraftingCPU missingCraftCpu = ConfigHolder.INSTANCE.enableAe2MissingCrafting && plan.simulation() ?
                gtlcore$findMissingCraftCpu(plan) : null;
        ICraftingCPU submittedTarget = missingCraftCpu == null ? target : missingCraftCpu;
        ICraftingPlan submittedPlan = missingCraftCpu != null ?
                new MissingCraftingPlan(plan) : plan;
        var reservation = plan == this.gtlcore$reservedPlan ? this.gtlcore$inventoryReservation : null;
        ICraftingSubmitResult submitResult = reservation == null ?
                craftingService.submitJob(submittedPlan, requester, submittedTarget, prioritizePower, source) :
                reservation.submit(() -> craftingService.submitJob(
                        submittedPlan, requester, submittedTarget, prioritizePower, source));
        if (submitResult.successful()) {
            this.gtlcore$releaseInventoryReservation();
        } else if (plan instanceof AeGraphPlan graph && ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging) {
            GTLCore.LOGGER.warn("[Graph Crafting] confirmation rejected plan={} result={} simulation={} cpu={} error={} detail={}",
                    graph.id(), graph.graph().result(), submittedPlan.simulation(),
                    submittedTarget == null ? "auto" : submittedTarget.getClass().getName(), submitResult.errorCode(), submitResult.errorDetail());
        }
        return submitResult;
    }

    @Redirect(
              method = "startJob",
              at = @At(
                       value = "INVOKE",
                       target = "Lappeng/api/networking/crafting/ICraftingPlan;simulation()Z"),
              remap = false)
    private boolean gtlcore$allowMissingCraftOnTransfiniteCpu(ICraftingPlan plan) {
        return plan.simulation() && (!ConfigHolder.INSTANCE.enableAe2MissingCrafting ||
                gtlcore$findMissingCraftCpu(plan) == null);
    }

    @Unique
    private @Nullable TransfiniteCraftingCPU gtlcore$findMissingCraftCpu(@Nullable ICraftingPlan plan) {
        if (this.selectedCpu instanceof TransfiniteCraftingCPU selected) {
            return gtlcore$isUsableMissingCraftCpu(selected, plan, false) ? selected : null;
        }
        if (this.selectedCpu != null) {
            return null;
        }

        var grid = getGrid();
        if (grid == null) {
            return null;
        }
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            if (cpu instanceof TransfiniteCraftingCPU candidate &&
                    gtlcore$isUsableMissingCraftCpu(candidate, plan, true)) {
                return candidate;
            }
        }
        return null;
    }

    @Unique
    private boolean gtlcore$isUsableMissingCraftCpu(TransfiniteCraftingCPU cpu, @Nullable ICraftingPlan plan,
                                                    boolean automaticSelection) {
        return cpu.isCapacityView() && cpu.isActive() &&
                (plan == null || cpu.getAvailableStorage() >= plan.bytes()) &&
                (!automaticSelection || cpu.getHost().canBeAutoSelectedFor(this.getActionSource()));
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void gtlcore$releaseInventoryOnClose(net.minecraft.world.entity.player.Player player, CallbackInfo ci) {
        this.gtlcore$releaseInventoryReservation();
    }

    @Unique
    private boolean gtlcore$tryReserveInventory(ICraftingPlan craftingPlan) {
        this.gtlcore$releaseInventoryReservation();
        var grid = this.getGrid();
        if (grid == null) {
            return false;
        }

        var reservation = ManualCraftingInventoryLock.tryAcquire(
                grid.getStorageService().getInventory(), craftingPlan.usedItems(), this.getActionSource());
        if (reservation == null) {
            return false;
        }
        this.gtlcore$inventoryReservation = reservation;
        this.gtlcore$reservedPlan = craftingPlan;
        return true;
    }

    @Unique
    private void gtlcore$restartCalculationAfterReservationConflict() {
        this.result = null;
        this.plan = null;
        this.gtlcore$lastSentStored = null;
        if (this.whatToCraft == null || !this.gtlcore$planLongAmountJob(
                this.whatToCraft,
                CraftAmountReturnState.displayAmount(this.gtlcore$longAmount, this.amount),
                this.gtlcore$calculationStrategy)) {
            this.gtlcore$returnToPreviousMenu();
        }
    }

    @Unique
    private void gtlcore$releaseInventoryReservation() {
        if (this.gtlcore$inventoryReservation != null) {
            this.gtlcore$inventoryReservation.close();
            this.gtlcore$inventoryReservation = null;
        }
        this.gtlcore$reservedPlan = null;
    }

    @Inject(method = "goBack", at = @At("HEAD"), cancellable = true, remap = false)
    private void goBackWithLongAmount(CallbackInfo ci) {
        if (this.isClientSide()) return;

        ci.cancel();
        this.clearError();
        this.gtlcore$releaseInventoryReservation();
        this.gtlcore$returnToPreviousMenu();
    }

    @Inject(method = "replan", at = @At("HEAD"), cancellable = true, remap = false)
    private void replanWithLongAmount(CallbackInfo ci) {
        if (this.isClientSide()) return;

        ci.cancel();
        this.clearError();
        if (this.whatToCraft == null || !this.gtlcore$planLongAmountJob(
                this.whatToCraft,
                CraftAmountReturnState.displayAmount(this.gtlcore$longAmount, this.amount),
                CalculationStrategy.CRAFT_LESS)) {
            this.gtlcore$returnToPreviousMenu();
        }
    }

    @Unique
    private void gtlcore$returnToPreviousMenu() {
        var player = this.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        if (this.autoCraftingQueue != null && !this.autoCraftingQueue.isEmpty()) {
            CraftConfirmMenu.openWithCraftingList(
                    this.getActionHost(), serverPlayer, this.getLocator(), this.autoCraftingQueue);
        } else if (this.whatToCraft != null) {
            this.gtlcore$openLongAmountMenu(
                    serverPlayer, CraftAmountReturnState.displayAmount(this.gtlcore$longAmount, this.amount));
        } else {
            this.host.returnToMainMenu(player, (ISubMenu) (Object) this);
        }
    }

    @Unique
    private void gtlcore$openLongAmountMenu(ServerPlayer player, long amount) {
        MenuOpener.open(CraftAmountMenu.TYPE, player, this.getLocator());
        if (player.containerMenu instanceof CraftAmountMenu amountMenu) {
            ((ILongCraftAmountMenu) amountMenu).gtlcore$setLongWhatToCraft(this.whatToCraft, amount);
            amountMenu.broadcastChanges();
        }
    }
}
