package org.gtlcore.gtlcore.client.bloom;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.RandomAccess;

/** Only filters the returned quads. Model data, face culling, tint and lighting use Forge's original renderer. */
final class EmissionModel extends BakedModelWrapper<BakedModel> {

    private final boolean fullBlock;
    @Nullable
    private final BlockAndTintGetter level;
    @Nullable
    private final BlockPos pos;
    @Nullable
    private final BlockState state;
    private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
    private int checkedSides, visibleSides;

    EmissionModel(BakedModel model, boolean fullBlock) {
        this(model, fullBlock, null, null, null);
    }

    /** One tessellation only: the mask must not outlive this position's current neighbours. */
    EmissionModel(BakedModel model, boolean fullBlock, @Nullable BlockAndTintGetter level,
                  @Nullable BlockPos pos, @Nullable BlockState state) {
        super(model);
        this.fullBlock = fullBlock;
        this.level = level;
        this.pos = pos;
        this.state = state;
    }

    private boolean visible(@Nullable Direction side) {
        if (side == null || level == null || pos == null || state == null) return true;
        int bit = 1 << side.ordinal();
        if ((checkedSides & bit) == 0) {
            checkedSides |= bit;
            if (Block.shouldRenderFace(state, level, pos, side, neighbour.setWithOffset(pos, side)))
                visibleSides |= bit;
        }
        return (visibleSides & bit) != 0;
    }

    private List<BakedQuad> filter(List<BakedQuad> input, @Nullable RenderType layer) {
        // The shader policy is global, not per face. Full-block and all-emissive batches can
        // retain the delegate's immutable-or-borrowed list; we never mutate it or cache dynamic data.
        if (ShaderEmissionBridge.suppressesLocalBloom() || input.isEmpty()) return List.of();
        if (fullBlock) return input;
        // Custom models may return linked/sequential lists. Preserve linear complexity for them.
        if (!(input instanceof RandomAccess)) {
            List<BakedQuad> result = null;
            for (BakedQuad quad : input) if (BloomMetadata.matches(quad)) {
                if (result == null) result = new ArrayList<>();
                result.add(quad);
            }
            return result == null ? List.of() : result;
        }
        int size = input.size();
        if (size == 1) return BloomMetadata.matches(input.get(0)) ? input : List.of();
        int firstRejected = 0;
        while (firstRejected < size && BloomMetadata.matches(input.get(firstRejected))) firstRejected++;
        if (firstRejected == size) return input;
        int suffix = firstRejected + 1;
        // If there is no accepted prefix, delay allocation until the first accepted suffix.
        if (firstRejected == 0) {
            while (suffix < size && !BloomMetadata.matches(input.get(suffix))) suffix++;
            if (suffix == size) return List.of();
        }
        List<BakedQuad> result = new ArrayList<>(size - 1);
        for (int prefix = 0; prefix < firstRejected; prefix++) result.add(input.get(prefix));
        if (firstRejected == 0) result.add(input.get(suffix++));
        for (int i = suffix; i < size; i++) {
            BakedQuad quad = input.get(i);
            if (BloomMetadata.matches(quad)) result.add(quad);
        }
        return result;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
        if (!visible(side)) return List.of();
        return filter(originalModel.getQuads(state, side, random), null);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random,
                                    ModelData data, @Nullable RenderType layer) {
        // Forge normally creates connected/dynamic quads before checking their cull face.
        // Move that same test ahead of model generation; unculled quads (side == null)
        // still run normally, and each render layer uses Forge's independently reset seed.
        if (!visible(side)) return List.of();
        return filter(originalModel.getQuads(state, side, random, data, layer), layer);
    }
}
