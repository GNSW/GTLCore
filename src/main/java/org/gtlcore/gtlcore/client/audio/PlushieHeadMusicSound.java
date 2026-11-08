package org.gtlcore.gtlcore.client.audio;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.plushie.PlushieHeadClient;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/** One positional source that follows a head plushie while it bounces. */
public final class PlushieHeadMusicSound extends AbstractTickableSoundInstance {

    private final LivingEntity wearer;
    private final Level level;
    private final long startTick;
    private volatile long elapsedTicks;

    public PlushieHeadMusicSound(LivingEntity wearer, long startTick) {
        super(SoundEvent.createVariableRangeEvent(GTLCore.id("plushie_music")), SoundSource.RECORDS,
                SoundInstance.createUnseededRandom());
        this.wearer = wearer;
        level = wearer.level();
        this.startTick = startTick;
        elapsedTicks = Math.max(0, level.getGameTime() - startTick);
        updatePosition();
        looping = true;
        relative = false;
        attenuation = SoundInstance.Attenuation.LINEAR;
    }

    public boolean matchesWearer(LivingEntity candidate) {
        return candidate == wearer && !isStopped() && PlushieHeadClient.isJumping(wearer.getId());
    }

    public void cancel() {
        stop();
    }

    private void updatePosition() {
        Vec3 position = wearer.getEyePosition();
        x = position.x;
        y = position.y;
        z = position.z;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return !isStopped() && !wearer.isRemoved() && wearer.level() == level &&
                PlushieHeadClient.isJumping(wearer.getId());
    }

    @Override
    public void tick() {
        updatePosition();
        elapsedTicks = Math.max(0, level.getGameTime() - startTick);
        if (!canPlaySound()) stop();
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return PlushieAudioStream.openSeeked(buffers, sound, () -> elapsedTicks);
    }
}
