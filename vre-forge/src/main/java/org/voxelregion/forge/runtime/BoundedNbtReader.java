/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Bounded journal decoding; byte/allocation estimates are not a JVM heap measurement. */
final class BoundedNbtReader {
    static final long MAX_BYTES = 32L * 1024 * 1024;

    /** Applies the same ceiling independently to compressed, inflated and NBT-accounted data. */
    static CompoundTag read(Path path, long limit) throws IOException {
        if (limit < 1 || limit > MAX_BYTES) throw new IOException("Invalid journal read limit");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("Missing, nonregular or oversized journal file");
        try (InputStream file = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
             DataInputStream input = new DataInputStream(new LimitedInputStream(new GZIPInputStream(
                     new LimitedInputStream(file, limit)), limit))) {
            CompoundTag result = NbtIo.read(input, new NbtAccounter(limit));
            if (result == null || input.read() != -1) throw new IOException("Missing root or trailing journal NBT data");
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid or oversized journal NBT", invalid);
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && --remaining < 0) throw new IOException("Journal byte limit exceeded");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw new IOException("Journal byte limit exceeded");
            return count;
        }
    }
    private BoundedNbtReader() { }
}
