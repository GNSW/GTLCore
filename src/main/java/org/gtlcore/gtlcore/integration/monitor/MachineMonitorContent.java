package org.gtlcore.gtlcore.integration.monitor;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLCreativeModeTabs;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.*;

public final class MachineMonitorContent {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, GTLCore.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, GTLCore.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GTLCore.MOD_ID);
    // Keep the panel ID so existing placed screens and inventories retain their registry entry.
    public static final RegistryObject<MachineMonitorBlock> SCREEN = BLOCKS.register("machine_monitor_panel", MachineMonitorBlock::new);
    public static final RegistryObject<Item> SCREEN_ITEM = ITEMS.register("machine_monitor_panel", () -> new BlockItem(SCREEN.get(), new Item.Properties()));
    public static final RegistryObject<Item> LINKER = ITEMS.register("machine_monitor_linker", MachineMonitorLinker::new);
    public static final RegistryObject<BlockEntityType<MachineMonitorBlockEntity>> ENTITY = ENTITIES.register("machine_monitor", () -> BlockEntityType.Builder.of(MachineMonitorBlockEntity::new, SCREEN.get()).build(null));

    private MachineMonitorContent() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(MachineMonitorContent::creative);
        MinecraftForge.EVENT_BUS.addListener(MachineMonitorContent::remapLegacyController);
        MachineMonitorRuntime.register();
        MachineMonitorPackets.register();
    }

    private static void creative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == GTLCreativeModeTabs.GTL_CORE.get()) {
            event.accept(SCREEN_ITEM.get());
            event.accept(LINKER.get());
        }
    }

    private static void remapLegacyController(MissingMappingsEvent event) {
        var legacyController = GTLCore.id("machine_monitor_controller");
        for (var mapping : event.getMappings(Registries.BLOCK, GTLCore.MOD_ID)) {
            if (mapping.getKey().equals(legacyController)) mapping.remap(SCREEN.get());
        }
        for (var mapping : event.getMappings(Registries.ITEM, GTLCore.MOD_ID)) {
            if (mapping.getKey().equals(legacyController)) mapping.remap(SCREEN_ITEM.get());
        }
    }
}
