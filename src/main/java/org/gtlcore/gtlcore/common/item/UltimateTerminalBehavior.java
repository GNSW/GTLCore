package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.api.gui.BlockMapSelectorWidget;
import org.gtlcore.gtlcore.api.gui.ExtendLabelWidget;
import org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.*;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.part.*;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.Block;

import com.hepdd.gtmthings.api.gui.widget.TerminalInputWidget;
import it.unimi.dsi.fastutil.objects.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.List;

import static net.minecraft.network.chat.Component.translatable;
import static org.gtlcore.gtlcore.api.gui.BlockMapSelectorWidget.*;
import static org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern.getAdvancedBlockPattern;
import static org.gtlcore.gtlcore.common.block.BlockMap.*;

/**
 * 代码参考自gtmthings
 * &#064;line <a href="https://github.com/liansishen/GTMThings">...</a>
 */

public class UltimateTerminalBehavior implements IItemUIFactory {

    private static final String ACTIVE_BLOCK_TYPE_TAG = "blocks";
    private static final String ACTIVE_TIER_TAG = "Tier";
    private static final String TIER_SELECTIONS_TAG = "TierSelections";
    private static final int BLOCK_SELECTOR_GAP = 4;

    public UltimateTerminalBehavior() {}

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            var level = context.getLevel();
            var blockPos = context.getClickedPos();

            // Try bind AE network
            if (!level.isClientSide) {
                if (AdvancedBlockPattern.getGridNode(level.getBlockEntity(blockPos)) != null) {
                    ItemStack handItem = context.getPlayer().getMainHandItem();
                    CompoundTag tag = handItem.getOrCreateTag();
                    // 用一个复合nbt来存储AE坐标
                    CompoundTag AEPos = new CompoundTag();
                    AEPos.putInt("X", blockPos.getX());
                    AEPos.putInt("Y", blockPos.getY());
                    AEPos.putInt("Z", blockPos.getZ());
                    AEPos.putString("dim", level.dimension().location().toString());
                    tag.put("BoundAE", AEPos);
                    handItem.setTag(tag);
                    context.getPlayer().sendSystemMessage(Component.translatable("gtlcore.terminal.ae_bound", blockPos.toShortString()));
                    return InteractionResult.SUCCESS;
                }
            }

            var metaMachine = MetaMachine.getMachine(level, blockPos);
            if (context.getPlayer() != null && !level.isClientSide() &&
                    metaMachine instanceof IMultiController controller) {
                var autoBuildSetting = getAutoBuildSetting(context.getPlayer().getMainHandItem());
                if (autoBuildSetting.isDismantleMode()) {
                    dismantleMultiblock(controller, context.getPlayer(), autoBuildSetting);
                } else {
                    if (!controller.isFormed()) {
                        Objects.requireNonNull(getAdvancedBlockPattern(controller.getPattern())).autoBuild(context.getPlayer(), controller.getMultiblockState(), autoBuildSetting);
                    } else if (metaMachine instanceof WorkableMultiblockMachine machine && autoBuildSetting.isReplaceMode()) {
                        Objects.requireNonNull(getAdvancedBlockPattern(controller.getPattern())).autoBuild(context.getPlayer(), controller.getMultiblockState(), autoBuildSetting);
                        machine.onPartUnload();
                    }
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    private AutoBuildSetting getAutoBuildSetting(ItemStack mainHandItem) {
        var autoBuildSetting = new AutoBuildSetting();
        var tag = mainHandItem.getOrCreateTag();
        if (!tag.isEmpty()) {
            autoBuildSetting.setRepeatCount(tag.getInt("RepeatCount"));
            autoBuildSetting.setNoHatchMode(tag.getBoolean("NoHatchMode"));
            autoBuildSetting.setReplaceMode(tag.getBoolean("ReplaceMode"));
            autoBuildSetting.setFlipped(tag.getBoolean("IsFlipped"));
            autoBuildSetting.setDismantleMode(tag.getBoolean("DismantleMode"));
            autoBuildSetting.setAeMode(tag.getBoolean("AEMode"));
            if (tag.contains("BoundAE")) {
                CompoundTag AEPos = tag.getCompound("BoundAE");
                int x = AEPos.getInt("X");
                int y = AEPos.getInt("Y");
                int z = AEPos.getInt("Z");
                ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(AEPos.getString("dim")));
                autoBuildSetting.setBoundAE(GlobalPos.of(dimension, new BlockPos(x, y, z)));
            }
            for (var entry : tierBlockMap.object2ObjectEntrySet()) {
                Block selectedBlock = getSelectedTierBlock(tag, entry.getKey());
                if (selectedBlock != null) {
                    autoBuildSetting.addTierSelection(entry.getValue().get(), selectedBlock);
                }
            }
        }
        return autoBuildSetting;
    }

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder heldItemHolder, Player player) {
        return (new ModularUI(166, 176, heldItemHolder, player)).widget(this.createWidget(player));
    }

