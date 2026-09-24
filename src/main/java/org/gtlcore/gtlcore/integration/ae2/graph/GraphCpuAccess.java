package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.stacks.AEKey;

public interface GraphCpuAccess {

    GraphCpuController gtlcore$graphController();

    void gtlcore$postGraphChange(AEKey key);
}
