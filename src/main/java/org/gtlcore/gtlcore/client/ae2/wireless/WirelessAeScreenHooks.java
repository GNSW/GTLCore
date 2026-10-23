package org.gtlcore.gtlcore.client.ae2.wireless;

import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeNetworkRuntime;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessNetworkBindingToolItem;
import org.gtlcore.gtlcore.integration.wildcard.WildcardPatternCompat;

import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;

import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class WirelessAeScreenHooks {

    private static final long RECENT_CLICK_MILLIS = 60000L;
    private static final long REQUEST_RETRY_MILLIS = 1000L;
    private static final long CLICK_REFRESH_DELAY_MILLIS = 250L;
    private static final int UI_REFRESH_TICKS = 20;
    private static int ticksUntilUiRefresh;
    private static final int FANCY_PAGE_WIDTH = 168;
    private static final int FANCY_PAGE_HEIGHT = 158;
    private static final int PANEL_MARGIN = 6;
    private static final int FANCY_HEADER_HEIGHT = 24;
    private static final int NETWORK_ROW_HEIGHT = 24;
    private static final int FANCY_SCROLLBAR_WIDTH = 6;
    private static final int FANCY_SCROLLBAR_GAP = 4;
    private static final IGuiTexture WIRELESS_TAB_ICON = WirelessAeScreenHooks::drawWirelessTabIcon;
    private static final ResourceLocation WIRELESS_TAB_TEXTURE = new ResourceLocation("gtlcore", "textures/gui/wireless/tab_wireless_selected.png");
    private static final String WILDCARD_HELD_ITEM_UI_CLASS = "org.leodreamer.wildcard_pattern.wildcard.gui.WildcardHeldItemUI";
    private static final String WILDCARD_FANCY_PROVIDER_CLASS = "org.leodreamer.wildcard_pattern.wildcard.gui.WildcardFancyUIProvider";

    private static final String[] POSITION_METHODS = {
            "getBlockPos",
            "getPos",
            "pos"
    };
    private static final String[] TARGET_METHODS = {
            "getBlockEntity",
            "getTarget",
            "getModularUI",
            "getModularUIContainer",
            "getHolder",
            "getLocator",
            "getHost",
            "getPartHost",
            "getMenuHost",
            "getLogic",
            "getMachine",
            "getMetaMachine"
    };
    private static final String[] TARGET_FIELDS = {
            "modularUI",
            "holder",
            "blockEntity",
            "target",
            "host",
            "partHost",
            "locator",
            "logic",
            "machine",
            "metaMachine"
    };

    private static BlockPos recentClickedPos;
    private static Direction recentClickedSide;
    private static Vec3 recentClickedLocation;
    private static long recentClickedAtMillis;
    private static BlockPos pendingBindingToolPos;
    private static ModularUIGuiContainer bindingToolScreen;
    private static final Map<ReflectionKey, Optional<Method>> METHOD_CACHE = new HashMap<>();
    private static final Map<ReflectionKey, Optional<Field>> FIELD_CACHE = new HashMap<>();
    private static Screen cachedScreen;
    private static BlockPos cachedTargetPos;
    private static Screen cachedPositionScreen;
    private static AbstractContainerMenu cachedPositionMenu;
    private static List<BlockPos> cachedStaticTargetPositions = List.of();
    private static Screen embeddedScreen;
    private static BlockPos embeddedTargetPos;
    private static TargetHit embeddedTargetHit;
    private static List<WirelessAePackets.TargetNetworkEntry> embeddedEntries = List.of();
    private static WirelessAePackets.TargetNetworkEntry embeddedConnectedEntry;
    private static WirelessAePackets.TargetNetworkEntry embeddedDisconnectableEntry;
    private static boolean embeddedHasData;
    private static boolean embeddedLoading;
    private static int embeddedScrollOffset;
    private static boolean embeddedDraggingScrollbar;
    private static long embeddedRequestAtMillis;
    private static long embeddedRefreshAfterMillis;
    private static Screen fancyScreen;
    private static BlockPos fancyTargetPos;
    private static IFancyUIProvider fancyMainPage;
    private static WirelessAeFancyPageProvider fancyProvider;

    private WirelessAeScreenHooks() {}

    static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, WirelessAeScreenHooks::onRightClickBlock);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(WirelessAeScreenHooks::onLeftClickEmpty);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(WirelessAeScreenHooks::onScreenInit);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(WirelessAeScreenHooks::onClientTick);
    }

    private static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (event.getEntity().getMainHandItem().getItem() instanceof org.gtlcore.gtlcore.integration.ae2.wireless.WirelessNetworkBindingToolItem) {
            WirelessAePackets.CHANNEL.sendToServer(new WirelessAePackets.ClearBindingToolPacket());
        }
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        recentClickedPos = event.getPos();
        BlockHitResult hitResult = event.getHitVec();
        recentClickedSide = hitResult == null ? event.getFace() : hitResult.getDirection();
        recentClickedLocation = hitResult == null ? null : hitResult.getLocation();
        recentClickedAtMillis = System.currentTimeMillis();
        pendingBindingToolPos = event.getEntity().getOffhandItem().getItem() instanceof WirelessNetworkBindingToolItem &&
                WirelessAeNetworkRuntime.canOpenWirelessTargetMenu(event.getLevel(), event.getPos()) &&
                MetaMachine.getMachine(event.getLevel(), event.getPos()) instanceof IUIMachine ? event.getPos().immutable() : null;
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        installWirelessUiForScreen(event.getScreen());
        pendingBindingToolPos = null;
        ticksUntilUiRefresh = UI_REFRESH_TICKS;
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Screen screen = Minecraft.getInstance().screen;
        closeEmbeddedPanelIfScreenChanged(screen);
        if (!(screen instanceof ModularUIGuiContainer)) {
            ticksUntilUiRefresh = 0;
            return;
        }
        // Retry delayed widgets/target synchronization and repair rebuilt tabs outside rendering.
        if (--ticksUntilUiRefresh <= 0) {
            ticksUntilUiRefresh = UI_REFRESH_TICKS;
            installWirelessUiForScreen(screen);
        }
    }

    private static void installWirelessUiForScreen(Screen screen) {
        closeEmbeddedPanelIfScreenChanged(screen);
        if (!(screen instanceof ModularUIGuiContainer modularScreen)) {
            return;
        }

        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        BlockPos targetPos = findAcceptedTargetPos(screen, modularScreen, level);
        if (targetPos != null) {
            installNativeWirelessWidgets(modularScreen, targetPos);
        }
    }

    private static BlockPos findAcceptedTargetPos(Screen screen, AbstractContainerScreen<?> containerScreen,
                                                  Level level) {
        if (cachedScreen != screen) {
            cachedScreen = screen;
            cachedTargetPos = null;
            cachedPositionScreen = null;
            cachedPositionMenu = null;
            cachedStaticTargetPositions = List.of();
        }

        if (cachedTargetPos != null && shouldOfferWirelessTab(level, cachedTargetPos)) {
            return cachedTargetPos;
        }

        for (BlockPos candidate : findScreenTargetPositions(screen, containerScreen)) {
            if (shouldOfferWirelessTab(level, candidate)) {
                cachedTargetPos = candidate;
                return candidate;
            }
        }

        cachedTargetPos = null;
        return null;
    }

    private static List<BlockPos> findScreenTargetPositions(Screen screen, AbstractContainerScreen<?> containerScreen) {
        List<BlockPos> candidates = new ArrayList<>();
        addCandidate(candidates, getRecentClickedPos());
        addCandidate(candidates, getCrosshairBlockPos());

        AbstractContainerMenu menu = containerScreen.getMenu();
        for (BlockPos pos : getStaticTargetPositions(screen, menu)) {
            addCandidate(candidates, pos);
        }
        return candidates;
    }

    private static List<BlockPos> getStaticTargetPositions(Screen screen, AbstractContainerMenu menu) {
        if (cachedPositionScreen == screen && cachedPositionMenu == menu) {
            return cachedStaticTargetPositions;
        }

        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : findTargetPositions(menu)) {
            addCandidate(positions, pos);
        }
        for (BlockPos pos : findTargetPositions(screen)) {
            addCandidate(positions, pos);
        }
        cachedPositionScreen = screen;
        cachedPositionMenu = menu;
        cachedStaticTargetPositions = List.copyOf(positions);
        return cachedStaticTargetPositions;
    }

    private static void installNativeWirelessWidgets(ModularUIGuiContainer screen, BlockPos targetPos) {
        FancyMachineUIWidget fancy = findFancyMachineUI(screen);
        if (fancy == null) {
            return;
        }
        if (isWildcardPatternHeldItemUi(fancy)) {
            return;
        }

        WirelessAeFancyPageProvider provider = getOrCreateFancyProvider(screen, targetPos, fancy);
        ensureFancyPageList(fancy, provider);
        ensureWirelessSideTab(fancy.getSideTabsWidget(), provider);
        if (targetPos.equals(pendingBindingToolPos)) {
            pendingBindingToolPos = null;
            bindingToolScreen = screen;
            // Set the initial page during screen initialization, before its first render.
            fancy.getSideTabsWidget().getOnTabClick().accept(provider);
        }
    }

    private static boolean isWildcardPatternHeldItemUi(FancyMachineUIWidget fancy) {
        if (!WildcardPatternCompat.isLoaded()) {
            return false;
        }
        return hasTypeName(fancy, WILDCARD_HELD_ITEM_UI_CLASS) ||
                hasTypeName(fancy.getMainPage(), WILDCARD_FANCY_PROVIDER_CLASS);
    }

    private static boolean hasTypeName(Object target, String typeName) {
        if (target == null) {
            return false;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(typeName)) {
                return true;
            }
        }
        return false;
    }

    private static FancyMachineUIWidget findFancyMachineUI(ModularUIGuiContainer screen) {
        List<FancyMachineUIWidget> widgets = screen.modularUI.mainGroup.getWidgetsByType(FancyMachineUIWidget.class);
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private static WirelessAeFancyPageProvider getOrCreateFancyProvider(AbstractContainerScreen<?> screen,
                                                                        BlockPos targetPos,
                                                                        FancyMachineUIWidget fancy) {
        IFancyUIProvider mainPage = fancy.getMainPage();
        if (fancyScreen != screen || fancyProvider == null || fancyMainPage != mainPage || fancyTargetPos == null || !fancyTargetPos.equals(targetPos)) {
            fancyScreen = screen;
            fancyTargetPos = targetPos.immutable();
            fancyMainPage = mainPage;
            fancyProvider = new WirelessAeFancyPageProvider(
                    screen,
                    fancyTargetPos,
                    mainPage);
        } else {
            fancyProvider.updateTarget(screen, targetPos);
        }
        return fancyProvider;
    }

    private static void ensureFancyPageList(FancyMachineUIWidget fancy, WirelessAeFancyPageProvider provider) {
        List<IFancyUIProvider> pages = fancy.getAllPages();
        if (hasOnlyCurrentProvider(pages, provider)) return;
        boolean hasProvider = false;
        List<IFancyUIProvider> updated = new ArrayList<>(pages.size() + 1);
        for (IFancyUIProvider page : pages) {
            if (page instanceof WirelessAeFancyPageProvider) {
                if (page == provider && !hasProvider) {
                    updated.add(page);
                    hasProvider = true;
                }
            } else {
                updated.add(page);
            }
        }
        if (!hasProvider) {
            updated.add(provider);
        }
        if (!updated.equals(pages)) {
            writeField(fancy, List.copyOf(updated));
        }
    }

    private static void ensureWirelessSideTab(TabsWidget tabsWidget, WirelessAeFancyPageProvider provider) {
        List<IFancyUIProvider> subTabs = tabsWidget.getSubTabs();
        if (hasOnlyCurrentProvider(subTabs, provider)) return;
        subTabs.removeIf(tab -> tab instanceof WirelessAeFancyPageProvider);
        subTabs.add(provider);
    }

    private static boolean hasOnlyCurrentProvider(List<IFancyUIProvider> pages,
                                                  WirelessAeFancyPageProvider provider) {
        boolean found = false;
        for (IFancyUIProvider page : pages) {
            if (page instanceof WirelessAeFancyPageProvider) {
                if (page != provider || found) return false;
                found = true;
            }
        }
        return found;
    }

    private static boolean isEmbeddedPanelActive(Screen screen, BlockPos targetPos) {
        return embeddedScreen == screen && embeddedTargetPos != null && embeddedTargetPos.equals(targetPos);
    }

    private static void openEmbeddedPanel(Screen screen, BlockPos targetPos) {
        embeddedScreen = screen;
        embeddedTargetPos = targetPos.immutable();
        embeddedTargetHit = findTargetHit(embeddedTargetPos);
        embeddedEntries = List.of();
        embeddedConnectedEntry = null;
        embeddedDisconnectableEntry = null;
        embeddedHasData = false;
        embeddedLoading = false;
        embeddedScrollOffset = 0;
        embeddedDraggingScrollbar = false;
        embeddedRefreshAfterMillis = 0L;
        requestEmbeddedData(embeddedTargetPos);
    }

    private static void closeEmbeddedPanel() {
        embeddedScreen = null;
        embeddedTargetPos = null;
        embeddedTargetHit = null;
        embeddedEntries = List.of();
        embeddedConnectedEntry = null;
        embeddedDisconnectableEntry = null;
        embeddedHasData = false;
        embeddedLoading = false;
        embeddedScrollOffset = 0;
        embeddedDraggingScrollbar = false;
        embeddedRequestAtMillis = 0L;
        embeddedRefreshAfterMillis = 0L;
    }

    private static void closeEmbeddedPanelIfScreenChanged(Screen screen) {
        if (bindingToolScreen != screen) {
            bindingToolScreen = null;
        }
        if (cachedScreen != screen) {
            cachedScreen = null;
            cachedTargetPos = null;
            cachedPositionScreen = null;
            cachedPositionMenu = null;
            cachedStaticTargetPositions = List.of();
        }
        if (embeddedScreen != null && embeddedScreen != screen) {
            closeEmbeddedPanel();
        }
        if (fancyScreen != null && fancyScreen != screen) {
            fancyScreen = null;
            fancyTargetPos = null;
            fancyMainPage = null;
            fancyProvider = null;
        }
    }

    private static void ensureEmbeddedData(BlockPos targetPos) {
        long now = System.currentTimeMillis();
        if (embeddedRefreshAfterMillis > 0L && now >= embeddedRefreshAfterMillis) {
            embeddedRefreshAfterMillis = 0L;
            requestEmbeddedData(targetPos);
            return;
        }
        if (!embeddedHasData && (!embeddedLoading || now - embeddedRequestAtMillis > REQUEST_RETRY_MILLIS)) {
            requestEmbeddedData(targetPos);
        }
    }

    private static void requestEmbeddedData(BlockPos targetPos) {
        embeddedLoading = true;
        embeddedRequestAtMillis = System.currentTimeMillis();
        TargetHit hit = getEmbeddedTargetHit(targetPos);
        WirelessAePackets.CHANNEL.sendToServer(
                new WirelessAePackets.RequestTargetNetworksPacket(targetPos, hit.side(), hit.location()));
    }

    static void receiveTargetNetworks(BlockPos targetPos, List<WirelessAePackets.TargetNetworkEntry> entries) {
        Minecraft.getInstance().execute(() -> {
            if (embeddedTargetPos == null || !embeddedTargetPos.equals(targetPos)) {
                return;
            }
            embeddedEntries = List.copyOf(entries);
            updateEmbeddedEntryCache();
            embeddedHasData = true;
            embeddedLoading = false;
            embeddedRefreshAfterMillis = 0L;
        });
    }

    private static void drawWirelessPage(GuiGraphics graphics, int x, int y, int width, int height,
                                         int mouseX, int mouseY) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        WirelessAeStyle.drawInsetPanel(
                graphics,
                x + PANEL_MARGIN,
                y + FANCY_HEADER_HEIGHT,
                width - PANEL_MARGIN * 2,
                height - FANCY_HEADER_HEIGHT - PANEL_MARGIN);

        graphics.drawString(
                font,
                Component.translatable("screen.gtlcore.wireless_target"),
                x + 8,
                y + 8,
                WirelessAeStyle.TEXT,
                false);

        int contentX = x + PANEL_MARGIN + 6;
        int contentY = y + FANCY_HEADER_HEIGHT + 8;
        int contentWidth = width - (PANEL_MARGIN + 6) * 2;
        WirelessAePackets.TargetNetworkEntry connected = getConnectedEntry();
        WirelessAePackets.TargetNetworkEntry disconnectable = getDisconnectableEntry();

        WirelessAeStyle.drawStatusLight(graphics, contentX, contentY, connected != null);
        WirelessAeStyle.drawTrimmedString(
                graphics,
                font,
                connected == null ? Component.translatable("label.gtlcore.wireless_target.current_disconnected") : Component.translatable("label.gtlcore.wireless_target.current_connected", connected.name()),
                contentX + 14,
                contentY + 1,
                contentWidth - 14,
                connected == null ? WirelessAeStyle.MUTED_TEXT : WirelessAeStyle.ONLINE_TEXT);
        int listY = contentY + 18;

        if (!embeddedHasData) {
            WirelessAeStyle.drawTrimmedString(
                    graphics,
                    font,
                    Component.translatable("label.gtlcore.wireless_target.loading"),
                    contentX,
                    listY,
                    contentWidth,
                    WirelessAeStyle.MUTED_TEXT);
            return;
        }

        if (embeddedEntries.isEmpty()) {
            WirelessAeStyle.drawTrimmedString(
                    graphics,
                    font,
                    Component.translatable("label.gtlcore.wireless_target.no_networks"),
                    contentX,
                    listY,
                    contentWidth,
                    WirelessAeStyle.WARNING_TEXT);
            return;
        }

        int bottomReserve = disconnectable == null ? 8 : 36;
        int maxRows = Math.max(1, (y + height - bottomReserve - listY) / NETWORK_ROW_HEIGHT);
        embeddedScrollOffset = WirelessAeStyle.clampScrollOffset(embeddedScrollOffset, embeddedEntries.size(), maxRows);
        boolean hasScrollbar = WirelessAeStyle.needsScrollbar(embeddedEntries.size(), maxRows);
        int rowWidth = contentWidth - (hasScrollbar ? FANCY_SCROLLBAR_WIDTH + FANCY_SCROLLBAR_GAP : 0);
        int rows = Math.min(maxRows, Math.max(0, embeddedEntries.size() - embeddedScrollOffset));
        boolean lockedByCableConnection = isLockedByCableConnection();
        for (int i = 0; i < rows; i++) {
            WirelessAePackets.TargetNetworkEntry entry = embeddedEntries.get(embeddedScrollOffset + i);
            int rowY = listY + i * NETWORK_ROW_HEIGHT;
            boolean hovered = (bindingToolScreen == embeddedScreen || (!lockedByCableConnection && !entry.connected())) &&
                    isInsideRect(mouseX, mouseY, contentX, rowY, rowWidth, 20);
            drawNetworkRow(graphics, contentX, rowY, rowWidth, entry, hovered);
        }

        if (hasScrollbar) {
            WirelessAeStyle.drawScrollbar(
                    graphics,
                    contentX + contentWidth - FANCY_SCROLLBAR_WIDTH,
                    listY,
                    maxRows * NETWORK_ROW_HEIGHT - 4,
                    embeddedEntries.size(),
                    maxRows,
                    embeddedScrollOffset);
        }

        if (disconnectable != null) {
            int disconnectY = y + height - 30;
            WirelessAeStyle.drawSeparator(graphics, contentX, disconnectY - 5, contentWidth);
            boolean hovered = isInsideRect(mouseX, mouseY, contentX, disconnectY, contentWidth, 20);
            drawDisconnectRow(graphics, contentX, disconnectY, contentWidth, hovered);
        }
    }

    private static void drawNetworkRow(GuiGraphics graphics, int x, int y, int width,
                                       WirelessAePackets.TargetNetworkEntry entry, boolean hovered) {
        WirelessAeStyle.drawButtonBackground(graphics, x, y, width, 20,
                true, entry.connected(), false, hovered);

        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int textX = x + 10;
        int checkSpace = entry.connected() ? 24 : 0;
        WirelessAeStyle.drawTrimmedString(
                graphics,
                font,
                Component.literal(entry.name()),
                textX,
                y + 5,
                Math.max(8, width - 20 - checkSpace),
                WirelessAeStyle.TEXT);
    }

    private static void drawDisconnectRow(GuiGraphics graphics, int x, int y, int width, boolean hovered) {
        WirelessAeStyle.drawButtonBackground(graphics, x, y, width, 20,
                true, false, true, hovered);
        WirelessAeStyle.drawTrimmedString(
                graphics,
                Minecraft.getInstance().font,
                Component.translatable("button.gtlcore.wireless_target.disconnect"),
                x + 8,
                y + 5,
                width - 16,
                WirelessAeStyle.WARNING_TEXT);
    }

    private static void handleWirelessPageClick(BlockPos targetPos, int x, int y, int width, int height,
                                                double mouseX, double mouseY, int button) {
        if (!isInsideRect(mouseX, mouseY, x, y, width, height) || !embeddedHasData) {
            return;
        }

        int contentX = x + PANEL_MARGIN + 6;
        int contentY = y + FANCY_HEADER_HEIGHT + 8;
        int contentWidth = width - (PANEL_MARGIN + 6) * 2;
        int listY = contentY + 18;
        WirelessAePackets.TargetNetworkEntry connected = getConnectedEntry();
        WirelessAePackets.TargetNetworkEntry disconnectable = getDisconnectableEntry();
        int bottomReserve = disconnectable == null ? 8 : 36;
        int maxRows = Math.max(1, (y + height - bottomReserve - listY) / NETWORK_ROW_HEIGHT);
        embeddedScrollOffset = WirelessAeStyle.clampScrollOffset(embeddedScrollOffset, embeddedEntries.size(), maxRows);
        boolean hasScrollbar = WirelessAeStyle.needsScrollbar(embeddedEntries.size(), maxRows);
        if (button == 0 && hasScrollbar && isInsideRect(mouseX, mouseY, contentX + contentWidth - FANCY_SCROLLBAR_WIDTH, listY, FANCY_SCROLLBAR_WIDTH, maxRows * NETWORK_ROW_HEIGHT - 4)) {
            embeddedDraggingScrollbar = true;
            updateEmbeddedScrollOffsetFromMouse(mouseY, listY, maxRows * NETWORK_ROW_HEIGHT - 4, maxRows);
            return;
        }

        int rowWidth = contentWidth - (hasScrollbar ? FANCY_SCROLLBAR_WIDTH + FANCY_SCROLLBAR_GAP : 0);
        int rows = Math.min(maxRows, Math.max(0, embeddedEntries.size() - embeddedScrollOffset));
        boolean lockedByCableConnection = isLockedByCableConnection();
        for (int i = 0; i < rows; i++) {
            int rowY = listY + i * NETWORK_ROW_HEIGHT;
            if (isInsideRect(mouseX, mouseY, contentX, rowY, rowWidth, 20)) {
                if (button == 1) {
                    WirelessAePackets.CHANNEL.sendToServer(
                            new WirelessAePackets.SaveBindingToolNetworkPacket(
                                    bindingToolScreen.getMenu().containerId,
                                    embeddedEntries.get(embeddedScrollOffset + i).frequency()));
                    return;
                }
                if (lockedByCableConnection) {
                    return;
                }
                connectEmbeddedEntry(targetPos, embeddedEntries.get(embeddedScrollOffset + i));
                return;
            }
        }

        if (button == 0 && disconnectable != null) {
            int disconnectY = y + height - 30;
            if (isInsideRect(mouseX, mouseY, contentX, disconnectY, contentWidth, 20)) {
                disconnectEmbeddedEntry(targetPos, disconnectable);
            }
        }
    }

    private static List<Component> getWirelessPageTooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
        if (!embeddedHasData) {
            return List.of();
        }
        int contentX = x + PANEL_MARGIN + 6;
        int contentWidth = width - (PANEL_MARGIN + 6) * 2;
        int listY = y + FANCY_HEADER_HEIGHT + 8 + 18;
        WirelessAePackets.TargetNetworkEntry disconnectable = getDisconnectableEntry();
        int bottomReserve = disconnectable == null ? 8 : 36;
        int maxRows = Math.max(1, (y + height - bottomReserve - listY) / NETWORK_ROW_HEIGHT);
        boolean hasScrollbar = WirelessAeStyle.needsScrollbar(embeddedEntries.size(), maxRows);
        int rowWidth = contentWidth - (hasScrollbar ? FANCY_SCROLLBAR_WIDTH + FANCY_SCROLLBAR_GAP : 0);
        int rows = Math.min(maxRows, Math.max(0, embeddedEntries.size() - embeddedScrollOffset));
        for (int i = 0; i < rows; i++) {
            if (isInsideRect(mouseX, mouseY, contentX, listY + i * NETWORK_ROW_HEIGHT, rowWidth, 20)) {
                WirelessAePackets.TargetNetworkEntry entry = embeddedEntries.get(embeddedScrollOffset + i);
                List<Component> tooltip = new ArrayList<>();
                tooltip.add(Component.literal(entry.name()));
                String leftClickKey = entry.connected() ? "tooltip.gtlcore.wireless_target.already_connected" :
                        isLockedByCableConnection() ? "tooltip.gtlcore.wireless_target.connection_locked" :
                                "tooltip.gtlcore.wireless_target.connect";
                tooltip.add(Component.translatable(leftClickKey).withStyle(ChatFormatting.GRAY));
                if (bindingToolScreen == embeddedScreen) {
                    tooltip.add(Component.translatable("tooltip.gtlcore.wireless_target.save_to_tool").withStyle(ChatFormatting.AQUA));
                }
                return tooltip;
            }
        }
        return disconnectable != null && isInsideRect(mouseX, mouseY, contentX, y + height - 30, contentWidth, 20) ?
                List.of(Component.translatable("tooltip.gtlcore.wireless_target.disconnect")) : List.of();
    }

    private static boolean scrollEmbedded(double wheelDelta, int x, int y, int width, int height,
                                          double mouseX, double mouseY) {
        if (!embeddedHasData || embeddedEntries.isEmpty() || !isInsideRect(mouseX, mouseY, x, y, width, height)) {
            return false;
        }

        int listY = y + FANCY_HEADER_HEIGHT + 8 + 18;
        WirelessAePackets.TargetNetworkEntry disconnectable = getDisconnectableEntry();
        int bottomReserve = disconnectable == null ? 8 : 36;
        int visibleRows = Math.max(1, (y + height - bottomReserve - listY) / NETWORK_ROW_HEIGHT);
        int nextOffset = WirelessAeStyle.clampScrollOffset(
                embeddedScrollOffset - (int) Math.signum(wheelDelta),
                embeddedEntries.size(),
                visibleRows);
        if (nextOffset == embeddedScrollOffset) {
            return false;
        }
        embeddedScrollOffset = nextOffset;
        return true;
    }

    private static boolean updateEmbeddedScrollbarDrag(double mouseY, int x, int y, int width, int height) {
        if (!embeddedDraggingScrollbar) {
            return false;
        }
        int listY = y + FANCY_HEADER_HEIGHT + 8 + 18;
        WirelessAePackets.TargetNetworkEntry disconnectable = getDisconnectableEntry();
        int bottomReserve = disconnectable == null ? 8 : 36;
        int visibleRows = Math.max(1, (y + height - bottomReserve - listY) / NETWORK_ROW_HEIGHT);
        updateEmbeddedScrollOffsetFromMouse(mouseY, listY, visibleRows * NETWORK_ROW_HEIGHT - 4, visibleRows);
        return true;
    }

    private static void updateEmbeddedScrollOffsetFromMouse(double mouseY, int listY, int scrollbarHeight,
                                                            int visibleRows) {
        embeddedScrollOffset = WirelessAeStyle.scrollbarOffsetFromMouse(
                mouseY,
                listY,
                scrollbarHeight,
                embeddedEntries.size(),
                visibleRows);
    }

    private static void connectEmbeddedEntry(BlockPos targetPos, WirelessAePackets.TargetNetworkEntry entry) {
        if (!entry.connected()) {
            TargetHit hit = getEmbeddedTargetHit(targetPos);
            WirelessAePackets.CHANNEL.sendToServer(
                    new WirelessAePackets.ConnectTargetPacket(
                            targetPos,
                            hit.side(),
                            hit.location(),
                            entry.frequency(),
                            false));
            markEmbeddedConnection(entry.frequency());
            scheduleEmbeddedRefresh();
        }
    }

    private static void disconnectEmbeddedEntry(BlockPos targetPos, WirelessAePackets.TargetNetworkEntry entry) {
        TargetHit hit = getEmbeddedTargetHit(targetPos);
        WirelessAePackets.CHANNEL.sendToServer(
                new WirelessAePackets.ConnectTargetPacket(
                        targetPos,
                        hit.side(),
                        hit.location(),
                        entry.frequency(),
                        true));
        markEmbeddedConnection(null);
        scheduleEmbeddedRefresh();
    }

    private static void markEmbeddedConnection(java.util.UUID frequency) {
        List<WirelessAePackets.TargetNetworkEntry> updated = new ArrayList<>(embeddedEntries.size());
        for (WirelessAePackets.TargetNetworkEntry entry : embeddedEntries) {
            updated.add(new WirelessAePackets.TargetNetworkEntry(
                    entry.frequency(),
                    entry.name(),
                    entry.frequency().equals(frequency),
                    entry.frequency().equals(frequency)));
        }
        embeddedEntries = List.copyOf(updated);
        updateEmbeddedEntryCache();
        embeddedHasData = true;
    }

    private static void scheduleEmbeddedRefresh() {
        embeddedLoading = false;
        embeddedRefreshAfterMillis = System.currentTimeMillis() + CLICK_REFRESH_DELAY_MILLIS;
    }

    private static WirelessAePackets.TargetNetworkEntry getConnectedEntry() {
        return embeddedConnectedEntry;
    }

    private static WirelessAePackets.TargetNetworkEntry getDisconnectableEntry() {
        return embeddedDisconnectableEntry;
    }

    private static void updateEmbeddedEntryCache() {
        embeddedConnectedEntry = null;
        embeddedDisconnectableEntry = null;
        for (WirelessAePackets.TargetNetworkEntry entry : embeddedEntries) {
            if (entry.connected() && embeddedConnectedEntry == null) {
                embeddedConnectedEntry = entry;
            }
            if (entry.disconnectable() && embeddedDisconnectableEntry == null) {
                embeddedDisconnectableEntry = entry;
            }
            if (embeddedConnectedEntry != null && embeddedDisconnectableEntry != null) {
                return;
            }
        }
    }

    private static boolean isLockedByCableConnection() {
        return getConnectedEntry() != null && getDisconnectableEntry() == null;
    }

    private static boolean isInsideRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
    }

    private static BlockPos getRecentClickedPos() {
        if (recentClickedPos == null || System.currentTimeMillis() - recentClickedAtMillis > RECENT_CLICK_MILLIS) {
            return null;
        }
        return recentClickedPos;
    }

    private static TargetHit getEmbeddedTargetHit(BlockPos targetPos) {
        if (embeddedTargetHit != null && embeddedTargetPos != null && embeddedTargetPos.equals(targetPos)) {
            return embeddedTargetHit;
        }
        TargetHit hit = findTargetHit(targetPos);
        return hit == null ? TargetHit.EMPTY : hit;
    }

    private static TargetHit findTargetHit(BlockPos targetPos) {
        if (targetPos == null) {
            return TargetHit.EMPTY;
        }

        if (Minecraft.getInstance().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && targetPos.equals(hit.getBlockPos())) {
            return new TargetHit(hit.getDirection(), hit.getLocation());
        }

        if (targetPos.equals(recentClickedPos) && System.currentTimeMillis() - recentClickedAtMillis <= RECENT_CLICK_MILLIS) {
            return new TargetHit(recentClickedSide, recentClickedLocation);
        }
        return TargetHit.EMPTY;
    }

    private static BlockPos getCrosshairBlockPos() {
        if (Minecraft.getInstance().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return null;
    }

    private static boolean shouldOfferWirelessTab(Level level, BlockPos pos) {
        return WirelessAeNetworkRuntime.canOpenWirelessTargetMenu(level, pos);
    }

    private static List<BlockPos> findTargetPositions(Object target) {
        List<BlockPos> positions = new ArrayList<>();
        collectTargetPositions(target, positions, java.util.Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        return positions;
    }

    private static void collectTargetPositions(Object target, List<BlockPos> positions, Set<Object> visited,
                                               int depth) {
        if (target == null || depth > 5 || !visited.add(target)) {
            return;
        }

        if (target instanceof BlockPos pos) {
            addCandidate(positions, pos);
            return;
        }
        if (target instanceof BlockEntity blockEntity) {
            addCandidate(positions, blockEntity.getBlockPos());
        }
        if (target instanceof Optional<?> optional) {
            optional.ifPresent(value -> collectTargetPositions(value, positions, visited, depth + 1));
            return;
        }

        for (String methodName : POSITION_METHODS) {
            Object result = invokeNoArg(target, methodName);
            if (result instanceof BlockPos pos) {
                addCandidate(positions, pos);
            }
        }

        for (String methodName : TARGET_METHODS) {
            collectTargetPositions(invokeNoArg(target, methodName), positions, visited, depth + 1);
        }

        for (String fieldName : TARGET_FIELDS) {
            collectTargetPositions(readField(target, fieldName), positions, visited, depth + 1);
        }
    }

    private static void addCandidate(List<BlockPos> candidates, BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos immutable = pos.immutable();
        for (BlockPos candidate : candidates) {
            if (candidate.equals(immutable)) {
                return;
            }
        }
        candidates.add(immutable);
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }

        Method method = findNoArgMethod(target.getClass(), methodName);
        if (method == null) {
            return null;
        }

        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method findNoArgMethod(Class<?> type, String methodName) {
        return METHOD_CACHE.computeIfAbsent(
                new ReflectionKey(type, methodName),
                WirelessAeScreenHooks::findNoArgMethodUncached)
                .orElse(null);
    }

    private static Optional<Method> findNoArgMethodUncached(ReflectionKey key) {
        Class<?> type = key.type();
        String methodName = key.name();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(methodName);
                if (method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return Optional.of(method);
                }
            } catch (NoSuchMethodException ignored) {
                // Try parent class.
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }

        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }

        try {
            return field.get(target);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static void writeField(Object target, Object value) {
        if (target == null) {
            return;
        }

        Field field = findField(target.getClass(), "allPages");
        if (field == null) {
            return;
        }

        try {
            field.set(target, value);
        } catch (IllegalAccessException | RuntimeException ignored) {
            // Keep the UI usable even if a future GT version changes this field.
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        return FIELD_CACHE.computeIfAbsent(
                new ReflectionKey(type, fieldName),
                WirelessAeScreenHooks::findFieldUncached)
                .orElse(null);
    }

    private static Optional<Field> findFieldUncached(ReflectionKey key) {
        Class<?> type = key.type();
        String fieldName = key.name();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
                // Try parent class.
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private record ReflectionKey(Class<?> type, String name) {}

    private static final class WirelessAeFancyPageProvider implements IFancyUIProvider {

        private AbstractContainerScreen<?> screen;
        private BlockPos targetPos;
        private final IFancyUIProvider mainPage;

        private WirelessAeFancyPageProvider(AbstractContainerScreen<?> screen, BlockPos targetPos,
                                            IFancyUIProvider mainPage) {
            this.screen = screen;
            this.targetPos = targetPos.immutable();
            this.mainPage = mainPage;
        }

        private void updateTarget(AbstractContainerScreen<?> screen, BlockPos targetPos) {
            this.screen = screen;
            this.targetPos = targetPos.immutable();
        }

        @Override
        public Widget createMainPage(FancyMachineUIWidget widget) {
            openEmbeddedPanel(screen, targetPos);
            return new WirelessAeFancyPageWidget(screen, targetPos);
        }

        @Override
        public IGuiTexture getTabIcon() {
            return WIRELESS_TAB_ICON;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("screen.gtlcore.wireless_target");
        }

        @Override
        public void attachSideTabs(TabsWidget tabsWidget) {
            tabsWidget.setMainTab(mainPage);
            mainPage.attachSideTabs(tabsWidget);
            ensureWirelessSideTab(tabsWidget, this);
        }

        @Override
        public boolean hasPlayerInventory() {
            return false;
        }

        @Override
        public List<Component> getTabTooltips() {
            return List.of(Component.translatable("tooltip.gtlcore.wireless_target.entry"));
        }
    }

    private static final class WirelessAeFancyPageWidget extends Widget {

        private final AbstractContainerScreen<?> screen;
        private final BlockPos targetPos;

        private WirelessAeFancyPageWidget(AbstractContainerScreen<?> screen, BlockPos targetPos) {
            super(0, 0, FANCY_PAGE_WIDTH, FANCY_PAGE_HEIGHT);
            this.screen = screen;
            this.targetPos = targetPos.immutable();
            setClientSideWidget();
        }

        @Override
        public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            if (Minecraft.getInstance().screen != this.screen) {
                return;
            }
            ensureEmbeddedData(targetPos);
            drawWirelessPage(graphics, getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight(), mouseX, mouseY);
        }

        @Override
        public void drawInForeground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            if (Minecraft.getInstance().screen != this.screen) {
                return;
            }
            List<Component> tooltip = getWirelessPageTooltip(
                    getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight(), mouseX, mouseY);
            if (!tooltip.isEmpty()) {
                ((ModularUIGuiContainer) this.screen).setHoverTooltip(tooltip, ItemStack.EMPTY, null, null);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if ((button != 0 && (button != 1 || bindingToolScreen != this.screen)) ||
                    Minecraft.getInstance().screen != this.screen || !isMouseOverElement(mouseX, mouseY)) {
                return false;
            }
            handleWirelessPageClick(targetPos, getPositionX(), getPositionY(),
                    getSizeWidth(), getSizeHeight(), mouseX, mouseY, button);
            playButtonClickSound();
            return true;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (updateEmbeddedScrollbarDrag(mouseY, getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight())) {
                return true;
            }
            return isMouseOverElement(mouseX, mouseY);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            embeddedDraggingScrollbar = false;
            return isMouseOverElement(mouseX, mouseY);
        }

        @Override
        public boolean mouseWheelMove(double mouseX, double mouseY, double wheelDelta) {
            if (scrollEmbedded(wheelDelta, getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight(), mouseX, mouseY)) {
                return true;
            }
            return isMouseOverElement(mouseX, mouseY);
        }
    }

    private record TargetHit(Direction side, Vec3 location) {

        private static final TargetHit EMPTY = new TargetHit(null, null);
    }

    private static void drawWirelessTabIcon(GuiGraphics graphics, int mouseX, int mouseY, float x, float y,
                                            int width, int height) {
        int iconSize = 24;
        int originX = Math.round(x) + (width - iconSize) / 2;
        int originY = Math.round(y) + (height - iconSize) / 2;
        graphics.blit(WIRELESS_TAB_TEXTURE, originX, originY, 0.0F, 0.0F, iconSize, iconSize, iconSize, iconSize);
    }
}
