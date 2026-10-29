/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

import java.util.Objects;
import org.voxelregion.spatial.SectionKey;

/** Immutable, host-captured section data. Indices are {@code x | (z << 4) | (y << 8)}. */
public final class SectionSnapshot {
    private final SectionKey key;
    private final long revision;
    private final int airState;
    private final int[] states;
    private final int nonAirCount;
    private final int[] fluidStates;
    private final boolean hasFluids;

    /** Fluid metadata is unknown; the default clear-fluids policy needs none. */
    public SectionSnapshot(SectionKey key, long revision, int airState, int[] states) {
        this(key, revision, airState, states, null);
    }

    /**
     * Fluid targets use airState for no fluid, the cell's own state for a pure
     * fluid, and its retained fluid block state for a fluid-containing solid.
     * Both arrays are detached. A null fluid array means unknown, never dry.
     */
    public SectionSnapshot(SectionKey key, long revision, int airState, int[] states, int[] fluidStates) {
        this.key = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(states, "states");
        if (states.length != 4096) throw new IllegalArgumentException("A section has 4096 states");
        if (fluidStates != null && fluidStates.length != 4096) {
            throw new IllegalArgumentException("Fluid metadata must describe all 4096 cells");
        }
        this.revision = revision;
        this.airState = airState;
        this.states = states.clone();
        int count = 0;
        for (int state : this.states) if (state != airState) count++;
        this.nonAirCount = count;
        this.fluidStates = fluidStates == null ? null : fluidStates.clone();
        boolean foundFluid = false;
        if (this.fluidStates != null) {
            for (int i = 0; i < this.fluidStates.length; i++) {
                if (this.fluidStates[i] != airState) {
                    if (this.states[i] == airState) throw new IllegalArgumentException("An air cell cannot contain fluid");
                    foundFluid = true;
                }
            }
        }
        this.hasFluids = foundFluid;
    }

    public SectionKey key() { return key; }
    public long revision() { return revision; }
    public int airState() { return airState; }
    public int nonAirCount() { return nonAirCount; }
    public boolean isAllAir() { return nonAirCount == 0; }
    public int[] copyStates() { return states.clone(); }
    public boolean hasFluidData() { return fluidStates != null; }

    /** Constant-time proof from construction, rejected when metadata is unknown. */
    public boolean hasFluids() { requireFluidData(); return hasFluids; }
    public int[] copyFluidStates() { requireFluidData(); return fluidStates.clone(); }

    public int fluidState(int x, int y, int z) {
        requireFluidData();
        return fluidStates[index(x, y, z)];
    }

    public int fluidStateAtIndex(int index) { requireFluidData(); return fluidStates[index]; }

    private void requireFluidData() {
        if (fluidStates == null) throw new IllegalStateException("Fluid metadata was not captured");
    }

    public int state(int x, int y, int z) {
        return states[index(x, y, z)];
    }

    private static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) {
            throw new IndexOutOfBoundsException("Local coordinates must be in [0,16)");
        }
        return x | (z << 4) | (y << 8);
    }

    public int stateAtIndex(int index) { return states[index]; }
    /** Conservative retained-size accounting; does not replace host heap measurements. */
    public long estimatedBytes() {
        return 128L + 4096L * Integer.BYTES + (fluidStates == null ? 0 : 16L + 4096L * Integer.BYTES);
    }
}
