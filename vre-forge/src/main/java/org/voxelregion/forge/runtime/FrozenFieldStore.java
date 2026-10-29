/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.voxelregion.forge.FrozenOcclusionCapture.SealedSnapshot;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;

/**
 * Immutable, detached occlusion evidence. This class performs file/data work only
 * and may run on a bounded worker. SHA-256 is checked before decompression; both
 * compressed bytes and decompressed NBT/accounted allocations are capped at 32 MiB.
 */
public final class FrozenFieldStore {
    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    public static final long MAX_SNAPSHOT_BYTES = 256L * 1024 * 1024;
    private static final Set<String> KEYS = Set.of("schema", "id", "intent", "center", "bounds", "mode",
            "macro", "maxBoxes", "maxBytes", "estimated", "policy", "boxes", "chunks", "revisions");

    private FrozenFieldStore() { }

    /**
     * Forces the complete temporary image before atomically publishing its name.
     * Publication uses a same-directory hard link, whose CREATE_NEW semantics
     * cannot overwrite another writer's evidence. Filesystems lacking atomic hard
     * links fail safely. The caller must persist the returned hash in its journal.
     */
    public static String write(Path directory, UUID id, String intentHash, SealedSnapshot snapshot) throws IOException {
        Objects.requireNonNull(directory); Objects.requireNonNull(id); Objects.requireNonNull(snapshot);
        String intent = checkedHash(intentHash);
        validate(snapshot);
        byte[] encoded = encode(id, intent, snapshot);
        String hash = hash(encoded);
        Files.createDirectories(directory);
        Path destination = file(directory, id);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            requireSame(destination, hash);
            return hash;
        }
        Path temporary = Files.createTempFile(directory, id + ".occlusion-", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(encoded);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try { Files.createLink(destination, temporary); }
            catch (FileAlreadyExistsException race) { requireSame(destination, hash); }
            catch (UnsupportedOperationException unsupported) {
                throw new IOException("Filesystem cannot atomically publish immutable occlusion evidence", unsupported);
            }
        } finally { Files.deleteIfExists(temporary); }
        return hash;
    }

    /** Verifies content hash and identity before returning a strictly validated detached snapshot. */
    public static SealedSnapshot read(Path directory, UUID id, String intentHash, String expectedSha) throws IOException {
        return read(directory, id, intentHash, expectedSha, MAX_SNAPSHOT_BYTES);
    }

    /** Checks the host reservation before constructing blocker lists/revision maps or compiling any index. */
    public static SealedSnapshot read(Path directory, UUID id, String intentHash, String expectedSha,
            long maxEstimatedBytes) throws IOException {
        Objects.requireNonNull(directory); Objects.requireNonNull(id);
        if (maxEstimatedBytes < 1 || maxEstimatedBytes > MAX_SNAPSHOT_BYTES)
            throw new IOException("Invalid host snapshot reservation");
        String intent = checkedHash(intentHash), expected = checkedHash(expectedSha);
        byte[] encoded = readBytes(file(directory, id));
        if (!hash(encoded).equals(expected)) throw new IOException("Occlusion content hash mismatch");
        try (DataInputStream input = new DataInputStream(new LimitedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(encoded)), MAX_FILE_BYTES))) {
            CompoundTag tag = NbtIo.read(input, new NbtAccounter(MAX_FILE_BYTES));
            if (input.read() != -1) throw new IOException("Trailing occlusion NBT data");
            if (!tag.getAllKeys().equals(KEYS)) throw new IOException("Unknown or missing occlusion fields");
            require(tag, "schema", Tag.TAG_INT); require(tag, "id", Tag.TAG_INT_ARRAY);
            require(tag, "intent", Tag.TAG_STRING); require(tag, "center", Tag.TAG_LONG_ARRAY);
            require(tag, "bounds", Tag.TAG_INT_ARRAY); require(tag, "mode", Tag.TAG_STRING);
            require(tag, "macro", Tag.TAG_INT); require(tag, "maxBoxes", Tag.TAG_INT);
            require(tag, "maxBytes", Tag.TAG_LONG); require(tag, "estimated", Tag.TAG_LONG);
            require(tag, "policy", Tag.TAG_LONG); require(tag, "boxes", Tag.TAG_INT_ARRAY);
            require(tag, "chunks", Tag.TAG_LONG_ARRAY); require(tag, "revisions", Tag.TAG_LONG_ARRAY);
            if (tag.getInt("schema") != 1 || !tag.hasUUID("id") || !tag.getUUID("id").equals(id)
                    || !intent.equals(tag.getString("intent"))) throw new IOException("Occlusion identity/schema mismatch");
            long[] center = tag.getLongArray("center");
            int[] bounds = tag.getIntArray("bounds"), boxes = tag.getIntArray("boxes");
            long[] chunks = tag.getLongArray("chunks"), revisions = tag.getLongArray("revisions");
            if (center.length != 3 || bounds.length != 6 || boxes.length % 6 != 0 || chunks.length != revisions.length)
                throw new IOException("Malformed occlusion array lengths");
            limits(tag.getInt("maxBoxes"), tag.getLong("maxBytes"), tag.getLong("estimated"), boxes.length / 6, chunks.length);
            if (tag.getLong("estimated") > maxEstimatedBytes)
                throw new IOException("Occlusion snapshot exceeds host memory reservation");
            ArrayList<IntBox> blockers = new ArrayList<>(boxes.length / 6);
            for (int i = 0; i < boxes.length; i += 6) blockers.add(box(boxes, i));
            Map<ChunkKey, Long> epochs = new LinkedHashMap<>();
            for (int i = 0; i < chunks.length; i++) {
                ChunkKey key = new ChunkKey((int) chunks[i], (int) (chunks[i] >>> 32));
                if (epochs.putIfAbsent(key, revisions[i]) != null) throw new IOException("Duplicate captured chunk");
            }
            SealedSnapshot snapshot = new SealedSnapshot(new Point3(center[0], center[1], center[2]), box(bounds, 0),
                    FrozenOcclusion.Mode.valueOf(tag.getString("mode")), blockers, epochs, tag.getLong("policy"),
                    tag.getInt("macro"), tag.getInt("maxBoxes"), tag.getLong("maxBytes"), tag.getLong("estimated"));
            validate(snapshot);
            return snapshot;
        } catch (RuntimeException invalid) { throw new IOException("Invalid or oversized occlusion data", invalid); }
    }

    private static byte[] encode(UUID id, String intent, SealedSnapshot snapshot) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", 1); tag.putUUID("id", id); tag.putString("intent", intent);
        tag.putLongArray("center", new long[] { snapshot.center().x2(), snapshot.center().y2(), snapshot.center().z2() });
        tag.putIntArray("bounds", coordinates(snapshot.bounds()));
        tag.putString("mode", snapshot.mode().name()); tag.putInt("macro", snapshot.macroCellSize());
        tag.putInt("maxBoxes", snapshot.maxBoxes()); tag.putLong("maxBytes", snapshot.maxBytes());
        tag.putLong("estimated", snapshot.estimatedBytes()); tag.putLong("policy", snapshot.policyRevision());
        int[] boxes = new int[Math.multiplyExact(snapshot.blockers().size(), 6)];
        for (int i = 0; i < snapshot.blockers().size(); i++)
            System.arraycopy(coordinates(snapshot.blockers().get(i)), 0, boxes, i * 6, 6);
        tag.putIntArray("boxes", boxes);
        var entries = new ArrayList<>(snapshot.revisions().entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<ChunkKey, Long> entry) -> entry.getKey().x())
                .thenComparingInt(entry -> entry.getKey().z()));
        long[] chunks = new long[entries.size()], revisions = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            ChunkKey key = entries.get(i).getKey();
            chunks[i] = (key.x() & 0xffffffffL) | (long) key.z() << 32;
            revisions[i] = entries.get(i).getValue();
        }
        tag.putLongArray("chunks", chunks); tag.putLongArray("revisions", revisions);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(new LimitedOutputStream(new GZIPOutputStream(
                new LimitedOutputStream(output, MAX_FILE_BYTES)), MAX_FILE_BYTES))) {
            NbtIo.write(tag, data);
        }
        return output.toByteArray();
    }

    private static void validate(SealedSnapshot snapshot) throws IOException {
        limits(snapshot.maxBoxes(), snapshot.maxBytes(), snapshot.estimatedBytes(), snapshot.blockers().size(), snapshot.revisions().size());
        if (snapshot.policyRevision() < 0 || snapshot.policyRevision() == Long.MAX_VALUE)
            throw new IOException("Invalid protection revision");
        for (var entry : snapshot.revisions().entrySet()) {
            if (entry.getValue() < 0 || entry.getValue() == Long.MAX_VALUE
                    || !entry.getKey().bounds(snapshot.bounds().minY(), snapshot.bounds().maxY()).intersects(snapshot.bounds()))
                throw new IOException("Captured revision outside bounds or untracked");
        }
        for (IntBox box : snapshot.blockers()) {
            if (box.isEmpty() || !snapshot.bounds().contains(box)) throw new IOException("Invalid blocker box");
        }
    }

    private static void limits(int maxBoxes, long maxBytes, long estimated, int boxes, int chunks) throws IOException {
        long minimum = 524_288L + 1_024L * boxes + 160L * chunks;
        if (maxBoxes < 1 || maxBoxes > MAX_SNAPSHOT_BYTES / 1024 || boxes > maxBoxes
                || maxBytes < 524_288 || maxBytes > MAX_SNAPSHOT_BYTES || estimated < minimum || estimated > maxBytes
                || 24L * boxes + 16L * chunks + 1024 > MAX_FILE_BYTES)
            throw new IOException("Occlusion snapshot exceeds validated memory/file budget");
    }

    private static void require(CompoundTag tag, String key, int type) throws IOException {
        if (!tag.contains(key, type)) throw new IOException("Wrong type for occlusion field " + key);
    }
    private static IntBox box(int[] values, int offset) {
        return new IntBox(values[offset], values[offset + 1], values[offset + 2], values[offset + 3], values[offset + 4], values[offset + 5]);
    }
    private static int[] coordinates(IntBox box) {
        return new int[] { box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ() };
    }
    private static Path file(Path directory, UUID id) { return directory.resolve(id + ".occlusion.nbt"); }
    private static String checkedHash(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IOException("Expected SHA-256 hash");
        return value.toLowerCase(java.util.Locale.ROOT);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void requireSame(Path path, String expected) throws IOException {
        if (!hash(readBytes(path)).equals(expected)) throw new IOException("Refusing to replace different occlusion evidence");
    }
    private static byte[] readBytes(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES)
            throw new IOException("Missing, nonregular or oversized occlusion file");
        try (InputStream input = new LimitedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), MAX_FILE_BYTES)) {
            return input.readAllBytes();
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && --remaining < 0) throw new IOException("Occlusion input limit exceeded");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw new IOException("Occlusion input limit exceeded");
            return count;
        }
    }
    private static final class LimitedOutputStream extends FilterOutputStream {
        private long remaining;
        LimitedOutputStream(OutputStream output, long limit) { super(output); remaining = limit; }
        @Override public void write(int value) throws IOException {
            if (--remaining < 0) throw new IOException("Occlusion output limit exceeded");
            out.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if ((remaining -= length) < 0) throw new IOException("Occlusion output limit exceeded");
            out.write(bytes, offset, length);
        }
    }
}
