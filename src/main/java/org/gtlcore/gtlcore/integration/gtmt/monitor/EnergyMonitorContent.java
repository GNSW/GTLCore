package org.gtlcore.gtlcore.integration.gtmt.monitor;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLCreativeModeTabs;

import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.*;

public final class EnergyMonitorContent {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, GTLCore.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, GTLCore.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GTLCore.MOD_ID);
    public static final RegistryObject<EnergyMonitorBlock> BLOCK = BLOCKS.register("wireless_grid_energy_monitor", EnergyMonitorBlock::new);
    public static final RegistryObject<Item> ITEM = ITEMS.register("wireless_grid_energy_monitor", () -> new BlockItem(BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<EnergyMonitorBlockEntity>> ENTITY = ENTITIES.register("wireless_grid_energy_monitor", () -> BlockEntityType.Builder.of(EnergyMonitorBlockEntity::new, BLOCK.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(EnergyMonitorContent::creative);
    }

    private static void creative(BuildCreativeModeTabContentsEvent e) {
        if (e.getTab() == GTLCreativeModeTabs.GTL_CORE.get()) e.accept(ITEM.get());
    }
}
