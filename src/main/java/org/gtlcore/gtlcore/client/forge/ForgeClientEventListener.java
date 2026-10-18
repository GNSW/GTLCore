package org.gtlcore.gtlcore.client.forge;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.ae2.JeiTerminalSearchTarget;
import org.gtlcore.gtlcore.client.gui.IPatternModifierScreen;
import org.gtlcore.gtlcore.client.gui.SetReplaceAmountScreen;
import org.gtlcore.gtlcore.common.item.StructureWriteBehavior;
import org.gtlcore.gtlcore.integration.ae2.WirelessTerminalGridResolver;
import org.gtlcore.gtlcore.integration.ae2.wireless.JeiWirelessTerminalOrderPackets;
import org.gtlcore.gtlcore.integration.jei.JeiCheatModeCompat;
import org.gtlcore.gtlcore.integration.jei.JeiMeInventoryTooltip;
import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupingRules;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.client.utils.RenderBufferUtils;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.Tooltips;
import com.glodblock.github.extendedae.client.gui.GuiPatternModifier;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Optional;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class ForgeClientEventListener {

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        TagGroupingRules.TAG_GENERATION.incrementAndGet();
    }

    private static final int JEI_TERMINAL_SEARCH_KEY = InputConstants.KEY_F;
    private static final char JEI_TERMINAL_SEARCH_CHARACTER = 'f';
    private static final int JEI_WIRELESS_ORDER_MOUSE_BUTTON = GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
    private static final int JEI_WIRELESS_EXTRACT_MOUSE_BUTTON = GLFW.GLFW_MOUSE_BUTTON_LEFT;

    private static Screen pendingJeiTerminalSearchCharacterScreen;
    private static PendingJeiExtraction pendingJeiExtraction;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            org.gtlcore.gtlcore.client.NoClipClient.clientTick();
        }
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        org.gtlcore.gtlcore.client.NoClipClient.reset(event.getPlayer());
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        org.gtlcore.gtlcore.client.NoClipClient.reset(event.getPlayer());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJeiTerminalSearchKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getKeyCode() != JEI_TERMINAL_SEARCH_KEY) {
            pendingJeiTerminalSearchCharacterScreen = null;
            return;
        }
        pendingJeiTerminalSearchCharacterScreen = null;
        if (!(event.getScreen() instanceof JeiTerminalSearchTarget terminalSearchTarget) ||
                !LDLib.isJeiLoaded()) {
            return;
        }
        JeiMeInventoryTooltip.getHoveredIngredientName().ifPresent(searchText -> {
            terminalSearchTarget.gtlcore$setJeiSearchText(searchText);
            pendingJeiTerminalSearchCharacterScreen = event.getScreen();
            event.setCanceled(true);
        });
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJeiTerminalSearchCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (pendingJeiTerminalSearchCharacterScreen != event.getScreen()) {
            pendingJeiTerminalSearchCharacterScreen = null;
            return;
        }
        pendingJeiTerminalSearchCharacterScreen = null;
        if (Character.toLowerCase(event.getCodePoint()) == JEI_TERMINAL_SEARCH_CHARACTER) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJeiTerminalSearchKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (event.getKeyCode() == JEI_TERMINAL_SEARCH_KEY) {
            pendingJeiTerminalSearchCharacterScreen = null;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJeiWirelessOrderMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.isCanceled() || event.getButton() != JEI_WIRELESS_ORDER_MOUSE_BUTTON || !LDLib.isJeiLoaded() ||
                JeiCheatModeCompat.reservesInputForEditMode(event.getButton()) ||
                JeiMeInventoryTooltip.isFtbQuestsEditingScreen(event.getScreen())) {
            return;
        }
        JeiMeInventoryTooltip.getHoveredIngredientKey(event.getScreen(), event.getMouseX(), event.getMouseY()).ifPresent(key -> {
            JeiWirelessTerminalOrderPackets.sendRequest(key);
            event.setCanceled(true);
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onJeiWirelessExtractMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() == JEI_WIRELESS_EXTRACT_MOUSE_BUTTON) {
            pendingJeiExtraction = null;
        }
        if (!isJeiWirelessExtractionInput(event.getButton())) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        Optional<AEKey> hoveredKey = JeiMeInventoryTooltip.getHoveredIngredientKey(
                event.getScreen(), event.getMouseX(), event.getMouseY());
        if (hoveredKey.isEmpty()) {
            return;
        }
        boolean cheatMode = JeiCheatModeCompat.isCheatStackInputActive(JEI_WIRELESS_EXTRACT_MOUSE_BUTTON);
        if (cheatMode) {
            pendingJeiExtraction = new PendingJeiExtraction(event.getScreen(), hoveredKey.get(), true);
            event.setCanceled(true);
            return;
        }
        if (player == null) {
            return;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            return;
        }
        if (!WirelessTerminalGridResolver.hasWirelessTerminal(player)) {
            return;
        }
        pendingJeiExtraction = new PendingJeiExtraction(event.getScreen(), hoveredKey.get(), false);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onJeiWirelessExtractMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getButton() != JEI_WIRELESS_EXTRACT_MOUSE_BUTTON) {
            return;
        }
        PendingJeiExtraction pending = pendingJeiExtraction;
        pendingJeiExtraction = null;
        if (pending == null || pending.screen() != event.getScreen()) {
            return;
        }

        event.setCanceled(true);
        if (pending.cheatMode()) {
            JeiCheatModeCompat.executeCheatStackFallback(pending.key());
        } else {
            JeiWirelessTerminalOrderPackets.sendExtractionRequest(pending.key());
        }
    }

    private static boolean isJeiWirelessExtractionInput(int button) {
        return button == JEI_WIRELESS_EXTRACT_MOUSE_BUTTON && Screen.hasShiftDown() && LDLib.isJeiLoaded() &&
                !JeiCheatModeCompat.reservesInputForEditMode(button);
    }

    private record PendingJeiExtraction(Screen screen, AEKey key, boolean cheatMode) {}

    /**
     * 样板修改器材料替换页中键打开数量编辑界面（仿 AE2 样板编码终端行为）。
     */
    @SubscribeEvent
    public static void onPatternModifierReplaceAmountMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof GuiPatternModifier screen) || screen.getMenu().page != 1 ||
                !Minecraft.getInstance().options.keyPickItem.matchesMouse(event.getButton())) {
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !slot.hasItem() ||
                (slot != screen.getMenu().replaceTarget && slot != screen.getMenu().replaceWith)) {
            return;
        }
        ((IPatternModifierScreen) screen).gtlcore$openReplaceAmountScreen(slot);
        event.setCanceled(true);
    }

    /**
     * 样板修改器材料替换槽 tooltip 追加数量与中键设置提示。
     */
    @SubscribeEvent
    public static void onPatternModifierReplaceSlotTooltip(ItemTooltipEvent event) {
        if (!(Minecraft.getInstance().screen instanceof GuiPatternModifier screen) || screen.getMenu().page != 1) {
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || (slot != screen.getMenu().replaceTarget && slot != screen.getMenu().replaceWith)) {
            return;
        }
        var unwrapped = GenericStack.fromItemStack(event.getItemStack());
        if (unwrapped != null) {
            event.getToolTip().add(Tooltips.getAmountTooltip(ButtonToolTips.Amount, unwrapped));
        }
        event.getToolTip().add(Tooltips.getSetAmountTooltip());
    }

    /**
     * 移除 InvTweaks 添加到样板修改器数量编辑子屏上的排序按钮（低优先级保证在 InvTweaks 之后执行）。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSetReplaceAmountScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SetReplaceAmountScreen)) {
            return;
        }
        for (var listener : new ArrayList<>(event.getListenersList())) {
            if (listener.getClass().getName().startsWith("invtweaks.gui.InvTweaksButton")) {
                event.removeListener(listener);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;
            if (level == null || player == null) return;
            PoseStack poseStack = event.getPoseStack();
            Camera camera = event.getCamera();
            ItemStack held = player.getMainHandItem();
            if (StructureWriteBehavior.isItemStructureWriter(held)) {
                BlockPos[] poses = StructureWriteBehavior.getPos(held);
                if (poses == null) return;
                Vec3 pos = camera.getPosition();

                poseStack.pushPose();
                poseStack.translate(-pos.x, -pos.y, -pos.z);

                RenderSystem.disableDepthTest();
                RenderSystem.enableBlend();
                RenderSystem.disableCull();
                RenderSystem.blendFunc(
                        GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                Tesselator tesselator = Tesselator.getInstance();
                BufferBuilder buffer = tesselator.getBuilder();

                buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                RenderSystem.setShader(GameRenderer::getPositionColorShader);

                RenderBufferUtils.renderCubeFace(
                        poseStack,
                        buffer,
                        poses[0].getX(),
                        poses[0].getY(),
                        poses[0].getZ(),
                        poses[1].getX() + 1,
                        poses[1].getY() + 1,
                        poses[1].getZ() + 1,
                        0.2f,
                        0.2f,
                        1f,
                        0.25f,
                        true);

                tesselator.end();

                buffer.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
                RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
                RenderSystem.lineWidth(3);

                RenderBufferUtils.drawCubeFrame(
                        poseStack,
                        buffer,
                        poses[0].getX(),
                        poses[0].getY(),
                        poses[0].getZ(),
                        poses[1].getX() + 1,
                        poses[1].getY() + 1,
                        poses[1].getZ() + 1,
                        0.0f,
                        0.0f,
                        1f,
                        0.5f);

                tesselator.end();

                RenderSystem.enableCull();

                RenderSystem.disableBlend();
                RenderSystem.enableDepthTest();
                poseStack.popPose();
            }
        }
    }
}
