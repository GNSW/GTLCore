package org.gtlcore.gtlcore.client.bloom;

import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/** Shared GPU pages for 4x4x4 sections; selection remains independent for every block. */
final class RegionMeshes {

    private static final int PAGE_BYTES = 1024 * 1024;
    private final Long2ObjectOpenHashMap<Group> groups = new Long2ObjectOpenHashMap<>();

    Slice upload(int x, int y, int z, ByteBuffer vertices) {
        long key = SectionPos.asLong(x >> 2, y >> 2, z >> 2);
        Group group = groups.computeIfAbsent(key, ignored -> new Group(x & ~3, y & ~3, z & ~3));
        int size = vertices.remaining();
        Page page = null;
        int offset = -1;
        for (Page candidate : group.pages) {
            offset = candidate.slots.allocate(size);
            if (offset >= 0) {
                page = candidate;
                break;
            }
        }
        if (page == null) {
            page = new Page(group, Math.max(PAGE_BYTES, size));
            group.pages.add(page);
            offset = page.slots.allocate(size);
        }
        try {
            page.ensureCapacity();
            Slice slice = new Slice(page, offset, size, (x & 3) * 16, (y & 3) * 16, (z & 3) * 16);
            slice.updateVertices(0, vertices);
            page.live++;
            return slice;
        } catch (RuntimeException | Error failure) {
            page.slots.release(offset, size);
            if (page.live == 0) remove(page);
            throw failure;
        }
    }

    void beginFrame() {
        for (Group group : groups.values()) for (Page page : group.pages) page.selected = 0;
    }

    int draw(GlProgram shader, Vec3 camera) {
        shader.integer("SectionOffsets", 1);
        int draws = 0;
        for (Group group : groups.values()) {
            boolean positioned = false;
            for (Page page : group.pages) {
                page.finishSelection();
                if (page.selected == 0) continue;
                if (!positioned) {
                    shader.vec3("ChunkOffset", (float) (group.x * 16.0 - camera.x),
                            (float) (group.y * 16.0 - camera.y), (float) (group.z * 16.0 - camera.z));
                    positioned = true;
                }
                page.mesh.drawRanges(page.counts, page.offsets, page.ranges, page.changed);
                page.changed = false;
                draws++;
            }
        }
        return draws;
    }

    long bytes() {
        long bytes = 0;
        for (Group group : groups.values()) for (Page page : group.pages) bytes += page.mesh.bytes();
        return bytes;
    }

    private void remove(Page page) {
        if (page.mesh != null) page.mesh.close();
        page.group.pages.remove(page);
        if (page.group.pages.isEmpty()) {
            Group group = page.group;
            groups.remove(SectionPos.asLong(group.x >> 2, group.y >> 2, group.z >> 2));
        }
    }

    final class Slice implements AutoCloseable {

        private final Page page;
        private final int start, capacity, x, y, z;
        private int[] counts;
        private long[] offsets;
        private int ranges;
        private long revision;
        private boolean closed;

        private Slice(Page page, int start, int capacity, int x, int y, int z) {
            this.page = page;
            this.start = start;
            this.capacity = capacity;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** The caller owns this upload scratch buffer; CPU model/template bytes are untouched. */
        void updateVertices(int offset, ByteBuffer vertices) {
            if (closed || offset < 0 || offset % 128 != 0 || vertices.remaining() % 128 != 0 ||
                    (long) offset + vertices.remaining() > capacity)
                throw new IllegalArgumentException("Vertex update outside section allocation");
            for (int i = vertices.position(); i < vertices.limit(); i += 32) {
                vertices.put(i + 28, (byte) x);
                vertices.put(i + 29, (byte) y);
                vertices.put(i + 30, (byte) z);
            }
            page.mesh.updateVertices(start + offset, vertices);
        }

        void select(int[] counts, long[] offsets, int ranges, boolean changed) {
            this.counts = counts;
            this.offsets = offsets;
            this.ranges = ranges;
            if (changed) revision++;
            int index = page.selected++;
            if (index == page.selection.length) {
                page.selection = Arrays.copyOf(page.selection, index * 2);
                page.revisions = Arrays.copyOf(page.revisions, index * 2);
            }
            if (page.selection[index] != this || page.revisions[index] != revision) page.changed = true;
            page.selection[index] = this;
            page.revisions[index] = revision;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            page.slots.release(start, capacity);
            if (--page.live == 0) remove(page);
        }
    }

    private static final class Group {

        final int x, y, z;
        final ArrayList<Page> pages = new ArrayList<>();

        Group(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Page {

        final Group group;
        final RegionSlots slots;
        RawMesh mesh;
        int capacity, live, selected, previousSelected, ranges;
        boolean changed = true;
        Slice[] selection = new Slice[8];
        long[] revisions = new long[8];
        int[] counts = new int[16];
        long[] offsets = new long[16];

        Page(Group group, int limit) {
            this.group = group;
            slots = new RegionSlots(limit);
        }

        void ensureCapacity() {
            if (slots.used() <= capacity) return;
            int nextCapacity = Math.max(4096, capacity);
            while (nextCapacity < slots.used()) nextCapacity = Math.multiplyExact(nextCapacity, 2);
            RawMesh next = RawMesh.allocateRegion(nextCapacity);
            try {
                if (mesh != null) mesh.copyVerticesTo(next, capacity);
            } catch (RuntimeException | Error failure) {
                next.close();
                throw failure;
            }
            if (mesh != null) mesh.close();
            mesh = next;
            capacity = nextCapacity;
            changed = true;
        }

        void finishSelection() {
            if (selected != previousSelected) changed = true;
            if (selected < previousSelected) Arrays.fill(selection, selected, previousSelected, null);
            previousSelected = selected;
            if (!changed) return;
            ranges = 0;
            for (int i = 0; i < selected; i++) {
                Slice slice = selection[i];
                long base = (long) slice.start / 128 * 6 * 4;
                for (int j = 0; j < slice.ranges; j++) {
                    int count = slice.counts[j];
                    long offset = base + slice.offsets[j];
                    if (ranges > 0 && offsets[ranges - 1] + (long) counts[ranges - 1] * 4 == offset) {
                        counts[ranges - 1] += count;
                    } else {
                        if (ranges == counts.length) {
                            counts = Arrays.copyOf(counts, ranges * 2);
                            offsets = Arrays.copyOf(offsets, ranges * 2);
                        }
                        counts[ranges] = count;
                        offsets[ranges++] = offset;
                    }
                }
            }
        }
    }
}
