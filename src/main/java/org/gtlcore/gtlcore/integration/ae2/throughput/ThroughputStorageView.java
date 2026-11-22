package org.gtlcore.gtlcore.integration.ae2.throughput;

import appeng.api.storage.MEStorage;

import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;

public interface ThroughputStorageView {

    default Collection<MEStorage> gtlcore$getChildStorages() {
        return List.of();
    }

    default NavigableMap<Integer, List<MEStorage>> gtlcore$getPriorityStorages() {
        return null;
    }

    default long gtlcore$getTopologyVersion() {
        return 0L;
    }
}
