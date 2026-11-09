package org.gtlcore.gtlcore.integration.ae2.wireless;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLCreativeModeTabs;
import org.gtlcore.gtlcore.integration.ae2.chamber.MEChamberManagerTerminalMenu;
import org.gtlcore.gtlcore.integration.ae2.chamber.MEChamberManagerTerminalPart;
import org.gtlcore.gtlcore.integration.ae2.chamber.WirelessMEChamberManagerMenuHost;
import org.gtlcore.gtlcore.integration.ae2.chamber.WirelessMEChamberManagerTerminalItem;
import org.gtlcore.gtlcore.integration.ae2.emitter.EmitterManagerTerminalMenu;
import org.gtlcore.gtlcore.integration.ae2.emitter.EmitterManagerTerminalPart;
import org.gtlcore.gtlcore.integration.ae2.emitter.WirelessEmitterManagerMenuHost;
import org.gtlcore.gtlcore.integration.ae2.emitter.WirelessEmitterManagerTerminalItem;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadSelectionMenu;
import org.gtlcore.gtlcore.integration.ae2.patternrelay.PatternRelayItem;
import org.gtlcore.gtlcore.integration.ae2.patternrelay.PatternRelayPart;
import org.gtlcore.gtlcore.integration.ae2.tag.TagViewCellItem;
import org.gtlcore.gtlcore.integration.ae2.tag.TagViewCellMenu;
import org.gtlcore.gtlcore.integration.ae2.throughput.METhroughputMonitorPart;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputMonitorTerminalMenu;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputMonitorTerminalPart;
import org.gtlcore.gtlcore.integration.ae2.throughput.WirelessThroughputMonitorMenuHost;
import org.gtlcore.gtlcore.integration.ae2.throughput.WirelessThroughputMonitorTerminalItem;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

import appeng.api.features.GridLinkables;
import appeng.items.parts.PartItem;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.MenuOpener;
import de.mari_023.ae2wtlib.wut.WUTHandler;

public final class GTLWirelessAeContent {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, GTLCore.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, GTLCore.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GTLCore.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(ForgeRegistries.MENU_TYPES, GTLCore.MOD_ID);
    private static final MenuType<ThroughputMonitorTerminalMenu> WIRELESS_THROUGHPUT_MONITOR_TERMINAL_MENU_TYPE = IForgeMenuType.create(ThroughputMonitorTerminalMenu::createWirelessClientMenu);
    private static final MenuType<EmitterManagerTerminalMenu> WIRELESS_EMITTER_MANAGER_TERMINAL_MENU_TYPE = IForgeMenuType.create(EmitterManagerTerminalMenu::createWirelessClientMenu);
    private static final MenuType<MEChamberManagerTerminalMenu> WIRELESS_ME_CHAMBER_MANAGER_TERMINAL_MENU_TYPE = IForgeMenuType.create(MEChamberManagerTerminalMenu::createWirelessClientMenu);
    private static final MenuType<TagViewCellMenu> TAG_VIEW_CELL_MENU_TYPE = IForgeMenuType.create(TagViewCellMenu::createClientMenu);

    public static final RegistryObject<Block> WIRELESS_NETWORK_CORE = BLOCKS.register(
            "wireless_network_core",
            WirelessNetworkCoreBlock::new);

    public static final RegistryObject<Item> WIRELESS_NETWORK_CORE_ITEM = ITEMS.register(
            "wireless_network_core",
            () -> new BlockItem(WIRELESS_NETWORK_CORE.get(), new Item.Properties()));

    public static final RegistryObject<Block> WIRELESS_NETWORK_BOOKMARK = BLOCKS.register(
            "wireless_network_bookmark",
            WirelessNetworkBookmarkBlock::new);

    public static final RegistryObject<Item> WIRELESS_NETWORK_BOOKMARK_ITEM = ITEMS.register(
            "wireless_network_bookmark",
            () -> new BlockItem(WIRELESS_NETWORK_BOOKMARK.get(), new Item.Properties()));

