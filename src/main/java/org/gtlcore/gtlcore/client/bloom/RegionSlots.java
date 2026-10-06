package org.gtlcore.gtlcore.client.bloom;

import java.util.TreeMap;

/** Stable section allocations. Freed gaps coalesce; live sections never move. */
final class RegionSlots {

    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private final int limit;
    private int used;

    RegionSlots(int limit) {
        this.limit = limit;
    }

    int allocate(int bytes) {
        if (bytes <= 0 || bytes % 128 != 0) throw new IllegalArgumentException("Complete quads required");
        for (var gap : free.entrySet()) {
            if (gap.getValue() < bytes) continue;
            int offset = gap.getKey(), length = gap.getValue();
            free.remove(offset);
            if (length > bytes) free.put(offset + bytes, length - bytes);
            return offset;
        }
        if ((long) used + bytes > limit) return -1;
        int offset = used;
        used += bytes;
        return offset;
    }

    void release(int offset, int bytes) {
        var before = free.lowerEntry(offset);
        if (before != null && before.getKey() + before.getValue() == offset) {
            offset = before.getKey();
            bytes += before.getValue();
            free.remove(before.getKey());
        }
        Integer after = free.remove(offset + bytes);
        if (after != null) bytes += after;
        if (offset + bytes == used) used = offset;
        else free.put(offset, bytes);
    }

    int used() {
        return used;
    }
}
