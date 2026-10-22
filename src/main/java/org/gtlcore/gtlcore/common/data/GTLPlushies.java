package org.gtlcore.gtlcore.common.data;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.block.PlushieBlock;
import org.gtlcore.gtlcore.common.block.PlushieBlockEntity;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Blocks for the existing wearable items; the item IDs remain registered by GTLItems. */
public final class GTLPlushies {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, GTLCore.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GTLCore.MOD_ID);

    public static final RegistryObject<PlushieBlock> FISHBIG_TM = BLOCKS.register("fishbig_tm",
            () -> new PlushieBlock(Block.box(-1, 0, 2, 17, 21, 15)));
    public static final RegistryObject<PlushieBlock> PIG_PLUSHIE = BLOCKS.register("pig_plushie",
            () -> new PlushieBlock(Block.box(2, 0, 0.75, 14, 16.5, 15)));
    public static final RegistryObject<BlockEntityType<PlushieBlockEntity>> BLOCK_ENTITY = BLOCK_ENTITIES.register("plushie",
            () -> BlockEntityType.Builder.of(PlushieBlockEntity::new, FISHBIG_TM.get(), PIG_PLUSHIE.get()).build(null));

    private GTLPlushies() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