    public static final RegistryObject<PatternRelayItem> PATTERN_RELAY = ITEMS.register(
            "me_pattern_relay",
            () -> new PatternRelayItem(new Item.Properties()));

    public static final RegistryObject<WirelessNetworkBindingToolItem> WIRELESS_NETWORK_BINDING_TOOL = ITEMS.register(
            "wireless_network_binding_tool",
            WirelessNetworkBindingToolItem::new);

    public static final RegistryObject<PartItem<METhroughputMonitorPart>> THROUGHPUT_MONITOR = ITEMS.register(
            "throughput_monitor",
            () -> new PartItem<>(
                    new Item.Properties(),
                    METhroughputMonitorPart.class,
                    METhroughputMonitorPart::new));

    public static final RegistryObject<Item> THROUGHPUT_MONITOR_CONFIGURATOR = ITEMS.register(
            "throughput_monitor_configurator",
            () -> new Item(new Item.Properties()));

    public static final RegistryObject<TagViewCellItem> TAG_VIEW_CELL = ITEMS.register(
            "tag_view_cell",
            TagViewCellItem::new);

    public static final RegistryObject<PartItem<ThroughputMonitorTerminalPart>> THROUGHPUT_MONITOR_TERMINAL = ITEMS.register(
            "throughput_monitor_terminal",
            () -> new PartItem<>(
                    new Item.Properties(),
                    ThroughputMonitorTerminalPart.class,
                    ThroughputMonitorTerminalPart::new));

    public static final RegistryObject<PartItem<EmitterManagerTerminalPart>> EMITTER_MANAGER_TERMINAL = ITEMS.register(
            "emitter_manager_terminal",
            () -> new PartItem<>(
                    new Item.Properties(),
                    EmitterManagerTerminalPart.class,
                    EmitterManagerTerminalPart::new));

    public static final RegistryObject<PartItem<MEChamberManagerTerminalPart>> ME_CHAMBER_MANAGER_TERMINAL = ITEMS.register(
            "me_chamber_manager_terminal",
            () -> new PartItem<>(
                    new Item.Properties(),
                    MEChamberManagerTerminalPart.class,
                    MEChamberManagerTerminalPart::new));

    public static final RegistryObject<WirelessThroughputMonitorTerminalItem> WIRELESS_THROUGHPUT_MONITOR_TERMINAL = ITEMS.register(
            "wireless_throughput_monitor_terminal",
            WirelessThroughputMonitorTerminalItem::new);

    public static final RegistryObject<WirelessEmitterManagerTerminalItem> WIRELESS_EMITTER_MANAGER_TERMINAL = ITEMS.register(
            "wireless_emitter_manager_terminal",
            WirelessEmitterManagerTerminalItem::new);

    public static final RegistryObject<WirelessMEChamberManagerTerminalItem> WIRELESS_ME_CHAMBER_MANAGER_TERMINAL = ITEMS.register(
            "wireless_me_chamber_manager_terminal",
            WirelessMEChamberManagerTerminalItem::new);

