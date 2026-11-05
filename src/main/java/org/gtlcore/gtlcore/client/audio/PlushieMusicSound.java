package org.gtlcore.gtlcore.client.audio;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.block.PlushieAnimation;
import org.gtlcore.gtlcore.common.block.PlushieBlockEntity;

import net.minecraft.Util;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.sound.sampled.AudioFormat;

/** One positional music stream, seeked to the shared server tick when it is (re)created. */
public final class PlushieMusicSound extends AbstractTickableSoundInstance {

    private final PlushieBlockEntity plushie;
    private final Level level;
    private final long startTick;
    private volatile long elapsedTicks;

    public PlushieMusicSound(PlushieBlockEntity plushie) {
        super(SoundEvent.createVariableRangeEvent(GTLCore.id("plushie_music")), SoundSource.RECORDS,
                SoundInstance.createUnseededRandom());
        this.plushie = plushie;
        level = Objects.requireNonNull(plushie.getLevel(), "Plushie must belong to a level");
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
        return buffers.getStream(sound.getPath(), true).thenApplyAsync(stream -> {
            try {
                OffsetAudioStream shifted = new OffsetAudioStream(stream);
                long targetTick = elapsedTicks;
                long frame = PlushieAnimation.musicFrame(targetTick);
                shifted.skipToFrame(frame);
                while (targetTick != elapsedTicks && !shifted.ended) {
                    long nextTick = elapsedTicks;
                    frame += Math.max(0, nextTick - targetTick) * PlushieAnimation.MUSIC_SAMPLE_RATE / 20;
                    shifted.skipToFrame(frame);
                    targetTick = nextTick;
                }
                return shifted;
            } catch (IOException | RuntimeException exception) {
                try {
                    stream.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
                throw new CompletionException(exception);
            }
        }, Util.ioPool());
    }

    private static final class OffsetAudioStream implements AudioStream {

        private final AudioStream source;
        private ByteBuffer pending;
        private long skippedBytes;
        private boolean ended;

        private OffsetAudioStream(AudioStream source) {
            this.source = source;
        }

        private void skipToFrame(long frame) throws IOException {
            int frameSize = getFormat().getFrameSize();
            if (frameSize <= 0) throw new IOException("Audio stream has no PCM frame size");
            long targetBytes = Math.max(0, frame) * frameSize;
            while (skippedBytes < targetBytes && !ended) {
                if (pending == null || !pending.hasRemaining()) {
                    pending = source.read((int) Math.min(16384, targetBytes - skippedBytes));
                    if (pending == null || !pending.hasRemaining()) {
                        ended = true;
                        break;
                    }
                }
                int skip = (int) Math.min(targetBytes - skippedBytes, pending.remaining());
                pending.position(pending.position() + skip);
                skippedBytes += skip;
            }
        }

        @Override
        public AudioFormat getFormat() {
            return source.getFormat();
        }

        @Override
        public ByteBuffer read(int count) throws IOException {
            if (pending != null && pending.hasRemaining()) {
                ByteBuffer result = pending;
                pending = null;
                return result;
            }
            return source.read(count);
        }

        @Override
        public void close() throws IOException {
            pending = null;
            source.close();
        }
    }
}
