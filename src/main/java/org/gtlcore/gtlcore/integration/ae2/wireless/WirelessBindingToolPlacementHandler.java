package org.gtlcore.gtlcore.integration.ae2.wireless;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Binds a freshly placed target to the network saved in the binding tool when the player sneak-places it.
 * <p>
 * Only applies while the tool is <em>not</em> in the main hand: a tool in hand intercepts the
 * right-click in {@link WirelessNetworkBindingToolItem#onItemUseFirst} before the block is placed.
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class WirelessBindingToolPlacementHandler {

    /** Placement runs before the block entity exists, so bind a couple of ticks later. */
    private static final int BIND_DELAY_TICKS = 2;
    /** The block entity may still be settling; retry a few times before giving up. */
    private static final int MAX_ATTEMPTS = 10;

    private static final List<PendingBind> PENDING = new ArrayList<>();

    private WirelessBindingToolPlacementHandler() {}

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isShiftKeyDown()) {
            return;
        }
        if (event.getPlacedBlock().isAir()) {
            return;
        }
        ItemStack stack = WirelessBindingToolLocator.find(player);
        if (stack.isEmpty()) {
            return;
        }
        UUID frequency = WirelessNetworkBindingToolItem.savedNetwork(stack);
        if (frequency == null) {
            return;
        }
        // Deliberately no target check here: at this point the block entity does not exist yet.
        PENDING.add(new PendingBind(player, event.getPos(), frequency));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        List<PendingBind> ready = new ArrayList<>();
        List<PendingBind> retry = new ArrayList<>();
        Iterator<PendingBind> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingBind pending = iterator.next();
            if (--pending.remaining > 0) {
                continue;
            }
            iterator.remove();
            if (pending.player.hasDisconnected() || !pending.player.isAlive()) {
                continue;
            }
            ServerLevel level = pending.player.serverLevel();
            if (!WirelessAeNetworkRuntime.canBindAsWirelessTarget(level, pending.pos)) {
                // Not a wireless target yet; try again next tick until we run out of attempts.
                if (pending.attempts++ < MAX_ATTEMPTS) {
                    pending.remaining = 1;
                    retry.add(pending);
                }
                continue;
            }
            ready.add(pending);
        }
        PENDING.addAll(retry);
        for (PendingBind pending : ready) {
            ServerLevel level = pending.player.serverLevel();
            WirelessNetworkBindingToolItem.bindTo(
                    pending.player,
                    WirelessAeNetworkRuntime.resolveWirelessTarget(level, pending.pos, null),
                    pending.frequency,
                    true);
        }
    }

    private static final class PendingBind {

        private final ServerPlayer player;
        private final BlockPos pos;
        private final UUID frequency;
        private int remaining = BIND_DELAY_TICKS;
        private int attempts;

        private PendingBind(ServerPlayer player, BlockPos pos, UUID frequency) {
            this.player = player;
            this.pos = pos;
            this.frequency = frequency;
        }
    }
}
