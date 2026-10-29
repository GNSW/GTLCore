/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/** Exact 1.20.1 terrain identities whose native removal inherits BlockBehaviour.onRemove. */
final class TerrainRemovalProof {
    private static final Map<Block, Class<? extends Block>> TYPES = new IdentityHashMap<>();
    static {
        add(Block.class, Blocks.STONE, Blocks.GRANITE, Blocks.POLISHED_GRANITE, Blocks.DIORITE,
                Blocks.POLISHED_DIORITE, Blocks.ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DIRT,
                Blocks.COARSE_DIRT, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
                Blocks.END_STONE, Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE,
                Blocks.SMOOTH_SANDSTONE, Blocks.RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE,
                Blocks.CHISELED_RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE, Blocks.COBBLED_DEEPSLATE,
                Blocks.POLISHED_DEEPSLATE, Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
                Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.SMOOTH_BASALT,
                Blocks.BEDROCK, Blocks.CLAY, Blocks.TERRACOTTA, Blocks.SNOW_BLOCK,
                Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
                Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
                Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
                Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.BLACK_TERRACOTTA);
        add(DropExperienceBlock.class, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
                Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
                Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
                Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE,
                Blocks.DEEPSLATE_EMERALD_ORE, Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE);
        add(RotatedPillarBlock.class, Blocks.DEEPSLATE, Blocks.BASALT, Blocks.POLISHED_BASALT);
        add(GrassBlock.class, Blocks.GRASS_BLOCK);
        add(SnowyDirtBlock.class, Blocks.PODZOL);
        add(MyceliumBlock.class, Blocks.MYCELIUM);
        add(SnowLayerBlock.class, Blocks.SNOW);
        add(SandBlock.class, Blocks.SAND, Blocks.RED_SAND);
        add(GravelBlock.class, Blocks.GRAVEL);
        add(DirtPathBlock.class, Blocks.DIRT_PATH);
        add(NetherrackBlock.class, Blocks.NETHERRACK);
        add(RootedDirtBlock.class, Blocks.ROOTED_DIRT);
        add(RedStoneOreBlock.class, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
    }

    static boolean supports(BlockState state, ServerLevel level, BlockPos position) {
        if (state.hasBlockEntity() || PoiTypes.forState(state).isPresent() || state.getLightEmission(level, position) != 0)
            return false;
        if (state.is(Blocks.WATER) && state.getBlock().getClass() == LiquidBlock.class) {
            var fluid = state.getFluidState();
            return (fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER)
                    && state.getLightBlock(level, position) == 1;
        }
        if (!state.getFluidState().isEmpty()) return false;
        return TYPES.get(state.getBlock()) == state.getBlock().getClass() || HostIntegration.provesStaticRemoval(state);
    }

    /** Surface-only light repair is limited to the already-proven opaque/water removal family. */
    static boolean boundaryLighting(BlockState state, ServerLevel level, BlockPos position) {
        return state.is(Blocks.WATER) || state.getLightBlock(level, position) == 15
                && Block.isShapeFullBlock(state.getOcclusionShape(level, position));
    }

    private static void add(Class<? extends Block> type, Block... blocks) {
        for (Block block : blocks) TYPES.put(block, type);
    }
    private TerrainRemovalProof() {}
}
