package org.gtlcore.gtlcore.client.audio;

import org.gtlcore.gtlcore.common.block.PlushieAnimation;

import net.minecraft.Util;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

import javax.sound.sampled.AudioFormat;

/** Opens the shared plushie stream and skips it to the synchronized music frame. */
final class PlushieAudioStream {

    private PlushieAudioStream() {}

    static CompletableFuture<AudioStream> openSeeked(SoundBufferLibrary buffers, Sound sound, LongSupplier elapsedTicks) {
        return buffers.getStream(sound.getPath(), true).thenApplyAsync(stream -> {
            try {
                OffsetAudioStream shifted = new OffsetAudioStream(stream);
                long targetTick = elapsedTicks.getAsLong();
                long frame = PlushieAnimation.musicFrame(targetTick);
                shifted.skipToFrame(frame);
                while (targetTick != elapsedTicks.getAsLong() && !shifted.ended) {
                    long nextTick = elapsedTicks.getAsLong();
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
