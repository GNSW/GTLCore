package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

public record Source(SourceId id, SourceKind kind, MEStorage physical, long identityHash, long sourceEpoch) {}
