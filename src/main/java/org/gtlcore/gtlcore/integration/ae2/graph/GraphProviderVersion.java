package org.gtlcore.gtlcore.integration.ae2.graph;

/** Monotonic provider revision, including multiple edits inside the same server tick. */
public interface GraphProviderVersion {

    long gtlcore$providerGeneration();
}
