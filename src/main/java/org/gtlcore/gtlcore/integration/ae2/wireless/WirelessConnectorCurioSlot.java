package org.gtlcore.gtlcore.integration.ae2.wireless;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;

import top.theillusivec4.curios.api.SlotTypeMessage;
import top.theillusivec4.curios.api.type.util.ISlotHelper;

/**
 * Dedicated Curios slot for the wireless network binding tool, so it does not share the generic
 * {@code curio} slot with the wireless terminals.
 * <p>
 * Curios is optional: every Curios call sits in a method that only runs after the
 * {@code ModList} check, so the classes are not resolved when Curios is absent.
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WirelessConnectorCurioSlot {

    public static final String SLOT_ID = "wireless_connector";

    private WirelessConnectorCurioSlot() {}

    @SubscribeEvent
    public static void enqueue(InterModEnqueueEvent event) {
        if (!ModList.get().isLoaded("curios")) {
            return;
        }
        registerSlot();
    }

    private static void registerSlot() {
        InterModComms.sendTo("curios", SlotTypeMessage.REGISTER_TYPE, () -> new SlotTypeMessage.Builder(SLOT_ID)
                .icon(new ResourceLocation(GTLCore.MOD_ID, "slot/wireless_connector"))
                .size(1)
                .priority(150)
                .build());
    }

    /** Curios does not create slots by itself; hand every player one. */
    @Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
    static final class Grant {

        private Grant() {}

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                ensureSlot(player);
            }
        }

        @SubscribeEvent
        public static void onClone(PlayerEvent.Clone event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                ensureSlot(player);
            }
        }

        private static void ensureSlot(ServerPlayer player) {
            if (!ModList.get().isLoaded("curios")) {
                return;
            }
            CuriosSlotAccess.ensure(player);
        }
    }

    /** Only loaded when Curios is present. */
    static final class CuriosSlotAccess {

        private CuriosSlotAccess() {}

        static void ensure(ServerPlayer player) {
            ISlotHelper helper = top.theillusivec4.curios.api.CuriosApi.getSlotHelper();
            if (helper != null && helper.getSlotsForType(player, SLOT_ID) < 1) {
                helper.growSlotType(SLOT_ID, 1, player);
            }
        }
    }
}
