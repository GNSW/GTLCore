/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Native mutable block-state storage created only for this adapter's replacement sections.
 * A proven single-state palette has the native disk representation of one entry and no data.
 * The proof is repeated under the native container lock on every pack; no state or stream is cached.
 */
public final class RegionBlockStateContainer extends PalettedContainer<BlockState> {
    public RegionBlockStateContainer(BlockState initialState) {
        super(Block.BLOCK_STATE_REGISTRY, Objects.requireNonNull(initialState), Strategy.SECTION_STATES);
    }

    @Override
    public PalettedContainerRO.PackedData<BlockState> pack(IdMap<BlockState> registry, Strategy strategy) {
        if (registry == Block.BLOCK_STATE_REGISTRY && strategy == Strategy.SECTION_STATES) {
            acquire();
            try {
                BlockState first = get(0, 0, 0);
                // A stale unused palette entry can only reject this proof. GlobalPalette also
                // rejects it conservatively. Neither case requires scanning the 4096 positions.
                if (!maybeHas(state -> state != first))
                    return new PalettedContainerRO.PackedData<>(List.of(first), Optional.empty());
            } finally {
                release();
            }
        }
        // ThreadingDetector is not reentrant: release before calling native pack, which locks itself.
        return super.pack(registry, strategy);
    }
}