    private Widget createWidget(Player player) {
        final var handItem = player.getMainHandItem();
        WidgetGroup group = new WidgetGroup(0, 0, 200, 136);
        var contain = new DraggableScrollableWidgetGroup(4, 4, 192, 128)
                .setBackground(GuiTextures.DISPLAY).setYScrollBarWidth(2)
                .setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(1.0F));
        contain.addWidget(new ExtendLabelWidget(65, 8, translatable("gui.gtlcore.ultimate_terminal_settings")))
                .addWidget(new ExtendLabelWidget(14, 26, translatable("gui.gtlcore.tier_blocks")))
                .addWidget(new ExtendLabelWidget(14, 46, translatable("gui.gtlcore.repeat_count")))
                .addWidget(new ExtendLabelWidget(14, 66, translatable("gui.gtlcore.no_hatch_mode"))
                        .setHoverTooltips(translatable("tooltip.gtlcore.no_hatch_mode")))
                .addWidget(new ExtendLabelWidget(14, 86, translatable("gui.gtlcore.replace_mode"))
                        .setHoverTooltips(translatable("tooltip.gtlcore.replace_mode")))
                .addWidget(new ExtendLabelWidget(14, 106, translatable("gui.gtlcore.mirror_mode"))
                        .setHoverTooltips(translatable("tooltip.gtlcore.mirror_mode")))
                .addWidget(new TerminalInputWidget(140, 45, 36, 12,
                        () -> getRepeatCount(handItem), (v) -> setRepeatCount(v, handItem)).setMax(648).setMin(0))
                .addWidget(new SwitchWidget(140, 63, 36, 14,
                        (c, b) -> setIsBuildHatches(!getIsBuildHatches(handItem), handItem)).setPressed(getIsBuildHatches(handItem))
                        .setTexture(new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("OFF")),
                                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("ON"))))
                .addWidget(new SwitchWidget(140, 83, 36, 14,
                        (c, b) -> setReplaceMode(!getReplaceMode(handItem), handItem)).setPressed(getReplaceMode(handItem))
                        .setTexture(new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("OFF")),
                                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("ON"))))
                .addWidget(new SwitchWidget(140, 103, 36, 14,
                        (c, b) -> setIsFlip(!getIsFlip(handItem), handItem)).setPressed(getIsFlip(handItem))
                        .setTexture(new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("OFF")),
                                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("ON"))))
                // AE模式相关设置
                .addWidget(new ExtendLabelWidget(14, 126, translatable("gui.gtlcore.ae_mode")))
                .addWidget(new SwitchWidget(140, 123, 36, 14,
                        (c, b) -> setAEMode(!getAEMode(handItem), handItem)).setPressed(getAEMode(handItem))
                        .setTexture(new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("OFF")),
                                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("ON"))))
                // 拆除模式相关设置
                .addWidget(new ExtendLabelWidget(14, 146, translatable("gui.gtlcore.dismantle_mode")))
                .addWidget(new SwitchWidget(140, 143, 36, 14,
                        (c, b) -> setDismantleMode(!getDismantleMode(handItem), handItem)).setPressed(getDismantleMode(handItem))
                        .setTexture(new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("OFF")),
                                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("ON"))));

        var blockMap = new BlockMapSelectorWidget(
                group.getSizeWidth() + BLOCK_SELECTOR_GAP, 0, group.getSizeWidth(), (s, i) -> {
                    if (s != null && i != null) {
                        setTierSelection(handItem, s, i);
                    }
                }, (s, i) -> isTierSelected(handItem, s, i));
        blockMap.setInit(handItem);
        var open = new SwitchWidget(14, 26, 30, 16, (c, f) -> blockMap.showType(f))
                .setHoverTooltips(Component.translatable("gui.gtlcore.open.config.map"));
        contain.addWidget(open);
        group.addWidget(contain).addWidget(blockMap).setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    private static boolean isTierSelected(ItemStack itemStack, String blockType, int tier) {
        var lazyTierBlocks = tierBlockMap.get(blockType);
        if (lazyTierBlocks == null) {
            return false;
        }
        Block[] tierBlocks = lazyTierBlocks.get();
        return tier >= 0 && tier < tierBlocks.length &&
                tierBlocks[tier] == getSelectedTierBlock(itemStack.getOrCreateTag(), blockType);
    }

    private static Block setTierSelection(ItemStack itemStack, String blockType, int tier) {
        var lazyTierBlocks = tierBlockMap.get(blockType);
        if (lazyTierBlocks == null) {
            return null;
        }
        Block[] tierBlocks = lazyTierBlocks.get();
        if (tier < 0 || tier >= tierBlocks.length) {
            return null;
        }

        CompoundTag tag = itemStack.getOrCreateTag();
        CompoundTag selections = tag.contains(TIER_SELECTIONS_TAG, Tag.TAG_COMPOUND) ?
                tag.getCompound(TIER_SELECTIONS_TAG) : new CompoundTag();
        String activeBlockType = tag.getString(ACTIVE_BLOCK_TYPE_TAG);
        if (!activeBlockType.isEmpty() && !selections.contains(activeBlockType, Tag.TAG_STRING)) {
            Block activeBlock = getSelectedTierBlock(tag, activeBlockType);
            if (activeBlock != null) {
                selections.putString(activeBlockType, BuiltInRegistries.BLOCK.getKey(activeBlock).toString());
            }
        }

        Block selectedBlock = tierBlocks[tier];
        selections.putString(blockType, BuiltInRegistries.BLOCK.getKey(selectedBlock).toString());
        tag.put(TIER_SELECTIONS_TAG, selections);
        tag.putString(ACTIVE_BLOCK_TYPE_TAG, blockType);
        tag.putInt(ACTIVE_TIER_TAG, tier);
        itemStack.setTag(tag);
        return selectedBlock;
    }

    private static Block getSelectedTierBlock(CompoundTag tag, String blockType) {
        var lazyTierBlocks = tierBlockMap.get(blockType);
        if (lazyTierBlocks == null) {
            return null;
        }
        Block[] tierBlocks = lazyTierBlocks.get();
        if (tierBlocks.length == 0) {
            return null;
        }

        if (tag.contains(TIER_SELECTIONS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag selections = tag.getCompound(TIER_SELECTIONS_TAG);
            if (selections.contains(blockType, Tag.TAG_STRING)) {
                ResourceLocation blockId = ResourceLocation.tryParse(selections.getString(blockType));
                if (blockId == null) {
                    return null;
                }
                Block selectedBlock = BuiltInRegistries.BLOCK.getOptional(blockId).orElse(null);
                return containsBlock(tierBlocks, selectedBlock) ? selectedBlock : null;
            }
        }

        if (!blockType.equals(tag.getString(ACTIVE_BLOCK_TYPE_TAG))) {
            return null;
        }
        int tier = Math.max(0, Math.min(tag.getInt(ACTIVE_TIER_TAG), tierBlocks.length - 1));
        return tierBlocks[tier];
    }

    private static boolean containsBlock(Block[] tierBlocks, Block selectedBlock) {
        if (selectedBlock == null) {
            return false;
        }
        for (Block tierBlock : tierBlocks) {
            if (tierBlock == selectedBlock) {
                return true;
            }
        }
        return false;
    }

    private static int getRepeatCount(ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        return !tag.isEmpty() ? tag.getInt("RepeatCount") : 0;
    }

    private static boolean getIsFlip(ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        return !tag.isEmpty() && tag.getBoolean("IsFlipped");
    }

    private static boolean getDismantleMode(ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        return !tag.isEmpty() && tag.getBoolean("DismantleMode");
    }

    private static void setDismantleMode(boolean isDismantle, ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        tag.putBoolean("DismantleMode", isDismantle);
        itemStack.setTag(tag);
    }

    private static boolean getAEMode(ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        return !tag.isEmpty() && tag.getBoolean("AEMode");
    }

    private static void setAEMode(boolean isAEMode, ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        tag.putBoolean("AEMode", isAEMode);
        itemStack.setTag(tag);
    }

    private static void setRepeatCount(int repeatCount, ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        tag.putInt("RepeatCount", repeatCount);
        itemStack.setTag(tag);
    }

    private static boolean getIsBuildHatches(ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        return tag.isEmpty() || tag.getBoolean("NoHatchMode");
    }

    private static void setIsBuildHatches(boolean isBuildHatches, ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        tag.putBoolean("NoHatchMode", isBuildHatches);
        itemStack.setTag(tag);
    }

    private static boolean getReplaceMode(ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        return !tag.isEmpty() && tag.getBoolean("ReplaceMode");
    }

    private static void setReplaceMode(boolean isReplace, ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        tag.putBoolean("ReplaceMode", isReplace);
        itemStack.setTag(tag);
    }

    private static void setIsFlip(boolean isFlip, ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrCreateTag();
        tag.putBoolean("IsFlipped", isFlip);
        itemStack.setTag(tag);
    }

    private void dismantleMultiblock(IMultiController controller, Player player,
                                     AutoBuildSetting autoBuildSetting) {
        var level = player.level();
        // 仅在服务端执行
        if (level.isClientSide()) return;

        var pattern = controller.getPattern();
        if (pattern == null) return;

        AdvancedBlockPattern advancedPattern = getAdvancedBlockPattern(pattern);
        if (advancedPattern == null) return;

        advancedPattern.dismantleMultiblock(controller, player, autoBuildSetting);
    }

    @Setter
    @Getter
    public static class AutoBuildSetting {

        @Getter(AccessLevel.NONE)
        private final Object2ObjectMap<Block, Block> tierReplacements = new Object2ObjectOpenHashMap<>();
        private final Set<Block> blocks = new ObjectOpenHashSet<>();
        private int repeatCount;
        private boolean noHatchMode, replaceMode, isFlipped, dismantleMode, aeMode;
        private GlobalPos boundAE;

        public AutoBuildSetting() {
            this.repeatCount = 0;
            this.noHatchMode = true;
            this.replaceMode = false;
            this.isFlipped = false;
            this.dismantleMode = false;
            this.aeMode = false;
        }

        private void addTierSelection(Block[] tierBlocks, Block selectedBlock) {
            for (Block tierBlock : tierBlocks) {
                this.tierReplacements.put(tierBlock, selectedBlock);
                this.blocks.add(tierBlock);
            }
        }

        public List<ItemStack> apply(BlockInfo[] blockInfos) {
            List<ItemStack> candidates = new ObjectArrayList<>();
            if (blockInfos != null) {
                for (var info : blockInfos) {
                    Block selectedTierBlock = this.tierReplacements.get(info.getBlockState().getBlock());
                    if (selectedTierBlock != null && blockInfos.length > 1) {
                        candidates.add(selectedTierBlock.asItem().getDefaultInstance());
                        return candidates;
                    }
                    if (info.getBlockState().getBlock() instanceof LiquidBlock liquidBlock) {
                        candidates.add(liquidBlock.getFluid().getBucket().getDefaultInstance());
                    } else if (info.getBlockState().getBlock() != Blocks.AIR) {
                        candidates.add(info.getItemStackForm());
                    }
                }
            }
            return candidates;
        }

        public boolean isPlaceHatch(BlockInfo[] blockInfos) {
            if (!this.noHatchMode) return true;
            if (blockInfos != null && blockInfos.length > 0) {
                var blockInfo = blockInfos[0];
                return !Hatch.Set.contains(blockInfo.getBlockState().getBlock());
            }
            return true;
        }

        public boolean shouldPreserveDuringDismantle(Block block) {
            return this.dismantleMode && this.noHatchMode && Hatch.Set.contains(block);
        }

        private static final class Hatch {

            public static final Set<Block> Set = new ObjectOpenHashSet<>();

            static {
                GTRegistries.MACHINES.forEach(d -> {
                    if (d.getRecipeTypes() != null || d instanceof MultiblockMachineDefinition) return;
                    var block = d.getBlock();
                    if (d.createMetaMachine((IMachineBlockEntity) d.getBlockEntityType().create(BlockPos.ZERO, block.defaultBlockState())) instanceof MultiblockPartMachine) Set.add(block);
                });
            }
        }
    }
}
