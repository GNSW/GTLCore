package org.gtlcore.gtlcore.client.plushie;

import org.gtlcore.gtlcore.client.audio.PlushieHeadMusicSound;
import org.gtlcore.gtlcore.common.block.PlushieAnimation;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Tracks which players bounce a head plushie and owns their synchronized music streams. */
public final class PlushieHeadClient {

    private static final Map<Integer, Long> START_TICKS = new HashMap<>();
    private static final Set<Integer> BGM = new HashSet<>();
    private static final Map<Integer, PlushieHeadMusicSound> SOUNDS = new HashMap<>();
    private static Level lastLevel;

    private PlushieHeadClient() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(PlushieHeadClient::clientTick);
    }

    public static void receive(int entityId, boolean active, boolean bgm) {
        if (!active) {
            START_TICKS.remove(entityId);
            BGM.remove(entityId);
            stopSound(entityId);
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        START_TICKS.putIfAbsent(entityId, level.getGameTime());
        if (bgm) {
            BGM.add(entityId);
        } else {
            BGM.remove(entityId);
            stopSound(entityId);
        }
    }

    public static boolean isJumping(int entityId) {
        return START_TICKS.containsKey(entityId);
    }

    public static float squash(int entityId, float partialTick) {
        Long startTick = START_TICKS.get(entityId);
        Level level = Minecraft.getInstance().level;
        if (startTick == null || level == null) return 0;
        double seconds = Math.max(0, level.getGameTime() - startTick + partialTick) / 20.0;
        return PlushieAnimation.musicSquash(seconds);
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (lastLevel != minecraft.level) {
            clear();
            lastLevel = minecraft.level;
        }
        if (minecraft.level == null || minecraft.isPaused()) return;
        boolean muted = minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0 ||
                minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0;
        Iterator<Map.Entry<Integer, Long>> iterator = START_TICKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Long> entry = iterator.next();
            int entityId = entry.getKey();
            Entity entity = minecraft.level.getEntity(entityId);
            if (!(entity instanceof LivingEntity wearer)) {
                iterator.remove();
                BGM.remove(entityId);
                stopSound(entityId);
                continue;
            }
            if (muted || !BGM.contains(entityId)) {
                stopSound(entityId);
                continue;
            }
            ensureSound(entityId, wearer, entry.getValue());
        }
    }

    private static void ensureSound(int entityId, LivingEntity wearer, long startTick) {
        PlushieHeadMusicSound sound = SOUNDS.get(entityId);
        if (sound != null && sound.matchesWearer(wearer)) return;
        if (sound != null) {
            sound.cancel();
            SOUNDS.remove(entityId);
        }
        PlushieHeadMusicSound created = new PlushieHeadMusicSound(wearer, startTick);
        SOUNDS.put(entityId, created);
        Minecraft.getInstance().getSoundManager().play(created);
    }

    private static void stopSound(int entityId) {
        PlushieHeadMusicSound sound = SOUNDS.remove(entityId);
        if (sound != null) sound.cancel();
    }

    private static void clear() {
        for (PlushieHeadMusicSound sound : SOUNDS.values()) sound.cancel();
        SOUNDS.clear();
        START_TICKS.clear();
        BGM.clear();
    }
}
