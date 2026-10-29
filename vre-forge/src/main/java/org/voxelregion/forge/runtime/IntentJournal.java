/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Durable acceptance evidence; does not claim atomicity with entity/POI/world saves. */
final class IntentJournal {
    private static Path directory(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/voxelregion_test_intents");
    }
    static void append(ServerLevel level, OperationData.Task task) throws IOException {
        Path directory = directory(level);
        Files.createDirectories(directory);
        Path temporary = directory.resolve(task.id + ".pending");
        Path destination = directory.resolve(task.id + ".nbt");
        CompoundTag tag = task.save();
        tag.putString("dimension", level.dimension().location().toString());
        tag.putInt("schema", 1);
        tag.putString("adapter", "forge-1.20.1-47.3.7-region-0.1.0");
        tag.putString("intentHash", task.intentHash());
        NbtIo.writeCompressed(tag, temporary.toFile());
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
        // No destructive replacement: each operation owns a fresh UUID and immutable intent.
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Atomic phase/control checkpoint. Terrain authority remains the receipt in the native chunk image. */
    static void checkpoint(ServerLevel level, OperationData.Task task) {
        try {
            Path directory = directory(level);
            Files.createDirectories(directory);
            Path temporary = directory.resolve(task.id + ".checkpoint.pending");
            CompoundTag tag = task.save();
            tag.putString("dimension", level.dimension().location().toString());
            tag.putString("intentHash", task.intentHash());
            tag.putInt("schema", 2);
            NbtIo.writeCompressed(tag, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, directory.resolve(task.id + ".checkpoint.nbt"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) { throw new IllegalStateException("Operation checkpoint could not be made durable", ex); }
    }

    static void reconcile(ServerLevel level, OperationData data) {
        Path directory = directory(level);
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            boolean acceptanceOrderUnknown = false;
            for (Path path : files.filter(p -> p.getFileName().toString().matches("[0-9a-fA-F-]{36}\\.nbt")).toList()) {
                CompoundTag intent = BoundedNbtReader.read(path, BoundedNbtReader.MAX_BYTES);
                if (!intent.getString("dimension").equals(level.dimension().location().toString())) continue;
                UUID id = intent.getUUID("id");
                OperationData.Task accepted = OperationData.Task.load(intent);
                Path checkpoint = directory.resolve(id + ".checkpoint.nbt");
                if (Files.exists(checkpoint)) {
                    CompoundTag saved = BoundedNbtReader.read(checkpoint, BoundedNbtReader.MAX_BYTES);
                    OperationData.Task candidate = OperationData.Task.load(saved);
                    if (!candidate.id.equals(id) || !candidate.intentHash().equals(accepted.intentHash())
                            || !saved.getString("intentHash").equals(candidate.intentHash()))
                        throw new IOException("Checkpoint identity mismatch: " + id);
                    accepted = candidate;
                }
                OperationData.Task current = data.tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
                if (current != null && current.unconfirmedFences > 0 && !accepted.terminal()) {
                    current.state = OperationData.State.PAUSED_RECOVERY;
                    current.message = "Unconfirmed pipeline entries cannot be replaced by a progress-only journal cursor.";
                    continue;
                }
                if (current == null || accepted.terminal() || accepted.state == OperationData.State.PAUSED
                        || accepted.phase > current.phase) {
                    int originalIndex = current == null ? -1 : data.tasks.indexOf(current);
                    if (!accepted.terminal() && accepted.state != OperationData.State.PAUSED) {
                        accepted.state = accepted.unconfirmedFences == 0 ? OperationData.State.RECOVERING : OperationData.State.PAUSED_RECOVERY;
                        accepted.recovering = true;
                    }
                    if (!accepted.terminal() && accepted.unconfirmedFences > 0) {
                        accepted.state = OperationData.State.PAUSED_RECOVERY;
                        accepted.message = "Journal contains unconfirmed pipelined chunk writes; manual inspection required.";
                    }
                    if (current == null && !accepted.terminal()) {
                        acceptanceOrderUnknown = true;
                        accepted.state = OperationData.State.PAUSED_RECOVERY;
                        accepted.message = "Acceptance order is absent from SavedData; journal-only recovery requires manual inspection.";
                    }
                    accepted.chunkStarted = false;
                    if (originalIndex >= 0) data.tasks.set(originalIndex, accepted);
                    else data.tasks.add(accepted);
                }
            }
            if (acceptanceOrderUnknown) for (var task : data.tasks) if (!task.terminal()) {
                task.state = OperationData.State.PAUSED_RECOVERY;
                task.message = "A journal-only operation has unknown acceptance order; automatic overlap scheduling is paused.";
            }
        } catch (IOException | RuntimeException failure) {
            // An unreadable control journal must never revive a possibly cancelled operation.
            for (var task : data.tasks) if (!task.terminal()) {
                task.state = OperationData.State.PAUSED_RECOVERY;
                task.message = "Operation journal is unavailable or invalid; manual inspection required.";
            }
            RuntimeBridge.LOGGER.error("Region journal recovery stopped safely", failure);
        }
    }
    private IntentJournal() {}
}
