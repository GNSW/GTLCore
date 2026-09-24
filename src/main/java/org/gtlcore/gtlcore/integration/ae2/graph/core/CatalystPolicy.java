package org.gtlcore.gtlcore.integration.ae2.graph.core;

/** Captured before background planning; this value never reads a CPU or the world. */
public record CatalystPolicy(int parallelism, int maxExtraCopies) {

    public static final CatalystPolicy STOCK = new CatalystPolicy(4096, 0);
    public static final CatalystPolicy MINIMAL = new CatalystPolicy(1, 0);

    public CatalystPolicy {
        if (parallelism < 1 || parallelism > 4096) throw new IllegalArgumentException("Invalid catalyst parallelism");
        if (maxExtraCopies < 0 || maxExtraCopies > 4096) throw new IllegalArgumentException("Invalid extra catalyst limit");
    }
}
