package org.gtlcore.gtlcore.client.audio;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.block.PlushieBlockEntity;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** One positional source, seeked to the shared server tick when it is created. */
public final class PlushieMusicSound extends AbstractTickableSoundInstance {

    private final PlushieBlockEntity plushie;
    private final Level level;
    private final long startTick;
    private volatile long elapsedTicks;

    public PlushieMusicSound(PlushieBlockEntity plushie) {
        super(SoundEvent.createVariableRangeEvent(GTLCore.id("plushie_music")), SoundSource.RECORDS,
                SoundInstance.createUnseededRandom());
        this.plushie = plushie;
        level = Objects.requireNonNull(plushie.getLevel(), "Plushie sound must belong to a level");
        startTick = plushie.getStartTick();
        elapsedTicks = Math.max(0, level.getGameTime() - startTick);
        x = plushie.getBlockPos().getX() + 0.5;
        y = plushie.getBlockPos().getY() + 0.5;
        z = plushie.getBlockPos().getZ() + 0.5;
        looping = true;
        relative = false;
        attenuation = SoundInstance.Attenuation.LINEAR;
    }

    public boolean matchesPlayingPlushie(PlushieBlockEntity candidate) {
        return candidate == plushie && candidate.getStartTick() == startTick && candidate.isPlaying();
    }

    public void cancel() {
        stop();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return !isStopped() && !plushie.isRemoved() && plushie.getLevel() == level &&
                matchesPlayingPlushie(plushie) && level.getBlockEntity(plushie.getBlockPos()) == plushie;
    }

    @Override
    public void tick() {
        elapsedTicks = Math.max(0, level.getGameTime() - startTick);
        if (!canPlaySound()) stop();
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return PlushieAudioStream.openSeeked(buffers, sound, () -> elapsedTicks);
    }
}
