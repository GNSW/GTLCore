package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.config.BloomOptions.Mode;

/** Routing and invalidation depend on the effective renderer, not merely the selected mode. */
record BloomPolicy(Mode mode, boolean shaders, boolean materialBridge) {

    boolean local() {
        return mode.local(shaders);
    }

    boolean pbr() {
        return mode.pbr() && shaders && materialBridge;
    }

    boolean refreshMaterials(BloomPolicy previous, boolean tuningChanged) {
        return pbr() != previous.pbr() || pbr() && tuningChanged;
    }

    boolean resetLocalPipeline(BloomPolicy previous) {
        return shaders != previous.shaders || previous.local() && !local();
    }
}
