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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
        Set<BlockPos> activeAnchors = new HashSet<>();
        for (SoundGroup group : findGroups(minecraft)) {
            PlushieMusicSound sound = findExisting(group);
            if (sound == null || !sound.matchesGroup(minecraft.level, group.startTick()) ||
                    checkChannels && !minecraft.getSoundManager().isActive(sound)) {
                if (sound != null) sound.cancel();
                sound = new PlushieMusicSound(minecraft.level, group.anchor(), group.startTick(), group.count());
                minecraft.getSoundManager().play(sound);
            } else {
                sound.updateGroup(group.count());
            }
            activeAnchors.add(sound.getAnchor());
            SOUNDS.put(sound.getAnchor(), sound);
        }
        SOUNDS.entrySet().removeIf(entry -> {
            if (activeAnchors.contains(entry.getKey())) return false;
            entry.getValue().cancel();
            return true;
        });
    }

    private static List<SoundGroup> findGroups(Minecraft minecraft) {
        List<PlushieBlockEntity> active = PlushieBlockEntity.getActivePlushies(minecraft.level);
        boolean[] visited = new boolean[active.size()];
        List<SoundGroup> groups = new ArrayList<>();
        double rangeSquared = PlushieBlockEntity.MUSIC_RANGE * PlushieBlockEntity.MUSIC_RANGE;
        double audibleSquared = PlushieBlockEntity.MUSIC_RANGE * PlushieBlockEntity.MUSIC_RANGE;

        for (int seed = 0; seed < active.size(); seed++) {
            if (visited[seed]) continue;
            List<PlushieBlockEntity> members = new ArrayList<>();
            List<Integer> pending = new ArrayList<>();
            visited[seed] = true;
            pending.add(seed);
            for (int cursor = 0; cursor < pending.size(); cursor++) {
                PlushieBlockEntity current = active.get(pending.get(cursor));
                members.add(current);
                for (int candidate = 0; candidate < active.size(); candidate++) {
                    if (visited[candidate] || current.getBlockPos().distSqr(active.get(candidate).getBlockPos()) > rangeSquared) {
                        continue;
                    }
                    visited[candidate] = true;
                    pending.add(candidate);
                }
            }

            if (minecraft.player == null || members.stream().noneMatch(plushie -> minecraft.player.position().distanceToSqr(Vec3.atCenterOf(plushie.getBlockPos())) <= audibleSquared)) {
                continue;
            }

            PlushieBlockEntity anchor = members.get(0);
            for (PlushieBlockEntity member : members) {
                if (member.getStartTick() < anchor.getStartTick() ||
                        member.getStartTick() == anchor.getStartTick() &&
                                member.getBlockPos().asLong() < anchor.getBlockPos().asLong()) {
                    anchor = member;
                }
            }
            List<BlockPos> positions = members.stream().map(PlushieBlockEntity::getBlockPos).toList();
            groups.add(new SoundGroup(anchor.getBlockPos().immutable(), anchor.getStartTick(), members.size(), positions));
        }
        return groups;
    }

    private static PlushieMusicSound findExisting(SoundGroup group) {
        PlushieMusicSound direct = SOUNDS.get(group.anchor());
        if (direct != null) return direct;
        for (BlockPos position : group.positions()) {
            PlushieMusicSound sound = SOUNDS.get(position);
            if (sound != null) return sound;
        }
        return null;
    }

    private static void stopAll() {
        SOUNDS.values().forEach(PlushieMusicSound::cancel);
        SOUNDS.clear();
    }

    private record SoundGroup(BlockPos anchor, long startTick, int count, List<BlockPos> positions) {}
}
