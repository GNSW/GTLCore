package org.gtlcore.gtlcore.common.plushie;

import org.gtlcore.gtlcore.integration.ae2.wireless.CuriosCompat;
import org.gtlcore.gtlcore.network.PlushieHeadPackets;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/** Server-side state for the plushie bounce started with /winejump. */
public final class PlushieHeadJump {

    private static final String TAG_JUMPING = "gtlcore_plushie_jump";
    private static final String TAG_BGM = "gtlcore_plushie_jump_bgm";
    private static final String HEAD_CURIO_SLOT = "head";

    private PlushieHeadJump() {}

    public static boolean isWearingPlushie(Player player) {
        if (PlushieItems.isPlushie(player.getItemBySlot(EquipmentSlot.HEAD))) {
            return true;
        }
        return CuriosCompat.findCurio(player, HEAD_CURIO_SLOT, PlushieItems::isPlushie) != null;
    }

    public static boolean isJumping(Player player) {
        return player.getPersistentData().getBoolean(TAG_JUMPING);
    }

    public static boolean isBgmEnabled(Player player) {
        return player.getPersistentData().getBoolean(TAG_BGM);
    }

    public static void start(ServerPlayer player, boolean bgm) {
        player.getPersistentData().putBoolean(TAG_JUMPING, true);
        player.getPersistentData().putBoolean(TAG_BGM, bgm);
        sync(player);
    }

    public static void stop(ServerPlayer player) {
        player.getPersistentData().putBoolean(TAG_JUMPING, false);
        player.getPersistentData().putBoolean(TAG_BGM, false);
        sync(player);
    }

    public static void setBgm(ServerPlayer player, boolean bgm) {
        player.getPersistentData().putBoolean(TAG_BGM, bgm);
        sync(player);
    }

    public static void sync(ServerPlayer player) {
        PlushieHeadPackets.send(player, isJumping(player), isBgmEnabled(player));
    }

    public static void syncTo(ServerPlayer player, ServerPlayer receiver) {
        PlushieHeadPackets.sendTo(player, receiver, isJumping(player), isBgmEnabled(player));
    }
}
