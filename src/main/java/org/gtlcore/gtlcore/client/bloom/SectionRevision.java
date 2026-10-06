package org.gtlcore.gtlcore.client.bloom;

/** Changes only when a section's block-state storage changes, including bulk packet reads. */
public interface SectionRevision {

    long gtlcore$bloomRevision();
}
