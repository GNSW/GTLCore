/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;

/** Progress and mutation revision serialize in the same ForgeCaps image as terrain. */
final class ChunkReceipts implements ICapabilitySerializable<CompoundTag> {
    static final Capability<ChunkReceipts> CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {});
    static final byte STARTED = 1, COMPLETE = 2, SKIPPED_PERMISSION = 3, CANCELLED = 4;
    private static final int LIMIT = 1024;
    private static final int SCHEMA = 2;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final LazyOptional<ChunkReceipts> self = LazyOptional.of(() -> this);
    private final LevelChunk owner;
    private long mutationRevision;
    private boolean trackingValid = true;

    ChunkReceipts() { this(null); }
    private ChunkReceipts(LevelChunk owner) { this.owner = owner; }

    static void attach(AttachCapabilitiesEvent<LevelChunk> event) {
        if (!(event.getObject().getLevel() instanceof net.minecraft.server.level.ServerLevel)) return;
        ChunkReceipts data = new ChunkReceipts(event.getObject());
        event.addCapability(new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "receipts"), data);
        event.addListener(data.self::invalidate);
    }

    static ChunkReceipts of(LevelChunk chunk) {
        return chunk.getCapability(CAPABILITY).orElseThrow(() -> new IllegalStateException("Chunk receipt capability missing"));
    }

    /** A normalized next cell; maxSectionY,0 denotes the end of a chunk phase. */
    record Cursor(int sectionY, int voxel) {
        Cursor {
            if (voxel == 4096) { sectionY = Math.addExact(sectionY, 1); voxel = 0; }
            if (voxel < 0 || voxel >= 4096) throw new IllegalArgumentException("Invalid receipt voxel cursor");
        }
    }

    /** Detached audit/save-fence token. Sequence changes on every progress transition. */
    record Checkpoint(byte state, String intentHash, long completedThroughPhase, long workingPhase,
                      Cursor cursor, boolean pendingWrite, Cursor pendingCell,
                      long expectedMutationRevision, long sequence, int minSectionY, int maxSectionY) { }

    byte state(UUID operation) {
        Entry entry = entries.get(operation);
        return entry == null ? 0 : entry.state;
    }

    /** Compatibility API: STARTED intentionally has no safe replay cursor. */
    void mark(UUID operation, byte state) {
        if (state != STARTED && state != COMPLETE) throw new IllegalArgumentException("Invalid legacy receipt state");
        Entry entry = entries.get(operation);
        if (entry != null && terminal(entry.state) && entry.state != state)
            throw new IllegalStateException("A terminal operation receipt cannot move backwards");
        if (entry == null) entry = insert(operation);
        entry.state = state;
        touch(entry);
    }

    long mutationRevision() { return mutationRevision; }
    boolean trackingValid() { return trackingValid; }

    void changed() {
        if (mutationRevision == Long.MAX_VALUE) trackingValid = false;
        else mutationRevision++;
        dirty();
    }

    void invalidateTracking() { trackingValid = false; dirty(); }

    /**
     * A running, same-process phase may plan against edits made before its next step.
     * Never call this to authorize restart/pause recovery: validate the old epoch first.
     */
    void adoptLiveChanges(UUID operation, long phase) {
        Entry entry = active(operation, phase);
        if (!trackingValid || entry.pendingWrite) throw new IllegalStateException("Cannot adopt changes during an uncertain write");
        if (entry.expectedRevision != mutationRevision) {
            entry.expectedRevision = mutationRevision;
            touch(entry);
        }
    }

    /** Advance a live wave into a disjoint later shell. Never used to authorize restart or paused writes. */
    Cursor beginLiveNextPhase(UUID operation, String hash, long phase, int minSectionY, int maxSectionY) {
        Entry entry = entries.get(operation);
        if (entry == null || entry.workingPhase >= 0 || entry.completedThroughPhase >= phase
                || entry.pendingWrite || terminal(entry.state) || !trackingValid || !hash.equals(entry.intentHash))
            throw new IllegalStateException("No completed live phase to advance");
        entry.expectedRevision = mutationRevision;
        return beginPhase(operation, hash, phase, minSectionY, maxSectionY);
    }

    Checkpoint checkpoint(UUID operation) {
        Entry entry = entries.get(operation);
        return entry == null || entry.intentHash == null ? null : entry.snapshot();
    }

    /**
     * Fresh work only, or a previously validated next phase. Calls are strictly
     * phase ordered; omitted earlier phases must be geometrically excluded or
     * durably excluded in the operation journal. Never use this to invent
     * recovery evidence for an unvisited chunk after a restart.
     */
    Cursor beginPhase(UUID operation, String intentHash, long phase, int minSectionY, int maxSectionY) {
        validateHash(intentHash);
        if (phase < 0 || phase == Long.MAX_VALUE || minSectionY >= maxSectionY)
            throw new IllegalArgumentException("Invalid phase or section bounds");
        if (!trackingValid) throw new IllegalStateException("Chunk mutation tracking is uncertain");
        Entry entry = entries.get(operation);
        if (entry == null) {
            entry = insert(operation);
            entry.intentHash = intentHash;
            entry.completedThroughPhase = phase - 1;
            entry.minSectionY = minSectionY;
            entry.maxSectionY = maxSectionY;
        } else {
            if (terminal(entry.state)) throw new IllegalStateException("Operation is terminal in this chunk");
            if (!intentHash.equals(entry.intentHash)) throw new IllegalStateException("Missing or mismatched detailed receipt");
            if (entry.minSectionY != minSectionY || entry.maxSectionY != maxSectionY)
                throw new IllegalStateException("Chunk build height changed");
            if (entry.pendingWrite || entry.expectedRevision != mutationRevision)
                throw new IllegalStateException("Chunk changed or has an uncertain native write");
            if (entry.workingPhase == phase) return entry.cursor;
            if (entry.workingPhase >= 0 || phase <= entry.completedThroughPhase)
                throw new IllegalStateException("Cannot bypass an unfinished phase or repeat a completed phase");
            // The caller has proved or durably excluded any intervening phases.
            entry.completedThroughPhase = phase - 1;
        }
        entry.state = STARTED;
        entry.workingPhase = phase;
        entry.cursor = new Cursor(minSectionY, 0);
        entry.expectedRevision = mutationRevision;
        touch(entry);
        return entry.cursor;
    }

    RecoveryValidation.Result resumeCursor(UUID operation, String intentHash, long phase) {
        return RecoveryValidation.check(this, operation, intentHash, phase);
    }

    /** Set before the native call so a reentrant save records uncertainty. */
    void beforeWrite(UUID operation, long phase, int sectionY, int voxel) {
        Entry entry = ready(operation, phase);
        Cursor cell = new Cursor(sectionY, voxel);
        if (!cell.equals(entry.cursor) || cell.sectionY >= entry.maxSectionY)
            throw new IllegalArgumentException("Write does not match the persisted next cell");
        entry.pendingWrite = true;
        entry.pendingCell = cell;
        touch(entry);
    }

    /** Called only after a successful native return and all bounded commit bookkeeping. */
    void afterWrite(UUID operation, long phase, int nextSectionY, int nextVoxel) {
        Entry entry = active(operation, phase);
        if (!trackingValid || !entry.pendingWrite) throw new IllegalStateException("No valid pending write");
        Cursor next = new Cursor(nextSectionY, nextVoxel);
        validateForward(entry, next, true);
        entry.cursor = next;
        entry.pendingWrite = false;
        entry.pendingCell = null;
        entry.expectedRevision = mutationRevision;
        touch(entry);
    }

    /** Only a live backend's explicit NOT_APPLIED result may cancel a prepared write. Never use after an exception/restart. */
    void abortUnappliedWrite(UUID operation, long phase) {
        Entry entry = active(operation, phase);
        if (!entry.pendingWrite || !trackingValid) throw new IllegalStateException("No provably unapplied pending write");
        entry.pendingWrite = false; entry.pendingCell = null;
        entry.expectedRevision = mutationRevision;
        touch(entry);
    }

    /** Air/protection/outside skips must also progress in the co-saved receipt. */
    void advanceCursor(UUID operation, long phase, int nextSectionY, int nextVoxel) {
        Entry entry = ready(operation, phase);
        Cursor next = new Cursor(nextSectionY, nextVoxel);
        validateForward(entry, next, false);
        entry.cursor = next;
        touch(entry);
    }

    void completePhase(UUID operation, long phase, boolean operationComplete) {
        Entry entry = ready(operation, phase);
        if (entry.cursor.sectionY != entry.maxSectionY || entry.cursor.voxel != 0)
            throw new IllegalStateException("Phase cursor has not reached the end");
        entry.completedThroughPhase = phase;
        entry.workingPhase = -1;
        if (operationComplete) entry.state = COMPLETE;
        touch(entry);
    }

    void skipPermission(UUID operation) { terminate(operation, SKIPPED_PERMISSION); }
    void cancel(UUID operation) { terminate(operation, CANCELLED); }

    private void terminate(UUID operation, byte outcome) {
        Entry entry = entries.get(operation);
        if (entry == null) entry = insert(operation);
        if (terminal(entry.state)) {
            if (entry.state != outcome) throw new IllegalStateException("Terminal receipt cannot change");
            return;
        }
        entry.state = outcome;
        touch(entry);
    }

    static boolean terminal(byte state) { return state == COMPLETE || state == SKIPPED_PERMISSION || state == CANCELLED; }

    private Entry insert(UUID operation) {
        Objects.requireNonNull(operation, "operation");
        if (entries.size() >= LIMIT) throw new IllegalStateException("Chunk receipt capacity reached; recovery evidence is retained");
        Entry entry = new Entry();
        entries.put(operation, entry);
        return entry;
    }

    private Entry active(UUID operation, long phase) {
        Entry entry = entries.get(operation);
        if (entry == null || entry.intentHash == null || terminal(entry.state) || entry.workingPhase != phase)
            throw new IllegalStateException("No active detailed receipt for this phase");
        return entry;
    }

    private Entry ready(UUID operation, long phase) {
        Entry entry = active(operation, phase);
        if (!trackingValid || entry.pendingWrite || entry.expectedRevision != mutationRevision)
            throw new IllegalStateException("Chunk changed or native write outcome is uncertain");
        return entry;
    }

    private static void validateForward(Entry entry, Cursor next, boolean strictly) {
        if (next.sectionY < entry.minSectionY || next.sectionY > entry.maxSectionY
                || (next.sectionY == entry.maxSectionY && next.voxel != 0))
            throw new IllegalArgumentException("Cursor outside build height");
        long oldIndex = (long) entry.cursor.sectionY * 4096 + entry.cursor.voxel;
        long nextIndex = (long) next.sectionY * 4096 + next.voxel;
        if (nextIndex < oldIndex || strictly && nextIndex == oldIndex)
            throw new IllegalArgumentException("Receipt cursor cannot move backwards or repeat a write");
    }

    private void touch(Entry entry) {
        if (entry.sequence == Long.MAX_VALUE) trackingValid = false;
        else entry.sequence++;
        dirty();
    }
    private void dirty() { if (owner != null) owner.setUnsaved(true); }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        return capability == CAPABILITY ? self.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("_schema", SCHEMA);
        tag.putLong("_mutationRevision", mutationRevision);
        tag.putBoolean("_trackingValid", trackingValid);
        CompoundTag details = new CompoundTag();
        entries.forEach((id, entry) -> {
            tag.putByte(id.toString(), entry.state);
            if (entry.intentHash != null) details.put(id.toString(), entry.save());
        });
        tag.put("_details", details);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        boolean modern = tag.contains("_schema");
        if (modern && (tag.getInt("_schema") != SCHEMA || !tag.contains("_mutationRevision", Tag.TAG_LONG)
                || !tag.contains("_trackingValid", Tag.TAG_BYTE) || !tag.contains("_details", Tag.TAG_COMPOUND)))
            throw new IllegalStateException("Invalid receipt schema");
        long restoredRevision = modern ? tag.getLong("_mutationRevision") : 0;
        if (restoredRevision < 0) throw new IllegalStateException("Invalid mutation revision");
        Map<UUID, Entry> restored = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            if (modern && (key.equals("_schema") || key.equals("_mutationRevision")
                    || key.equals("_trackingValid") || key.equals("_details"))) continue;
            if (restored.size() >= LIMIT || !tag.contains(key, Tag.TAG_BYTE))
                throw new IllegalStateException("Invalid or excessive region receipts");
            byte state = tag.getByte(key);
            if (state != STARTED && !terminal(state)) throw new IllegalStateException("Invalid region receipt state");
            Entry entry = new Entry();
            entry.state = state;
            try { restored.put(UUID.fromString(key), entry); }
            catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid region receipt UUID", ex); }
        }
        if (modern) {
            CompoundTag details = tag.getCompound("_details");
            if (details.getAllKeys().size() > restored.size()) throw new IllegalStateException("Orphan receipt progress");
            for (String key : details.getAllKeys()) {
                UUID id;
                try { id = UUID.fromString(key); }
                catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid progress UUID", ex); }
                Entry entry = restored.get(id);
                if (entry == null || !details.contains(key, Tag.TAG_COMPOUND)) throw new IllegalStateException("Orphan receipt progress");
                entry.load(details.getCompound(key), restoredRevision);
            }
        }
        // Assign only after full validation: corrupt input cannot erase previous evidence.
        entries.clear();
        entries.putAll(restored);
        mutationRevision = restoredRevision;
        // Legacy entries have no detailed cursor, so cannot resume. Tracking
        // starts now for explicitly new operations without trusting old work.
        trackingValid = !modern || tag.getBoolean("_trackingValid");
    }

    private static void validateHash(String hash) {
        if (hash == null || hash.isBlank() || hash.length() > 256) throw new IllegalArgumentException("Invalid immutable intent digest");
    }

    private static final class Entry {
        byte state = STARTED;
        String intentHash;
        long completedThroughPhase = -1, workingPhase = -1, expectedRevision, sequence;
        int minSectionY, maxSectionY;
        Cursor cursor, pendingCell;
        boolean pendingWrite;

        Checkpoint snapshot() {
            return new Checkpoint(state, intentHash, completedThroughPhase, workingPhase, cursor,
                    pendingWrite, pendingCell, expectedRevision, sequence, minSectionY, maxSectionY);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("intent", intentHash);
            tag.putLong("completed", completedThroughPhase);
            tag.putLong("phase", workingPhase);
            tag.putInt("minSection", minSectionY); tag.putInt("maxSection", maxSectionY);
            tag.putInt("section", cursor.sectionY); tag.putInt("voxel", cursor.voxel);
            tag.putLong("revision", expectedRevision); tag.putLong("sequence", sequence);
            tag.putBoolean("pending", pendingWrite);
            if (pendingWrite) {
                tag.putInt("pendingSection", pendingCell.sectionY); tag.putInt("pendingVoxel", pendingCell.voxel);
            }
            return tag;
        }

        void load(CompoundTag tag, long chunkRevision) {
            for (String key : new String[] { "completed", "phase", "revision", "sequence" })
                if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalStateException("Missing progress field: " + key);
            for (String key : new String[] { "minSection", "maxSection", "section", "voxel" })
                if (!tag.contains(key, Tag.TAG_INT)) throw new IllegalStateException("Missing cursor field: " + key);
            if (!tag.contains("pending", Tag.TAG_BYTE)) throw new IllegalStateException("Missing pending-write evidence");
            intentHash = tag.getString("intent"); validateHash(intentHash);
            completedThroughPhase = tag.getLong("completed"); workingPhase = tag.getLong("phase");
            minSectionY = tag.getInt("minSection"); maxSectionY = tag.getInt("maxSection");
            cursor = new Cursor(tag.getInt("section"), tag.getInt("voxel"));
            expectedRevision = tag.getLong("revision"); sequence = tag.getLong("sequence");
            pendingWrite = tag.getBoolean("pending");
            if (completedThroughPhase < -1 || workingPhase < -1 || workingPhase == Long.MAX_VALUE
                    || expectedRevision < 0 || expectedRevision > chunkRevision || sequence < 0 || minSectionY >= maxSectionY
                    || workingPhase >= 0 && workingPhase <= completedThroughPhase
                    || workingPhase == -1 && completedThroughPhase < 0)
                throw new IllegalStateException("Invalid persisted progress");
            validateForward(this, cursor, false);
            if (pendingWrite) {
                if (!tag.contains("pendingSection", Tag.TAG_INT) || !tag.contains("pendingVoxel", Tag.TAG_INT))
                    throw new IllegalStateException("Missing pending cursor");
                pendingCell = new Cursor(tag.getInt("pendingSection"), tag.getInt("pendingVoxel"));
                if (!pendingCell.equals(cursor) || cursor.sectionY >= maxSectionY || workingPhase < 0)
                    throw new IllegalStateException("Invalid pending cursor");
            }
            if (!terminal(state) && workingPhase == -1 && (cursor.sectionY != maxSectionY || cursor.voxel != 0))
                throw new IllegalStateException("Completed phase has an unfinished cursor");
        }
    }
}
