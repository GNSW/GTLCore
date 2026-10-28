package org.gtlcore.gtlcore.api.machine.trait.MEStock;

import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

public interface IMESlot {

    /** These stocking/configuration slots expose simulated ME stock rather than a paid local inventory. */
    default boolean isVirtualStock() {
        return true;
    }

    void setOnConfigChanged(Runnable onConfigChanged);

    Runnable getOnConfigChanged();

    void setConfigWithoutNotify(@Nullable GenericStack config);
}
