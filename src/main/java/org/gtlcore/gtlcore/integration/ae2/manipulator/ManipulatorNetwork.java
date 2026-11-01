package org.gtlcore.gtlcore.integration.ae2.manipulator;

import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorResources;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorTask;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.compat.FeCompat;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.PowerUnits;
import appeng.api.features.GridLinkables;
import appeng.api.features.IGridLinkableHandler;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** One requester per saved task: owned output goes directly into that task's escrow. */
public final class ManipulatorNetwork implements ICraftingRequester {

    public static final IGridLinkableHandler LINK = new IGridLinkableHandler() {

        public boolean canLink(ItemStack stack) {
            return stack.is(GTLItems.MATTER_MANIPULATOR.get());
        }

        public void link(ItemStack stack, GlobalPos pos) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", pos.dimension().location().toString());
            tag.putLong("Pos", pos.pos().asLong());
            stack.getOrCreateTag().put("ManipulatorNetwork", tag);
        }

        public void unlink(ItemStack stack) {
            stack.removeTagKey("ManipulatorNetwork");
        }
    };

    public static void register() {
        GridLinkables.register(GTLItems.MATTER_MANIPULATOR, LINK);
    }

    public static IWirelessAccessPoint accessPoint(ServerPlayer player, CompoundTag binding) {
        if (!binding.contains("Dimension")) return null;
        var dimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(binding.getString("Dimension")));
        var level = player.server.getLevel(dimension);
        var pos = net.minecraft.core.BlockPos.of(binding.getLong("Pos"));
        if (level == null || !level.hasChunkAt(pos)) return null;
        if (!(level.getBlockEntity(pos) instanceof IWirelessAccessPoint access)) return null;
        return access;
    }

    public static IGrid grid(ServerPlayer player, ItemStack stack) {
        var access = accessPoint(player, stack.getOrCreateTag().getCompound("ManipulatorNetwork"));
        return access == null || access.getGrid() == null || !access.getGrid().getEnergyService().isNetworkPowered() ? null : access.getGrid();
    }

    private final ManipulatorTask task;
    private final CompoundTag nodeData;
    private IManagedGridNode node;
    private ManipulatorPlanService provider;
    private IGridNode boundNode;
    private final Map<ICraftingLink, AEKey> links = new LinkedHashMap<>();
    private final Map<AEKey, Long> outstanding = new LinkedHashMap<>();
    private Future<ICraftingPlan> calculation;
    private AEKey calculatingKey;
    private long calculatingAmount;
    private boolean failed;
    private final Map<AEKey, Long> planRequests = new LinkedHashMap<>();
    private Map<AEKey, Long> requestMap;
    private java.util.Iterator<Map.Entry<AEKey, Long>> requestIterator;

    public ManipulatorNetwork(ManipulatorTask task, CompoundTag saved) {
        this.task = task;
        nodeData = saved.getCompound("Node").copy();
        node = createNode();
        planRequests.putAll(ManipulatorResources.read(saved.getList("PlanRequests", Tag.TAG_COMPOUND)));
        for (Tag t : saved.getList("Links", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            ICraftingLink link = new CraftingLink(entry.getCompound("Link"), this);
            AEKey key = AEKey.fromTagGeneric(entry.getCompound("Key"));
            links.put(link, key);
            ManipulatorResources.add(outstanding, key, entry.getLong("Amount"));
        }
    }

    private IManagedGridNode createNode() {
        var managed = GridHelper.createManagedNode(this, new IGridNodeListener<ManipulatorNetwork>() {

            public void onSaveChanges(ManipulatorNetwork owner, IGridNode n) {
                task.changed();
            }
        }).setInWorldNode(false).setIdlePowerUsage(0).setVisualRepresentation(GTLItems.MATTER_MANIPULATOR)
                .addService(ICraftingRequester.class, this);
        managed.loadFromNBT(nodeData);
        if (provider != null) managed.addService(ICraftingProvider.class, provider);
        return managed;
    }

    private void resetNode() {
        if (node.isReady()) {
            node.saveToNBT(nodeData);
            node.destroy();
            // AE2 managed nodes cannot be initialized again after destruction.
            node = createNode();
        }
        boundNode = null;
    }

    public IGrid connect(ServerPlayer player) {
        var access = accessPoint(player, task.binding);
        var target = access == null ? null : access.getActionableNode();
        if (target == null) {
            resetNode();
            return null;
        }
        if (target != boundNode || !node.isReady()) {
            resetNode();
            node.setOwningPlayer(player);
            node.create(player.serverLevel(), null);
            GridHelper.createConnection(node.getNode(), target);
            boundNode = target;
        }
        IGrid grid = target.getGrid();
        return grid.getEnergyService().isNetworkPowered() ? grid : null;
    }

    public void addProvider(ManipulatorPlanService provider) {
        this.provider = provider;
        node.addService(ICraftingProvider.class, provider);
    }

    public IManagedGridNode managedNode() {
        return node;
    }

    public void requestPlan(AEKey marker) {
        planRequests.put(marker, 1L);
        failed = false;
        task.changed();
    }

    public void tick(ServerPlayer player, IGrid grid, Map<AEKey, Long> remaining) {
        for (var iterator = links.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (entry.getKey().isDone() || entry.getKey().isCanceled()) {
                outstanding.remove(entry.getValue());
                iterator.remove();
                task.changed();
            }
        }
        if (grid == null || !task.autoOrder) return;
        if (calculation != null) {
            if (!calculation.isDone()) return;
            try {
                ICraftingPlan plan = calculation.get();
                if (plan.simulation()) {
                    task.status("mm.error.ingredients");
                    failed = true;
                } else {
                    var result = grid.getCraftingService().submitJob(plan, this, null, false, IActionSource.ofPlayer(player));
                    if (!result.successful() || result.link() == null) {
                        task.status("mm.error.cpu");
                        failed = true;
                    } else {
                        links.put(result.link(), calculatingKey);
                        ManipulatorResources.add(outstanding, calculatingKey, calculatingAmount);
                        planRequests.remove(calculatingKey);
                        requestIterator = null;
                        task.changed();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                task.status("mm.error.calculation");
                failed = true;
            } catch (ExecutionException exception) {
                org.gtlcore.gtlcore.GTLCore.LOGGER.error("Manipulator crafting calculation failed for {}", task.id, exception.getCause());
                task.status("mm.error.calculation");
                failed = true;
            }
            calculation = null;
        }
        if (failed) return;
        Map<AEKey, Long> requests = planRequests.isEmpty() ? remaining : planRequests;
        if (requestMap != requests || requestIterator == null) {
            requestMap = requests;
            requestIterator = requests.entrySet().iterator();
        }
        int budget = Math.max(1, org.gtlcore.gtlcore.config.ConfigHolder.INSTANCE.manipulator.operationsPerSecond / net.minecraft.SharedConstants.TICKS_PER_SECOND);
        while (budget-- > 0 && requestIterator.hasNext()) {
            var entry = requestIterator.next();
            if (outstanding.containsKey(entry.getKey())) continue;
            long available = ManipulatorResources.extract(player, grid, entry.getKey(), entry.getValue(), true);
            long missing = entry.getValue() - task.escrow.getOrDefault(entry.getKey(), 0L) - available;
            if (missing <= 0) continue;
            if (!grid.getCraftingService().isCraftable(entry.getKey())) {
                task.status("mm.error.pattern");
                continue;
            }
            calculatingKey = entry.getKey();
            calculatingAmount = missing;
            calculation = grid.getCraftingService().beginCraftingCalculation(player.serverLevel(), () -> IActionSource.ofPlayer(player),
                    calculatingKey, missing, CalculationStrategy.REPORT_MISSING_ITEMS);
            break;
        }
        if (!requestIterator.hasNext()) requestIterator = null;
    }

    public void retry() {
        failed = false;
    }

    public void cancel() {
        if (calculation != null) {
            calculation.cancel(true);
            calculation = null;
        }
        new ArrayList<>(links.keySet()).forEach(ICraftingLink::cancel);
        links.clear();
        outstanding.clear();
        planRequests.clear();
        requestIterator = null;
        task.changed();
    }

    public void close() {
        if (calculation != null) {
            calculation.cancel(true);
            calculation = null;
        }
        resetNode();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        node.saveToNBT(nodeData);
        tag.put("Node", nodeData.copy());
        ListTag list = new ListTag();
        links.forEach((link, key) -> {
            CompoundTag entry = new CompoundTag();
            CompoundTag linkTag = new CompoundTag();
            link.writeToNBT(linkTag);
            entry.put("Link", linkTag);
            entry.put("Key", key.toTagGeneric());
            entry.putLong("Amount", outstanding.getOrDefault(key, 0L));
            list.add(entry);
        });
        tag.put("Links", list);
        tag.put("PlanRequests", ManipulatorResources.save(planRequests));
        return tag;
    }

    public static boolean charge(ItemStack tool, IGrid grid, long eu) {
        var electric = GTCapabilityHelper.getElectricItem(tool);
        if (electric == null) return false;
        long local = electric.discharge(eu, electric.getTier(), true, false, true);
        long missing = eu - local;
        if (missing > 0) {
            if (grid == null) return false;
            if (eu > electric.getMaxCharge()) return false;
            double ae = PowerUnits.FE.convertTo(PowerUnits.AE, (double) missing * FeCompat.ratio(true));
            if (!Double.isFinite(ae) || grid.getEnergyService().extractAEPower(ae, Actionable.SIMULATE, PowerMultiplier.ONE) < ae) return false;
            double taken = grid.getEnergyService().extractAEPower(ae, Actionable.MODULATE, PowerMultiplier.ONE);
            long supplied = Math.min(missing, (long) Math.floor(PowerUnits.AE.convertTo(PowerUnits.FE, taken) / FeCompat.ratio(true)));
            electric.charge(supplied, electric.getTier(), true, false);
            if (supplied < missing) return false;
        }
        return electric.discharge(eu, electric.getTier(), true, false, false) == eu;
    }

    public static void refund(ItemStack tool, long eu) {
        var electric = GTCapabilityHelper.getElectricItem(tool);
        electric.charge(eu, electric.getTier(), true, false);
    }

    @Override
    public IGridNode getActionableNode() {
        return node.getNode();
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return ImmutableSet.copyOf(links.keySet());
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey key, long amount, Actionable action) {
        if (!key.equals(links.get(link))) return 0;
        long accepted = Math.min(amount, outstanding.getOrDefault(key, 0L));
        if (action == Actionable.MODULATE) {
            if (!task.plans.isMarker(key)) ManipulatorResources.add(task.escrow, key, accepted);
            outstanding.computeIfPresent(key, (k, n) -> n - accepted);
            task.changed();
        }
        return accepted;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        task.changed();
    }
}
