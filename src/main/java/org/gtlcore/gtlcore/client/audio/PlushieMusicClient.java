package org.gtlcore.gtlcore.client.audio;

import org.gtlcore.gtlcore.client.renderer.block.PlushieBlockRenderer;
import org.gtlcore.gtlcore.common.block.PlushieBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Owns positional streams for all playing plushies in the client level. */
public final class PlushieMusicClient {

    private static final Map<BlockPos, PlushieMusicSound> SOUNDS = new HashMap<>();
    private static Object lastLevel;
    private static int soundCheckTicks;

    private PlushieMusicClient() {}

    public static void register() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        PlushieBlockRenderer.register(modBus);
        MinecraftForge.EVENT_BUS.addListener(PlushieMusicClient::clientTick);
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (lastLevel != minecraft.level) {
            stopAll();
            lastLevel = minecraft.level;
        }
        if (minecraft.level == null || minecraft.isPaused()) return;
        if (minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0 ||
                minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0) {
            stopAll();
            return;
        }

        boolean checkChannels = ++soundCheckTicks % 20 == 0;
        Set<BlockPos> positions = new HashSet<>();
        for (PlushieBlockEntity plushie : PlushieBlockEntity.getActivePlushies(minecraft.level)) {
            BlockPos pos = plushie.getBlockPos();
            if (minecraft.player == null || minecraft.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > 400) {
                continue;
            }
            positions.add(pos);
            PlushieMusicSound sound = SOUNDS.get(pos);
            if (sound == null || !sound.matchesPlayingPlushie(plushie) ||
                    checkChannels && !minecraft.getSoundManager().isActive(sound)) {
                if (sound != null) sound.cancel();
                sound = new PlushieMusicSound(plushie);
                SOUNDS.put(pos, sound);
                minecraft.getSoundManager().play(sound);
            }
        }
        SOUNDS.entrySet().removeIf(entry -> {
            if (positions.contains(entry.getKey())) return false;
            entry.getValue().cancel();
            return true;
        });
    }

    private static void stopAll() {
        SOUNDS.values().forEach(PlushieMusicSound::cancel);
        SOUNDS.clear();
    }
}
