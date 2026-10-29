// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.journal;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Small append-only operation journal with forced, checksummed records. Chunk data is
 * owned by the host and must carry receipts independently. Torn/truncated records fail
 * closed: this class never silently removes a damaged tail and retries a world write.
 * One process owns the directory. This is not a transaction manager for region files.
 */
public final class FileOperationJournal implements AutoCloseable {
    public enum State { PREPARED, ACTIVE, PAUSED, CANCELLED, COMPLETE;
        public boolean terminal() { return this == CANCELLED || this == COMPLETE; }
    }
    public record Record(OperationIntent intent, State state, long sequence, String reason) {}
    private static final int MAGIC = 0x56524531;
    private static final int MAX_RECORD = OperationIntent.MAX_PAYLOAD + 65536;
    private static final long MAX_FILE = 16L * 1024 * 1024;
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    public FileOperationJournal(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        lockChannel = FileChannel.open(this.root.resolve("journal.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException | IOException failure) { lockChannel.close(); throw failure; }
        if (acquired == null) { lockChannel.close(); throw new IOException("Journal already in use"); }
        lock = acquired;
    }
    private void open() { if (closed) throw new IllegalStateException("Journal closed"); }
    private Path file(UUID id) { return root.resolve(Objects.requireNonNull(id) + ".vre"); }

    /** Must return successfully before requesting any world mutation. Existing IDs cannot be reused. */
    public synchronized Record prepare(OperationIntent intent) throws IOException {
        open(); Objects.requireNonNull(intent);
        Record record = new Record(intent, State.PREPARED, 0, "");
        byte[] bytes = encode(record);
        try (FileChannel channel = FileChannel.open(file(intent.id()), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            write(channel, bytes); channel.force(true);
        }
        return record;
    }
    public synchronized Record read(UUID id) throws IOException {
        open(); Path path = file(id);
        long size = Files.size(path);
        if (size <= 0 || size > MAX_FILE) throw new IOException("Journal size invalid; recovery requires inspection: " + id);
        Record previous = null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            long consumed = 0;
            while (consumed < size) {
                int length = in.readInt(); consumed += 4;
                if (length <= 0 || length > MAX_RECORD || length > size - consumed - 8)
                    throw new IOException("Torn/invalid journal record: " + id);
                byte[] payload = in.readNBytes(length); long expected = in.readLong(); consumed += length + 8L;
                CRC32 crc = new CRC32(); crc.update(payload);
                if (expected != crc.getValue()) throw new IOException("Journal checksum mismatch: " + id);
                Record next = decode(payload);
                if (!next.intent.id().equals(id)) throw new IOException("Journal operation mismatch");
                if (previous == null) {
                    if (next.sequence != 0 || next.state != State.PREPARED) throw new IOException("Missing initial intent");
                } else {
                    if (next.sequence != previous.sequence + 1 || previous.state.terminal()
                            || next.state == State.PREPARED || !sameIntent(previous.intent, next.intent))
                        throw new IOException("Invalid journal transition");
                }
                previous = next;
            }
        } catch (EOFException failure) { throw new IOException("Truncated journal; do not replay: " + id, failure); }
        if (previous == null) throw new IOException("Missing operation intent");
        return previous;
    }
    public synchronized Record transition(UUID id, State state, String reason) throws IOException {
        open(); Objects.requireNonNull(state); Objects.requireNonNull(reason);
        Record previous = read(id);
        if (previous.state.terminal() || state == State.PREPARED)
            throw new IllegalStateException("Terminal or invalid transition");
        if (reason.length() > 4096) throw new IllegalArgumentException("Reason too long");
        Record next = new Record(previous.intent, state, Math.addExact(previous.sequence, 1), reason);
        byte[] bytes = encode(next);
        if (Files.size(file(id)) > MAX_FILE - bytes.length) throw new IOException("Journal full; pause operation");
        try (FileChannel channel = FileChannel.open(file(id), StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            write(channel, bytes); channel.force(true);
        }
        return next;
    }
    /** Enumerates IDs only; inspect each separately so one corrupt operation cannot hide others. */
    public synchronized List<UUID> operations() throws IOException {
        open(); List<UUID> ids = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.vre")) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                try { ids.add(UUID.fromString(name.substring(0, name.length() - 4))); }
                catch (IllegalArgumentException bad) { throw new IOException("Unexpected journal file: " + name, bad); }
            }
        }
        ids.sort(Comparator.naturalOrder()); return List.copyOf(ids);
    }
    private static boolean sameIntent(OperationIntent a, OperationIntent b) {
        return a.id().equals(b.id()) && a.dimension().equals(b.dimension()) && a.actor().equals(b.actor())
                && Arrays.equals(a.payload(), b.payload());
    }
    private static void write(FileChannel channel, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    private static byte[] encode(Record record) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeInt(MAGIC); out.writeLong(record.intent.id().getMostSignificantBits());
            out.writeLong(record.intent.id().getLeastSignificantBits()); out.writeUTF(record.intent.dimension());
            out.writeUTF(record.intent.actor()); byte[] parameters = record.intent.payload();
            out.writeInt(parameters.length); out.write(parameters); out.writeInt(record.state.ordinal());
            out.writeLong(record.sequence); out.writeUTF(record.reason);
        }
        byte[] bytes = payload.toByteArray(); CRC32 crc = new CRC32(); crc.update(bytes);
        ByteArrayOutputStream result = new ByteArrayOutputStream(bytes.length + 12);
        try (DataOutputStream out = new DataOutputStream(result)) {
            out.writeInt(bytes.length); out.write(bytes); out.writeLong(crc.getValue());
        }
        return result.toByteArray();
    }
    private static Record decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Unknown journal format");
            UUID id = new UUID(in.readLong(), in.readLong()); String dimension = in.readUTF(), actor = in.readUTF();
            int size = in.readInt();
            if (size < 0 || size > OperationIntent.MAX_PAYLOAD || size > in.available()) throw new IOException("Invalid parameters");
            byte[] parameters = in.readNBytes(size); int state = in.readInt(); long sequence = in.readLong();
            String reason = in.readUTF();
            if (state < 0 || state >= State.values().length || sequence < 0 || in.available() != 0)
                throw new IOException("Invalid state record");
            return new Record(new OperationIntent(id, dimension, actor, parameters), State.values()[state], sequence, reason);
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid operation metadata", failure); }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
