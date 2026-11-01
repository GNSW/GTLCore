package org.gtlcore.gtlcore.integration.terminal.host;

import org.gtlcore.gtlcore.api.gui.BlockMapSelectorWidget;
import org.gtlcore.gtlcore.common.block.BlockMap;
import org.gtlcore.gtlcore.network.GTLNetworkHandler;
import org.gtlcore.gtlcore.network.packet.CMirrorStateRequest;
import org.gtlcore.gtlcore.network.packet.CTerminalAction;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public final class TerminalHostPanel extends WidgetGroup {

    private static final int DIALOG_WIDTH = 180;
    private static final int BUILD_DIALOG_HEIGHT = 144;
    private static volatile WeakReference<TerminalHostPanel> activePanel = new WeakReference<>(null);
    private final FancyMachineUIWidget parent;
    private final IMultiController controller;
    private final TerminalKind kind;

    private WidgetGroup dialog;
    private boolean noHatch;
    private boolean buildPreferAe;
    private int repeatCount;
    private String blockType;
    private int blockTier;
    private String upgradeBlockType;
    private int upgradeBlockTier;
    private boolean returnToAe;
    private boolean mirrorMode;
    private boolean manualMirrorOverride;
    private ButtonWidget noHatchButton;
    private ButtonWidget withHatchButton;
    private ButtonWidget preferAeButton;
    private ButtonWidget aeButton;
    private ButtonWidget inventoryButton;
    private ButtonWidget mirrorButton;

    public TerminalHostPanel(FancyMachineUIWidget parent, IMultiController controller,
                             TerminalAccess.EquippedTerminal equipped) {
        super(Math.max(0, parent.getSizeWidth() + 4), 18, 20, 86);
        this.parent = parent;
        this.controller = controller;
        this.kind = equipped.kind();
        TerminalUiMemory.Data memory = TerminalUiMemory.get();
        this.noHatch = memory.buildNoHatch();
        this.buildPreferAe = memory.buildPreferAe();
        this.repeatCount = Math.max(0, Math.min(648, memory.repeatCount()));
        this.blockType = TerminalUiMemory.normalizeBlockType(memory.blockType());
        this.blockTier = Math.max(0, memory.blockTier());
        this.upgradeBlockType = TerminalUiMemory.normalizeBlockType(memory.upgradeBlockType());
        this.upgradeBlockTier = Math.max(0, memory.upgradeBlockTier());
        this.returnToAe = memory.dismantleToAe();
        this.mirrorMode = detectMirroredStructure();

        ButtonWidget build = new ButtonWidget(
                0, 0, 20, 20,
                buttonTexture("gtlcore.terminal_host.button.build.short", kind.canBuild()),
                ignored -> onBuildPressed());
        build.setHoverTooltips(kind.canBuild() ? Component.translatable("gtlcore.terminal_host.tooltip.build") : Component.translatable("gtlcore.terminal_host.tooltip.need_terminal"));

        ButtonWidget dismantle = new ButtonWidget(
                0, 22, 20, 20,
                buttonTexture("gtlcore.terminal_host.button.dismantle.short", kind.canDismantle()),
                ignored -> onDismantlePressed());
        dismantle.setHoverTooltips(kind.canDismantle() ? new Component[] {
                Component.translatable("gtlcore.terminal_host.tooltip.dismantle"),
                Component.translatable("gtlcore.terminal_host.tooltip.blacklist")
        } : new Component[] { Component.translatable("gtlcore.terminal_host.tooltip.need_ultimate") });
        dismantle.setActive(kind.canDismantle());

        ButtonWidget upgrade = new ButtonWidget(
                0, 44, 20, 20,
                buttonTexture("gtlcore.terminal_host.button.upgrade.short", kind.canDismantle()),
                ignored -> {
                    if (kind.canDismantle()) {
                        showUpgradeDialog();
                    }
                });
        upgrade.setHoverTooltips(kind.canDismantle() ? Component.translatable("gtlcore.terminal_host.tooltip.upgrade") : Component.translatable("gtlcore.terminal_host.tooltip.need_ultimate"));
        upgrade.setActive(kind.canDismantle());

        mirrorButton = new ButtonWidget(
                0, 66, 20, 20,
                mirrorTexture(mirrorMode),
                ignored -> {
                    if (kind.hasBuildOptions()) {
                        mirrorMode = !mirrorMode;
                        manualMirrorOverride = true;
                        refreshMirrorButton();
                    }
                });
        mirrorButton.setHoverTooltips(kind.hasBuildOptions() ? Component.translatable(mirrorMode ? "gtlcore.terminal_host.tooltip.mirror.on" : "gtlcore.terminal_host.tooltip.mirror.off") : Component.translatable("gtlcore.terminal_host.tooltip.mirror.unavailable"));
        mirrorButton.setActive(kind.hasBuildOptions());

        addWidget(build);
        addWidget(dismantle);
        addWidget(upgrade);
        addWidget(mirrorButton);
        activePanel = new WeakReference<>(this);
        if (kind.hasBuildOptions() && !controller.isFormed()) {
            GTLNetworkHandler.INSTANCE.sendToServer(
                    new CMirrorStateRequest(controller.self().getPos()));
        }
    }

    public static void acceptMirrorState(BlockPos controllerPos, boolean mirrored) {
        TerminalHostPanel panel = activePanel.get();
        if (panel == null || panel.manualMirrorOverride || panel.controller.isFormed() || !panel.controller.self().getPos().equals(controllerPos)) {
            return;
        }
        panel.mirrorMode = mirrored;
        panel.refreshMirrorButton();
    }

    private boolean detectMirroredStructure() {
        if (!controller.isFormed()) {
            return false;
        }
        var state = controller.getMultiblockState();
        return state != null && state.isNeededFlip();
    }

    private void onBuildPressed() {
        if (!kind.canBuild()) {
            return;
        }
        if (kind == TerminalKind.BASIC) {
            send(CTerminalAction.Action.BUILD, false, false, false, 0, "coil", 0);
            return;
        }
        showBuildDialog();
    }

    private void onDismantlePressed() {
        if (kind.canDismantle()) {
            showDismantleDialog();
        }
    }

    private void showBuildDialog() {
        closeDialog();
        int x = Math.max(4, (parent.getSizeWidth() - DIALOG_WIDTH) / 2);
        int y = Math.max(8, (parent.getSizeHeight() - BUILD_DIALOG_HEIGHT) / 2);
        dialog = new RainbowDialogGroup(x, y, DIALOG_WIDTH, BUILD_DIALOG_HEIGHT);
        dialog.setBackground(GuiTextures.BACKGROUND_INVERSE);

        dialog.addWidget(new LabelWidget(8, 6,
                Component.translatable("gtlcore.terminal_host.dialog.build.title").getString()));
        dialog.addWidget(closeButton(BUILD_DIALOG_HEIGHT));

        noHatchButton = new ButtonWidget(8, 24, 78, 18,
                optionTexture("gtlcore.terminal_host.option.no_hatch", noHatch),
                ignored -> {
                    noHatch = true;
                    refreshBuildOptions();
                });
        withHatchButton = new ButtonWidget(94, 24, 78, 18,
                optionTexture("gtlcore.terminal_host.option.with_hatch", !noHatch),
                ignored -> {
                    noHatch = false;
                    refreshBuildOptions();
                });
        dialog.addWidget(noHatchButton);
        dialog.addWidget(withHatchButton);

        dialog.addWidget(new LabelWidget(8, 49,
                Component.translatable("gtlcore.terminal_host.label.repeat").getString()));
        TextFieldWidget repeatInput = new TextFieldWidget(78, 46, 94, 16,
                () -> Integer.toString(repeatCount),
                value -> {
                    try {
                        repeatCount = Math.max(0, Math.min(648, Integer.parseInt(value)));
                    } catch (NumberFormatException ignored) {
                        repeatCount = 0;
                    }
                });
        repeatInput.setNumbersOnly(0, 648);
        repeatInput.setBordered(false);
        dialog.addWidget(repeatInput);

        dialog.addWidget(new LabelWidget(8, 72,
                Component.translatable("gtlcore.terminal_host.label.block_type").getString()));
        ButtonWidget selectBlock = new ButtonWidget(78, 69, 94, 18,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture("§b" + selectedBlockName(blockType, blockTier))),
                ignored -> showTierSelectorDialog());
        selectBlock.setHoverTooltips(
                Component.translatable("gtlcore.terminal_host.tooltip.open_block_selector"));
        dialog.addWidget(selectBlock);

        preferAeButton = new ButtonWidget(8, 94, 164, 18,
                checkTexture("gtlcore.terminal_host.option.build_prefer_ae", buildPreferAe),
                ignored -> {
                    buildPreferAe = !buildPreferAe;
                    refreshBuildOptions();
                });
        preferAeButton.setHoverTooltips(
                Component.translatable("gtlcore.terminal_host.tooltip.build_prefer_ae"));
        dialog.addWidget(preferAeButton);

        dialog.addWidget(new ButtonWidget(50, 118, 80, 18,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture(Component.translatable(
                                "gtlcore.terminal_host.action.start_build").getString())),
                ignored -> send(CTerminalAction.Action.BUILD, noHatch, buildPreferAe, mirrorMode,
                        repeatCount, blockType, blockTier)));

        openDialog();
    }

    private void refreshBuildOptions() {
        if (noHatchButton != null) {
            noHatchButton.setButtonTexture(
                    optionTexture("gtlcore.terminal_host.option.no_hatch", noHatch));
        }
        if (withHatchButton != null) {
            withHatchButton.setButtonTexture(
                    optionTexture("gtlcore.terminal_host.option.with_hatch", !noHatch));
        }
        if (preferAeButton != null) {
            preferAeButton.setButtonTexture(
                    checkTexture("gtlcore.terminal_host.option.build_prefer_ae", buildPreferAe));
        }
    }

    private void showTierSelectorDialog() {
        closeDialog();
        int x = Math.max(4, (parent.getSizeWidth() - DIALOG_WIDTH) / 2);
        int y = Math.max(8, (parent.getSizeHeight() - 160) / 2);
        dialog = new RainbowDialogGroup(x, y, DIALOG_WIDTH, 160);

        BlockMapSelectorWidget selector = new BlockMapSelectorWidget(
                2, 2, DIALOG_WIDTH - 4,
                (type, tier) -> {
                    blockType = TerminalUiMemory.normalizeBlockType(type);
                    int available = tierCandidates(blockType).size();
                    blockTier = Math.max(0, Math.min(tier, available - 1));
                },
                (type, tier) -> type.equals(blockType) && tier == blockTier);
        ItemStack selectorState = new ItemStack(Items.STONE);
        selectorState.getOrCreateTag().putString("blocks", blockType);
        selector.setInit(selectorState);
        selector.showType(true);
        dialog.addWidget(selector);
        dialog.addWidget(new ButtonWidget(50, 138, 80, 18,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture(Component.translatable(
                                "gtlcore.terminal_host.action.back_to_build").getString())),
                ignored -> showBuildDialog()));

        openDialog();
    }

    private void showDismantleDialog() {
        closeDialog();
        int x = Math.max(4, (parent.getSizeWidth() - DIALOG_WIDTH) / 2);
        int y = Math.max(8, (parent.getSizeHeight() - 88) / 2);
        dialog = new RainbowDialogGroup(x, y, DIALOG_WIDTH, 88);
        dialog.setBackground(GuiTextures.BACKGROUND_INVERSE);

        dialog.addWidget(new LabelWidget(8, 6,
                Component.translatable("gtlcore.terminal_host.dialog.dismantle.title").getString()));
        dialog.addWidget(closeButton(88));

        aeButton = new ButtonWidget(8, 24, 78, 18,
                optionTexture("gtlcore.terminal_host.option.ae", returnToAe),
                ignored -> {
                    returnToAe = true;
                    refreshDismantleOptions();
                });
        inventoryButton = new ButtonWidget(94, 24, 78, 18,
                optionTexture("gtlcore.terminal_host.option.inventory", !returnToAe),
                ignored -> {
                    returnToAe = false;
                    refreshDismantleOptions();
                });
        dialog.addWidget(aeButton);
        dialog.addWidget(inventoryButton);
        dialog.addWidget(new ButtonWidget(50, 60, 80, 18,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture(Component.translatable(
                                "gtlcore.terminal_host.action.start_dismantle").getString())),
                ignored -> send(
                        returnToAe ? CTerminalAction.Action.DISMANTLE_AE : CTerminalAction.Action.DISMANTLE_INVENTORY,
                        false, false, mirrorMode, 0, "coil", 0)));

        openDialog();
    }

    private void showUpgradeDialog() {
        closeDialog();
        int x = Math.max(4, (parent.getSizeWidth() - DIALOG_WIDTH) / 2);
        int y = Math.max(8, (parent.getSizeHeight() - 160) / 2);
        dialog = new RainbowDialogGroup(x, y, DIALOG_WIDTH, 160);
        dialog.setBackground(GuiTextures.BACKGROUND_INVERSE);

        BlockMapSelectorWidget selector = new BlockMapSelectorWidget(
                2, 2, DIALOG_WIDTH - 4,
                (type, tier) -> {
                    upgradeBlockType = TerminalUiMemory.normalizeBlockType(type);
                    int available = tierCandidates(upgradeBlockType).size();
                    upgradeBlockTier = Math.max(0, Math.min(tier, available - 1));
                },
                (type, tier) -> type.equals(upgradeBlockType) && tier == upgradeBlockTier);
        ItemStack selectorState = new ItemStack(Items.STONE);
        selectorState.getOrCreateTag().putString("blocks", upgradeBlockType);
        selector.setInit(selectorState);
        selector.showType(true);
        dialog.addWidget(selector);
        dialog.addWidget(new ButtonWidget(38, 138, 80, 18,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture(Component.translatable(
                                "gtlcore.terminal_host.action.start_upgrade").getString())),
                ignored -> send(CTerminalAction.Action.UPGRADE, false, false, false, 0,
                        upgradeBlockType, upgradeBlockTier)));
        dialog.addWidget(new ButtonWidget(124, 138, 48, 18,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture(Component.translatable(
                                "gtlcore.terminal_host.action.close").getString())),
                ignored -> closeDialog()));

        openDialog();
    }

    private void refreshDismantleOptions() {
        if (aeButton != null) {
            aeButton.setButtonTexture(
                    optionTexture("gtlcore.terminal_host.option.ae", returnToAe));
        }
        if (inventoryButton != null) {
            inventoryButton.setButtonTexture(
                    optionTexture("gtlcore.terminal_host.option.inventory", !returnToAe));
        }
    }

    private ButtonWidget closeButton(int dialogHeight) {
        return new ButtonWidget(DIALOG_WIDTH - 18, 4, 14, 14,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("×")),
                ignored -> closeDialog());
    }

    private void send(CTerminalAction.Action action, boolean noHatch,
                      boolean preferAe, boolean mirrorMode,
                      int repeatCount, String blockType, int blockTier) {
        if (action == CTerminalAction.Action.BUILD && kind != TerminalKind.BASIC) {
            TerminalUiMemory.rememberBuild(noHatch, preferAe, repeatCount, blockType, blockTier);
        } else if (action == CTerminalAction.Action.UPGRADE) {
            TerminalUiMemory.rememberUpgrade(blockType, blockTier);
        } else if (action != CTerminalAction.Action.BUILD) {
            TerminalUiMemory.rememberDismantle(action == CTerminalAction.Action.DISMANTLE_AE);
        }
        GTLNetworkHandler.INSTANCE.sendToServer(new CTerminalAction(
                controller.self().getPos(), action, noHatch, preferAe, mirrorMode,
                repeatCount, blockType, blockTier));
        closeDialog();
    }

    private void refreshMirrorButton() {
        if (mirrorButton != null) {
            mirrorButton.setButtonTexture(mirrorTexture(mirrorMode));
            mirrorButton.setHoverTooltips(Component.translatable(mirrorMode ? "gtlcore.terminal_host.tooltip.mirror.on" : "gtlcore.terminal_host.tooltip.mirror.off"));
        }
    }

    private void closeDialog() {
        if (dialog != null) {
            parent.removeWidget(dialog);
            dialog = null;
        }
    }

    private void openDialog() {
        parent.addWidget(dialog);
        parent.removeWidget(this);
        parent.addWidget(this);
    }

    private static GuiTextureGroup buttonTexture(String translationKey, boolean enabled) {
        return new GuiTextureGroup(
                GuiTextures.BUTTON,
                new TextTexture((enabled ? "§f" : "§8") + Component.translatable(translationKey).getString()));
    }

    private static GuiTextureGroup optionTexture(String translationKey, boolean selected) {
        return new GuiTextureGroup(
                GuiTextures.BUTTON,
                new TextTexture((selected ? "§a● " : "§7○ ") + Component.translatable(translationKey).getString()));
    }

    private static GuiTextureGroup checkTexture(String translationKey, boolean checked) {
        return new GuiTextureGroup(
                GuiTextures.BUTTON,
                new TextTexture((checked ? "§a☑ " : "§7☐ ") + Component.translatable(translationKey).getString()));
    }

    private static GuiTextureGroup mirrorTexture(boolean active) {
        return new GuiTextureGroup(
                GuiTextures.BUTTON,
                new TextTexture((active ? "§7" : "§f") + "⇆"));
    }

    private static String blockTypeLabel(String type) {
        return switch (type) {
            case "sc" -> Component.translatable("gtlcore.terminal_host.block_type.stellar").getString();
            case "sepm" -> Component.translatable("gtlcore.terminal_host.block_type.space_elevator").getString();
            case "cal" -> Component.translatable("gtlcore.terminal_host.block_type.component_casing").getString();
            default -> Component.translatable("gtlcore.terminal_host.block_type.coil").getString();
        };
    }

    private String selectedBlockName(String type, int tier) {
        try {
            var lazyBlocks = BlockMap.tierBlockMap.get(TerminalUiMemory.normalizeBlockType(type));
            if (lazyBlocks != null) {
                Block[] blocks = lazyBlocks.get();
                if (blocks.length > 0) {
                    int safeTier = Math.max(0, Math.min(tier, blocks.length - 1));
                    return blocks[safeTier].getName().getString();
                }
            }
        } catch (RuntimeException ignored) {
            // Use the stable label below.
        }
        return blockTypeLabel(type);
    }

    private static List<String> tierCandidates(String blockType) {
        Block[] blocks = BlockMap.tierBlockMap
                .get(TerminalUiMemory.normalizeBlockType(blockType)).get();
        List<String> result = new ArrayList<>(blocks.length);
        for (Block block : blocks) {
            result.add(block.getName().getString());
        }
        return result;
    }

    private static final class RainbowDialogGroup extends WidgetGroup {

        private final ColorRectTexture topColor = new ColorRectTexture();
        private final ColorRectTexture rightColor = new ColorRectTexture();
        private final ColorRectTexture bottomColor = new ColorRectTexture();
        private final ColorRectTexture leftColor = new ColorRectTexture();

        private RainbowDialogGroup(int x, int y, int width, int height) {
            super(x, y, width, height);
            addWidget(new Widget(0, 0, width, 2).setBackground(topColor));
            addWidget(new Widget(width - 2, 0, 2, height).setBackground(rightColor));
            addWidget(new Widget(0, height - 2, width, 2).setBackground(bottomColor));
            addWidget(new Widget(0, 0, 2, height).setBackground(leftColor));
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            float phase = (System.currentTimeMillis() % 6000L) / 6000.0F;
            topColor.setColor(rainbow(phase));
            rightColor.setColor(rainbow(phase + 0.25F));
            bottomColor.setColor(rainbow(phase + 0.50F));
            leftColor.setColor(rainbow(phase + 0.75F));
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return super.mouseClicked(mouseX, mouseY, button) || isMouseOverElement(mouseX, mouseY);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button,
                                    double dragX, double dragY) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY) || isMouseOverElement(mouseX, mouseY);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return super.mouseReleased(mouseX, mouseY, button) || isMouseOverElement(mouseX, mouseY);
        }

        @Override
        public boolean mouseWheelMove(double mouseX, double mouseY, double wheelDelta) {
            return super.mouseWheelMove(mouseX, mouseY, wheelDelta) || isMouseOverElement(mouseX, mouseY);
        }

        private static int rainbow(float hue) {
            return 0xFF000000 | Mth.hsvToRgb(hue % 1.0F, 0.62F, 1.0F);
        }
    }
}
