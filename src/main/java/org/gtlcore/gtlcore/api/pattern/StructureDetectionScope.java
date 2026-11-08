package org.gtlcore.gtlcore.api.pattern;

/**
 * Explicit detection scope: pins one structure check to the repetition count configured on the terminal.
 * <p>
 * Without an active scope the matcher keeps its regular selection (determined layouts, incremental search or the
 * legacy repetition search). Inside a scope the check mirrors
 * {@code AdvancedBlockPattern#getConfiguredRepetitions} so a detection reports the structure the terminal would build.
 */
public final class StructureDetectionScope implements AutoCloseable {

    private static final ThreadLocal<StructureDetectionScope> ACTIVE = new ThreadLocal<>();

    private final StructureDetectionScope previous;
    private final Object state;
    private final int repeatCount;

    private StructureDetectionScope(Object state, int repeatCount) {
        this.state = state;
        this.repeatCount = repeatCount;
        this.previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    public static StructureDetectionScope begin(Object state, int repeatCount) {
        return new StructureDetectionScope(state, repeatCount);
    }

    /**
     * @return the repetition count to check, or null when the regular matcher selection must be used
     */
    public static int[] configuredRepetitions(Object state, int[][] ranges) {
        var scope = ACTIVE.get();
        if (scope == null || scope.state != state || scope.repeatCount <= 0) return null;
        if (ranges.length == 0) return null;
        int[] repetitions = new int[ranges.length];
        for (int aisle = 0; aisle < ranges.length; aisle++) {
            int[] range = ranges[aisle];
            if (range == null || range.length != 2 || range[0] < 0 || range[0] > range[1]) return null;
            repetitions[aisle] = range[0] == range[1] ? range[0] : Math.max(range[0], Math.min(range[1], scope.repeatCount));
        }
        return repetitions;
    }

    @Override
    public void close() {
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }
}
