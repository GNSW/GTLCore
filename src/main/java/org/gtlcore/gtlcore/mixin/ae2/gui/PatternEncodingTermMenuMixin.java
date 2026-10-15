package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.gui.PatterEncodingTermMenuModify;
import org.gtlcore.gtlcore.common.data.GTLStats;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternEncoderMetadata;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadRecipeTypeResolver;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadSelectionMenu;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadService;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.ITerminalHost;
import appeng.core.definitions.AEItems;
import appeng.helpers.IMenuCraftingPacket;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.util.ConfigInventory;
import com.google.common.math.LongMath;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @author EasterFG on 2024/9/12
 */
@Mixin(value = PatternEncodingTermMenu.class, priority = 900)
public abstract class PatternEncodingTermMenuMixin extends MEStorageMenu implements IMenuCraftingPacket, PatterEncodingTermMenuModify {

    @Unique
    private static final ItemStack Pattern = AEItems.BLANK_PATTERN.stack();
    @Unique
    private static final String GTLCORE$SET_QUICK_UPLOAD_RECIPE_TYPE_ACTION = "setQuickUploadRecipeType";
    @Unique
    private static final String GTLCORE$UNDO_QUICK_UPLOAD_ACTION = "undoQuickUploadPattern";
    @Unique
    private static final String GTLCORE$QUICK_UPLOAD_LOG = "[PatternQuickUpload]";

    @Unique
    @Nullable
    private ResourceLocation gTLCore$pendingQuickUploadRecipeTypeId;
    @Unique
    private List<PatternQuickUploadService.Target> gTLCore$pendingQuickUploadTargets = List.of();
    @Unique
    @Nullable
    private PatternQuickUploadService.Target gTLCore$lastQuickUploadTarget;
    @Unique
    private ItemStack gTLCore$lastQuickUploadPattern = ItemStack.EMPTY;
    @Unique
    private int gTLCore$lastQuickUploadSlot = -1;

    @Shadow(remap = false)
    @Final
    private ConfigInventory encodedInputsInv;
    @Shadow(remap = false)
    @Final
    private ConfigInventory encodedOutputsInv;
    @Shadow(remap = false)
    @Final
    private RestrictedInputSlot blankPatternSlot;
    @Shadow(remap = false)
    @Final
    private RestrictedInputSlot encodedPatternSlot;

    @Shadow(remap = false)
    protected abstract @Nullable ItemStack encodePattern();

    @Shadow(remap = false)
    protected abstract boolean isPattern(ItemStack output);

    @Shadow(remap = false)
    protected abstract void clearPattern();

