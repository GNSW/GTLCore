package org.gtlcore.gtlcore.integration.ae2.manipulator;

import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorResources;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorTask;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Temporary real AE provider; inputs are delivered into the owning construction task. */
public final class ManipulatorPlanService implements ICraftingProvider {

    private final ManipulatorTask task;
    private final ManipulatorNetwork network;
    private Plan pattern;
    private AEItemKey marker;
    private long pendingOutput;
    private boolean refresh;
    private boolean automatic;

    public ManipulatorPlanService(ManipulatorTask task, ManipulatorNetwork network) {
        this.task = task;
        this.network = network;
        network.addProvider(this);
    }

    public boolean publish(ServerPlayer player, IGrid grid, int mode) {
        if (grid == null) {
            task.status("mm.status.network");
            return false;
        }
        Map<AEKey, Long> inputs = new LinkedHashMap<>();
        task.remaining.forEach((key, amount) -> {
            long required = mode >= 3 ? amount : amount - task.escrow.getOrDefault(key, 0L) - ManipulatorResources.extract(player, grid, key, amount, true);
            ManipulatorResources.add(inputs, key, required);
        });
        if (inputs.isEmpty()) {
            task.status("mm.status.no_missing");
            return true;
        }
        ItemStack token = GTLItems.MATTER_MANIPULATOR.asStack();
        token.getOrCreateTag().putUUID("ManipulatorPlanMarker", task.id);
        token.setHoverName(Component.literal(player.getGameProfile().getName() + "'s Manipulator Plan (" + task.settings.a.toShortString() + ") " + task.id));
        marker = AEItemKey.of(token);
        pattern = new Plan(marker, inputs);
        automatic = (mode & 1) == 0;
        refresh = true;
        ICraftingProvider.requestUpdate(network.managedNode());
        if ((mode & 1) == 0) network.requestPlan(marker);
        task.status("mm.status.plan_published");
        task.changed();
        return true;
    }

    public boolean isMarker(AEKey key) {
        return marker != null && marker.equals(key);
    }

    public void clear() {
        pattern = null;
        refresh = true;
        task.changed();
    }

    public void clearManual() {
        if (!automatic) clear();
    }

    public void cancelOrders() {
        network.cancel();
        if (automatic) clear();
    }

    public void disconnect() {
        refresh = true;
    }

    public void tick(ServerPlayer player, IGrid grid) {
        if (grid == null) return;
        if (refresh) {
            ICraftingProvider.requestUpdate(network.managedNode());
            refresh = false;
        }
        if (pendingOutput > 0 && marker != null) {
            pendingOutput -= grid.getStorageService().getInventory().insert(marker, pendingOutput, Actionable.MODULATE, IActionSource.ofPlayer(player));
            task.changed();
        }
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return pattern == null ? List.of() : List.of(pattern);
    }

    @Override
    public boolean isBusy() {
        return pendingOutput != 0;
    }

    @Override
    public boolean pushPattern(IPatternDetails requested, KeyCounter[] inputs) {
        if (requested != pattern || pendingOutput != 0 || task.cancelled) return false;
        for (KeyCounter counter : inputs) for (var entry : counter) ManipulatorResources.add(task.escrow, entry.getKey(), entry.getLongValue());
        for (KeyCounter counter : inputs) counter.clear();
        pendingOutput = 1;
        task.changed();
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (marker != null) tag.put("Marker", marker.toTag());
        if (pattern != null) tag.put("Inputs", ManipulatorResources.save(pattern.inputs));
        tag.putLong("PendingOutput", pendingOutput);
        tag.putBoolean("Automatic", automatic);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag.contains("Marker")) marker = AEItemKey.fromTag(tag.getCompound("Marker"));
        if (marker != null && tag.contains("Inputs")) pattern = new Plan(marker, ManipulatorResources.read(tag.getList("Inputs", Tag.TAG_COMPOUND)));
        pendingOutput = tag.getLong("PendingOutput");
        automatic = tag.getBoolean("Automatic");
        refresh = true;
    }

    private record Plan(AEItemKey marker, Map<AEKey, Long> inputs) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return marker;
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[] { new GenericStack(marker, 1) };
        }

        @Override
        public IInput[] getInputs() {
            return inputs.entrySet().stream().map(entry -> new IInput() {

                @Override
                public GenericStack[] getPossibleInputs() {
                    return new GenericStack[] { new GenericStack(entry.getKey(), entry.getValue()) };
                }

                @Override
                public long getMultiplier() {
                    return 1;
                }

                @Override
                public boolean isValid(AEKey key, Level level) {
                    return entry.getKey().equals(key);
                }

                @Override
                public AEKey getRemainingKey(AEKey key) {
                    return null;
                }
            }).toArray(IInput[]::new);
        }
    }
}
