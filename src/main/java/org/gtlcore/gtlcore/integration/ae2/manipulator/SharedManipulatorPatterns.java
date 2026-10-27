package org.gtlcore.gtlcore.integration.ae2.manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Persisted on the real provider logic; the target's physical pattern inventory stays empty. */
public interface SharedManipulatorPatterns {

    BlockPos gtlcore$manipulatorSource();

    Direction gtlcore$manipulatorSourceSide();

    void gtlcore$setManipulatorSource(BlockPos pos, Direction side);
}