    public PatternEncodingTermMenuMixin(MenuType<?> menuType, int id, Inventory ip, ITerminalHost host) {
        super(menuType, id, ip, host);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;Lappeng/helpers/IPatternTerminalMenuHost;Z)V",
            at = @At("TAIL"),
            remap = false)
    public void initHooks(MenuType<?> menuType, int id, Inventory ip, IPatternTerminalMenuHost host, boolean bindInventory, CallbackInfo ci) {
        this.registerClientAction("modifyPatter", Integer.class,
                this::gTLCore$modifyPatter);
        this.registerClientAction("quickUploadPattern", this::gTLCore$quickUploadPattern);
        this.registerClientAction(GTLCORE$UNDO_QUICK_UPLOAD_ACTION, this::gTLCore$undoQuickUploadPattern);
        this.registerClientAction(GTLCORE$SET_QUICK_UPLOAD_RECIPE_TYPE_ACTION, String.class,
                this::gTLCore$setQuickUploadRecipeTypeFromClient);
    }

    /**
     * @author .
     * @reason 样板不足时自动填充(如果库存有)
     */
    @Overwrite(remap = false)
    public void encode() {
        if (isClientSide()) {
            sendClientAction("encode");
            return;
        }

        ItemStack encodedPattern = encodePattern();
        if (encodedPattern != null) {
            gTLCore$writeQuickUploadMetadata(encodedPattern);
            var encodeOutput = this.encodedPatternSlot.getItem();

            // first check the output slots, should either be null, or a pattern (encoded or otherwise)
            if (!encodeOutput.isEmpty() && !PatternDetailsHelper.isEncodedPattern(encodeOutput) && !AEItems.BLANK_PATTERN.isSameAs(encodeOutput)) {
                return;
            } // if nothing is there we should snag a new pattern.
            else if (encodeOutput.isEmpty()) {
                var blankPattern = this.blankPatternSlot.getItem();
                if (!isPattern(blankPattern)) {
                    return; // no blanks.
                }

                // remove one, and clear the input slot.
                blankPattern.shrink(1);
                if (blankPattern.getCount() <= 0) {
                    if (this.storage != null) {
                        long extract = this.storage.extract(AEItemKey.of(Pattern), 64, Actionable.SIMULATE, this.getActionSource());
                        if (extract > 0) {
                            extract = this.storage.extract(AEItemKey.of(Pattern), extract, Actionable.MODULATE, this.getActionSource());
                            this.blankPatternSlot.set(Pattern.copyWithCount((int) extract));
                        }
                    } else this.blankPatternSlot.set(ItemStack.EMPTY);
                }
            }

            gTLCore$writePatternEncoder(encodedPattern);
            this.encodedPatternSlot.set(encodedPattern);
            gTLCore$awardPatternEncoded();
        } else {
            clearPattern();
        }
    }

    @Override
    public void gTLCore$modifyPatter(Integer data) {
        if (this.isClientSide()) {
            this.sendClientAction("modifyPatter", data);
        } else {
            // modify
            var output = gTLCore$valid(this.encodedOutputsInv, data);
            if (output == null) {
                return;
            }
            var input = gTLCore$valid(this.encodedInputsInv, data);
            if (input == null) {
                return;
            }
            for (int slot = 0; slot < output.length; ++slot) {
                if (output[slot] != null) {
                    this.encodedOutputsInv.setStack(slot, output[slot]);
                }
            }
            for (int slot = 0; slot < input.length; ++slot) {
                if (input[slot] != null) {
                    this.encodedInputsInv.setStack(slot, input[slot]);
                }
            }
        }
    }

    @Override
    public void gTLCore$quickUploadPattern() {
        if (this.isClientSide()) {
            this.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_searching"), true);
            this.sendClientAction("quickUploadPattern");
            return;
        }
        if (!(this.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        GTLCore.LOGGER.debug("{} quickUploadPattern requested by {}", GTLCORE$QUICK_UPLOAD_LOG, player.getGameProfile().getName());
        this.gTLCore$pendingQuickUploadTargets = List.of();
        ItemStack previewPattern = gTLCore$previewPatternForQuickUpload(player);
        if (previewPattern == null) {
            GTLCore.LOGGER.debug("{} previewPattern returned null", GTLCORE$QUICK_UPLOAD_LOG);
            return;
        }
        GTLCore.LOGGER.debug("{} prepared pattern stack={}",
                GTLCORE$QUICK_UPLOAD_LOG,
                previewPattern.getHoverName().getString());

        PatternQuickUploadService.SearchResult result = PatternQuickUploadService.findTargets(
                player,
                this.getNetworkNode(),
                previewPattern);
        if (result.failureMessage() != null) {
            GTLCore.LOGGER.debug("{} target search failed: {}",
                    GTLCORE$QUICK_UPLOAD_LOG,
                    result.failureMessage().getString());
            player.displayClientMessage(result.failureMessage(), true);
            return;
        }

        var match = result.match();
        GTLCore.LOGGER.debug("{} target search status={} candidates={}",
                GTLCORE$QUICK_UPLOAD_LOG,
                match.status(),
                match.candidates().size());
        switch (match.status()) {
            case NONE -> {
                player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_no_target"), true);
            }
            case UNIQUE -> {
                PatternQuickUploadService.Target target = match.uniqueCandidate();
                gTLCore$encodeAndUploadPattern(player, target);
            }
            case AMBIGUOUS -> {
                this.gTLCore$pendingQuickUploadTargets = List.copyOf(match.candidates());
                WirelessAePackets.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new WirelessAePackets.OpenPatternQuickUploadSelectionPacket(
                                previewPattern,
                                gTLCore$selectionEntries(this.gTLCore$pendingQuickUploadTargets)));
            }
        }
    }

    @Override
    public void gTLCore$undoQuickUploadPattern() {
        if (this.isClientSide()) {
            this.sendClientAction(GTLCORE$UNDO_QUICK_UPLOAD_ACTION);
            return;
        }
        if (!(this.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (this.gTLCore$lastQuickUploadTarget == null || this.gTLCore$lastQuickUploadPattern.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_undo_empty"), true);
            return;
        }
        ItemStack restorePattern = this.gTLCore$lastQuickUploadPattern.copy();
        if (!this.encodedPatternSlot.getItem().isEmpty()) {
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_undo_output_blocked"), true);
            return;
        }
        PatternQuickUploadService.Target target = this.gTLCore$lastQuickUploadTarget;
        if (PatternQuickUploadService.removeFromTarget(player, restorePattern, target, this.gTLCore$lastQuickUploadSlot)) {
            this.encodedPatternSlot.set(restorePattern);
            this.gTLCore$lastQuickUploadTarget = null;
            this.gTLCore$lastQuickUploadPattern = ItemStack.EMPTY;
            this.gTLCore$lastQuickUploadSlot = -1;
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_undone", target.targetName()), true);
        } else {
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_undo_failed"), true);
        }
    }

    @Override
    public void gTLCore$selectQuickUploadTarget(int index) {
        if (this.isClientSide() || !(this.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (index < 0 || index >= this.gTLCore$pendingQuickUploadTargets.size()) {
            this.gTLCore$pendingQuickUploadTargets = List.of();
            return;
        }
        PatternQuickUploadService.Target target = this.gTLCore$pendingQuickUploadTargets.get(index);
        this.gTLCore$pendingQuickUploadTargets = List.of();
        gTLCore$encodeAndUploadPattern(player, target);
    }

    @Override
    public void gTLCore$setQuickUploadRecipeType(@Nullable ResourceLocation recipeTypeId) {
        if (this.isClientSide()) {
            this.sendClientAction(GTLCORE$SET_QUICK_UPLOAD_RECIPE_TYPE_ACTION,
                    recipeTypeId == null ? "" : recipeTypeId.toString());
            return;
        }
        this.gTLCore$pendingQuickUploadRecipeTypeId = recipeTypeId;
    }

    @Unique
    private GenericStack[] gTLCore$valid(ConfigInventory inv, int data) {
        // data 错误的被修改为正数, 在有多个多个材料时
        boolean flag = data > 0;
        if (!flag) {
            data = -data;
        }
        GenericStack[] result = new GenericStack[inv.size()];
        for (int slot = 0; slot < inv.size(); ++slot) {
            GenericStack stack = inv.getStack(slot);
            if (stack != null) {
                if (flag) {
                    long modify = LongMath.saturatedMultiply(data, stack.amount());
                    if (modify == Long.MAX_VALUE || modify == Long.MIN_VALUE) {
                        return null;
                    } else {
                        result[slot] = new GenericStack(stack.what(), modify);
                    }
                } else {
                    if (stack.amount() % data != 0) {
                        return null;
                    } else {
                        // 除尽
                        result[slot] = new GenericStack(stack.what(), stack.amount() / data);
                    }
                }
            }
        }
        return result;
    }

    @Unique
    @Nullable
    private ItemStack gTLCore$previewPatternForQuickUpload(ServerPlayer player) {
        ItemStack encodeOutput = this.encodedPatternSlot.getItem();
        if (PatternDetailsHelper.isEncodedPattern(encodeOutput)) {
            ItemStack pattern = encodeOutput.copy();
            gTLCore$writeQuickUploadMetadata(pattern, false);
            return pattern;
        }
        if (!encodeOutput.isEmpty() && !AEItems.BLANK_PATTERN.isSameAs(encodeOutput)) {
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_output_blocked"), true);
            return null;
        }

        ItemStack encodedPattern = encodePattern();
        if (encodedPattern == null || encodedPattern.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_invalid_pattern"), true);
            return null;
        }
        gTLCore$writeQuickUploadMetadata(encodedPattern, false);
        return encodedPattern;
    }

    @Unique
    private boolean gTLCore$hasPatternSourceForQuickUpload(ServerPlayer player) {
        ItemStack encodeOutput = this.encodedPatternSlot.getItem();
        if (PatternDetailsHelper.isEncodedPattern(encodeOutput)) {
            return true;
        }
        if (!encodeOutput.isEmpty() && !AEItems.BLANK_PATTERN.isSameAs(encodeOutput)) {
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_output_blocked"), true);
            return false;
        }
        if (AEItems.BLANK_PATTERN.isSameAs(encodeOutput)) {
            return true;
        }

        ItemStack blankPattern = this.blankPatternSlot.getItem();
        if (!isPattern(blankPattern)) {
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_missing_blank"), true);
            return false;
        }
        return true;
    }

    @Unique
    private void gTLCore$consumePatternSourceAfterQuickUpload() {
        ItemStack encodeOutput = this.encodedPatternSlot.getItem();
        if (!encodeOutput.isEmpty()) {
            this.encodedPatternSlot.set(ItemStack.EMPTY);
            return;
        }
        gTLCore$consumeBlankSlotPattern();
    }

    @Unique
    private void gTLCore$writeQuickUploadMetadata(ItemStack patternStack) {
        gTLCore$writeQuickUploadMetadata(patternStack, true);
    }

    @Unique
    private void gTLCore$writeQuickUploadMetadata(ItemStack patternStack, boolean consumePendingRecipeType) {
        if (!(this.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        Set<ResourceLocation> recipeTypeIds = new LinkedHashSet<>();
        if (this.gTLCore$pendingQuickUploadRecipeTypeId != null) {
            GTLCore.LOGGER.debug("{} using pending recipe type {}",
                    GTLCORE$QUICK_UPLOAD_LOG,
                    this.gTLCore$pendingQuickUploadRecipeTypeId);
            recipeTypeIds.addAll(PatternQuickUploadRecipeTypeResolver
                    .expandProxiedRecipeTypeIds(this.gTLCore$pendingQuickUploadRecipeTypeId));
            if (consumePendingRecipeType) {
                this.gTLCore$pendingQuickUploadRecipeTypeId = null;
            }
        } else {
            GTLCore.LOGGER.debug("{} resolving recipe type from encoded pattern {}",
                    GTLCORE$QUICK_UPLOAD_LOG,
                    patternStack.getHoverName().getString());
            recipeTypeIds.addAll(PatternQuickUploadRecipeTypeResolver.findRecipeTypeIds(player, patternStack));
        }
        if (!recipeTypeIds.isEmpty()) {
            GTLCore.LOGGER.debug("{} writing recipe types {}", GTLCORE$QUICK_UPLOAD_LOG, recipeTypeIds);
            PatternQuickUploadMetadata.writeRecipeTypeIds(patternStack, recipeTypeIds);
        } else {
            GTLCore.LOGGER.debug("{} no recipe type metadata found for pattern {}",
                    GTLCORE$QUICK_UPLOAD_LOG,
                    patternStack.getHoverName().getString());
        }
    }

    @Unique
    private void gTLCore$setQuickUploadRecipeTypeFromClient(String recipeTypeId) {
        this.gTLCore$pendingQuickUploadRecipeTypeId = PatternQuickUploadMetadata.parseRecipeTypeId(recipeTypeId);
    }

    @Unique
    private void gTLCore$consumeBlankSlotPattern() {
        ItemStack blankPattern = this.blankPatternSlot.getItem();
        blankPattern.shrink(1);
        if (blankPattern.getCount() > 0) {
            return;
        }
        if (this.storage != null) {
            long extract = this.storage.extract(AEItemKey.of(Pattern), 64, Actionable.SIMULATE, this.getActionSource());
            if (extract > 0) {
                extract = this.storage.extract(AEItemKey.of(Pattern), extract, Actionable.MODULATE, this.getActionSource());
                this.blankPatternSlot.set(Pattern.copyWithCount((int) extract));
                return;
            }
        }
        this.blankPatternSlot.set(ItemStack.EMPTY);
    }

    @Unique
    private void gTLCore$encodeAndUploadPattern(ServerPlayer player, PatternQuickUploadService.Target target) {
        boolean isNewPattern = !PatternDetailsHelper.isEncodedPattern(this.encodedPatternSlot.getItem());
        ItemStack preparedPattern = gTLCore$previewPatternForQuickUpload(player);
        if (preparedPattern == null) {
            return;
        }
        if (!gTLCore$hasPatternSourceForQuickUpload(player)) {
            return;
        }
        if (isNewPattern) {
            gTLCore$writePatternEncoder(preparedPattern);
        }
        PatternQuickUploadService.UploadResult uploadResult = PatternQuickUploadService.insertIntoTargetSlotResult(player, preparedPattern, target);
        if (uploadResult.status() == PatternQuickUploadService.UploadStatus.INSERTED) {
            this.gTLCore$pendingQuickUploadRecipeTypeId = null;
            this.gTLCore$lastQuickUploadTarget = uploadResult.target();
            this.gTLCore$lastQuickUploadPattern = preparedPattern.copy();
            this.gTLCore$lastQuickUploadSlot = uploadResult.slot();
            gTLCore$consumePatternSourceAfterQuickUpload();
            if (isNewPattern) {
                gTLCore$awardPatternEncoded();
            }
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_inserted", target.targetName()), true);
        } else if (uploadResult.status() == PatternQuickUploadService.UploadStatus.DUPLICATE) {
            WirelessAePackets.sendPatternQuickUploadDuplicate(player, target.targetName());
        } else {
            player.displayClientMessage(Component.translatable("message.gtlcore.pattern_quick_upload_insert_failed"), true);
        }
    }

    @Unique
    private void gTLCore$writePatternEncoder(ItemStack patternStack) {
        if (this.getPlayer() instanceof ServerPlayer player) {
            PatternEncoderMetadata.writeEncoder(
                    patternStack,
                    player.getUUID(),
                    player.getGameProfile().getName());
        }
    }

    @Unique
    private void gTLCore$awardPatternEncoded() {
        if (this.getPlayer() instanceof ServerPlayer player) {
            GTLStats.awardPatternEncoded(player);
        }
    }

    @Unique
    private static List<PatternQuickUploadSelectionMenu.Entry> gTLCore$selectionEntries(
                                                                                        List<PatternQuickUploadService.Target> targets) {
        return targets.stream()
                .map(target -> new PatternQuickUploadSelectionMenu.Entry(
                        target.levelKey(),
                        target.bufferPos(),
                        target.targetName(),
                        target.recipeTypeId(),
                        target.recipeTypeName(),
                        target.showsSinglePosition()))
                .toList();
    }
}
