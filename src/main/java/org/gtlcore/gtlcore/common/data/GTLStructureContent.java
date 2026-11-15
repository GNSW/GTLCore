package org.gtlcore.gtlcore.common.data;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.block.StructurePlacerBlock;
import org.gtlcore.gtlcore.common.block.StructurePlacerBlockEntity;
import org.gtlcore.gtlcore.common.item.CoordinatePositioningCardItem;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterials;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 结构放置器内容：方块、物品、方块实体类型，以及结构材料组件的 9 个物品。 */
public final class GTLStructureContent {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, GTLCore.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, GTLCore.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GTLCore.MOD_ID);

    public static final RegistryObject<StructurePlacerBlock> STRUCTURE_PLACER = BLOCKS.register(
            "structure_placer", () -> new StructurePlacerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final RegistryObject<BlockItem> STRUCTURE_PLACER_ITEM = ITEMS.register(
            "structure_placer", () -> new BlockItem(STRUCTURE_PLACER.get(), new Item.Properties()));

    public static final RegistryObject<CoordinatePositioningCardItem> COORDINATE_POSITIONING_CARD = ITEMS.register(
            "coordinate_positioning_card", CoordinatePositioningCardItem::new);

    public static final RegistryObject<BlockEntityType<StructurePlacerBlockEntity>> STRUCTURE_PLACER_ENTITY = BLOCK_ENTITIES.register(
            "structure_placer", () -> BlockEntityType.Builder.of(
                    StructurePlacerBlockEntity::new, STRUCTURE_PLACER.get()).build(null));

    private GTLStructureContent() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        StructureMaterials.register(bus);
    }
}