    public static final RegistryObject<BlockEntityType<WirelessNetworkCoreBlockEntity>> WIRELESS_NETWORK_CORE_BE = BLOCK_ENTITY_TYPES.register(
            "wireless_network_core",
            () -> BlockEntityType.Builder.of(
                    WirelessNetworkCoreBlockEntity::new,
                    WIRELESS_NETWORK_CORE.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<WirelessNetworkBookmarkBlockEntity>> WIRELESS_NETWORK_BOOKMARK_BE = BLOCK_ENTITY_TYPES.register(
            "wireless_network_bookmark",
            () -> BlockEntityType.Builder.of(
                    WirelessNetworkBookmarkBlockEntity::new,
                    WIRELESS_NETWORK_BOOKMARK.get())
                    .build(null));

    public static final RegistryObject<MenuType<WirelessNetworkCoreMenu>> WIRELESS_NETWORK_CORE_MENU = MENU_TYPES.register(
            "wireless_network_core",
            () -> IForgeMenuType.create(WirelessNetworkCoreMenu::new));

    public static final RegistryObject<MenuType<WirelessNetworkBookmarkMenu>> WIRELESS_NETWORK_BOOKMARK_MENU = MENU_TYPES.register(
            "wireless_network_bookmark",
            () -> IForgeMenuType.create(WirelessNetworkBookmarkMenu::new));

    public static final RegistryObject<MenuType<WirelessNetworkPickerMenu>> WIRELESS_NETWORK_PICKER_MENU = MENU_TYPES.register(
            "wireless_network_picker",
            () -> IForgeMenuType.create(WirelessNetworkPickerMenu::new));

    public static final RegistryObject<MenuType<WirelessAeTargetMenu>> WIRELESS_AE_TARGET_MENU = MENU_TYPES.register(
            "wireless_ae_target",
            () -> IForgeMenuType.create(WirelessAeTargetMenu::new));

    public static final RegistryObject<MenuType<PatternQuickUploadSelectionMenu>> PATTERN_QUICK_UPLOAD_SELECTION_MENU = MENU_TYPES.register(
            "pattern_quick_upload_selection",
            () -> IForgeMenuType.create(PatternQuickUploadSelectionMenu::new));

    public static final RegistryObject<MenuType<ThroughputMonitorTerminalMenu>> THROUGHPUT_MONITOR_TERMINAL_MENU = MENU_TYPES.register(
            "throughput_monitor_terminal",
            () -> IForgeMenuType.create(ThroughputMonitorTerminalMenu::createWiredClientMenu));

    public static final RegistryObject<MenuType<ThroughputMonitorTerminalMenu>> WIRELESS_THROUGHPUT_MONITOR_TERMINAL_MENU = MENU_TYPES.register(
            "wireless_throughput_monitor_terminal",
            () -> WIRELESS_THROUGHPUT_MONITOR_TERMINAL_MENU_TYPE);

    public static final RegistryObject<MenuType<EmitterManagerTerminalMenu>> EMITTER_MANAGER_TERMINAL_MENU = MENU_TYPES.register(
            "emitter_manager_terminal",
            () -> IForgeMenuType.create(EmitterManagerTerminalMenu::createWiredClientMenu));

    public static final RegistryObject<MenuType<EmitterManagerTerminalMenu>> WIRELESS_EMITTER_MANAGER_TERMINAL_MENU = MENU_TYPES.register(
            "wireless_emitter_manager_terminal",
            () -> WIRELESS_EMITTER_MANAGER_TERMINAL_MENU_TYPE);

    public static final RegistryObject<MenuType<MEChamberManagerTerminalMenu>> ME_CHAMBER_MANAGER_TERMINAL_MENU = MENU_TYPES.register(
            "me_chamber_manager_terminal",
            () -> IForgeMenuType.create(MEChamberManagerTerminalMenu::createWiredClientMenu));

    public static final RegistryObject<MenuType<MEChamberManagerTerminalMenu>> WIRELESS_ME_CHAMBER_MANAGER_TERMINAL_MENU = MENU_TYPES.register(
            "wireless_me_chamber_manager_terminal",
            () -> WIRELESS_ME_CHAMBER_MANAGER_TERMINAL_MENU_TYPE);

    public static final RegistryObject<MenuType<TagViewCellMenu>> TAG_VIEW_CELL_MENU = MENU_TYPES.register(
            "tag_view_cell",
            () -> TAG_VIEW_CELL_MENU_TYPE);

    private GTLWirelessAeContent() {}

    public static void register(IEventBus modBus) {
        PatternRelayPart.registerModels();
        ThroughputMonitorTerminalPart.registerModels();
        EmitterManagerTerminalPart.registerModels();
        MEChamberManagerTerminalPart.registerModels();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        MENU_TYPES.register(modBus);
        modBus.addListener(GTLWirelessAeContent::onRegisterEvent);
        modBus.addListener(GTLWirelessAeContent::addCreativeTabItems);
    }

    private static void onRegisterEvent(RegisterEvent event) {
        if (!event.getRegistryKey().equals(ForgeRegistries.ITEMS.getRegistryKey())) {
            return;
        }

        WirelessThroughputMonitorTerminalItem throughputItem = WIRELESS_THROUGHPUT_MONITOR_TERMINAL.get();
        GridLinkables.register(throughputItem, WirelessTerminalItem.LINKABLE_HANDLER);
        MenuOpener.addOpener(
                WIRELESS_THROUGHPUT_MONITOR_TERMINAL_MENU_TYPE,
                ThroughputMonitorTerminalMenu::openWireless);
        WirelessEmitterManagerTerminalItem emitterItem = WIRELESS_EMITTER_MANAGER_TERMINAL.get();
        GridLinkables.register(emitterItem, WirelessTerminalItem.LINKABLE_HANDLER);
        MenuOpener.addOpener(
                WIRELESS_EMITTER_MANAGER_TERMINAL_MENU_TYPE,
                EmitterManagerTerminalMenu::openWireless);
        WirelessMEChamberManagerTerminalItem chamberItem = WIRELESS_ME_CHAMBER_MANAGER_TERMINAL.get();
        GridLinkables.register(chamberItem, WirelessTerminalItem.LINKABLE_HANDLER);
        MenuOpener.addOpener(
                WIRELESS_ME_CHAMBER_MANAGER_TERMINAL_MENU_TYPE,
                MEChamberManagerTerminalMenu::openWireless);
        MenuOpener.addOpener(TAG_VIEW_CELL_MENU_TYPE, TagViewCellMenu::open);
        WUTHandler.addTerminal(
                WirelessThroughputMonitorTerminalItem.TERMINAL_NAME,
                throughputItem::tryOpen,
                WirelessThroughputMonitorMenuHost::new,
                WIRELESS_THROUGHPUT_MONITOR_TERMINAL_MENU_TYPE,
                throughputItem,
                WirelessThroughputMonitorTerminalItem.HOTKEY_NAME,
                throughputItem.getDescriptionId());
        WUTHandler.addTerminal(
                WirelessEmitterManagerTerminalItem.TERMINAL_NAME,
                emitterItem::tryOpen,
                WirelessEmitterManagerMenuHost::new,
                WIRELESS_EMITTER_MANAGER_TERMINAL_MENU_TYPE,
                emitterItem,
                WirelessEmitterManagerTerminalItem.HOTKEY_NAME,
                emitterItem.getDescriptionId());
        WUTHandler.addTerminal(
                WirelessMEChamberManagerTerminalItem.TERMINAL_NAME,
                chamberItem::tryOpen,
                WirelessMEChamberManagerMenuHost::new,
                WIRELESS_ME_CHAMBER_MANAGER_TERMINAL_MENU_TYPE,
                chamberItem,
                WirelessMEChamberManagerTerminalItem.HOTKEY_NAME,
                chamberItem.getDescriptionId());
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (CreativeModeTabs.FUNCTIONAL_BLOCKS.equals(event.getTabKey()) || GTLCreativeModeTabs.GTL_CORE.getKey().equals(event.getTabKey())) {
            event.accept(WIRELESS_NETWORK_CORE_ITEM);
            event.accept(WIRELESS_NETWORK_BOOKMARK_ITEM);
            event.accept(WIRELESS_NETWORK_BINDING_TOOL);
            event.accept(PATTERN_RELAY);
            event.accept(THROUGHPUT_MONITOR);
            event.accept(THROUGHPUT_MONITOR_CONFIGURATOR);
            event.accept(TAG_VIEW_CELL);
            event.accept(THROUGHPUT_MONITOR_TERMINAL);
            event.accept(EMITTER_MANAGER_TERMINAL);
            event.accept(ME_CHAMBER_MANAGER_TERMINAL);
            event.accept(WIRELESS_THROUGHPUT_MONITOR_TERMINAL);
            event.accept(WIRELESS_EMITTER_MANAGER_TERMINAL);
            event.accept(WIRELESS_ME_CHAMBER_MANAGER_TERMINAL);
        }
    }
}
