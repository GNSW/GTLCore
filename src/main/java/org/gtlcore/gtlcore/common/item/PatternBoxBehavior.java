package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.parts.IPart;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.blockentity.networking.CableBusBlockEntity;
import appeng.crafting.pattern.EncodedPatternItem;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.util.inv.CombinedInternalInventory;
import appeng.util.inv.FilteredInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;
import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixBase;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores encoded patterns and moves them to or from AE pattern containers.
 */
public class PatternBoxBehavior implements IItemUIFactory {

    public static final PatternBoxBehavior INSTANCE = new PatternBoxBehavior();

    private static final String INV_TAG = "PatternInv";

    private static final int COLS = 9;
    private static final int SLOTS_PER_PAGE = 36;
    private static final int ROWS = SLOTS_PER_PAGE / COLS;
    private static final int SLOT_SIZE = 18;
    private static final int LEFT = 7;
    private static final int PATTERN_TOP = 42;
    // Pattern grid bottom plus spacing before the player inventory.
    private static final int INV_TOP = PATTERN_TOP + ROWS * SLOT_SIZE + 8;
    private static final int WIDTH = 176;
    private static final int HEIGHT = INV_TOP + 82;
    private static final int PAGE_BUTTON_Y = 24;
    private static final int PREVIOUS_PAGE_BUTTON_X = 136;
    private static final int PAGE_LABEL_X = 104;
    private static final int PAGE_LABEL_Y = 29;
    private static final int NEXT_PAGE_BUTTON_X = 154;
    private static final int PAGE_BUTTON_SIZE = 16;

    public static int getPageCount() {
        return ConfigHolder.INSTANCE.machines.patternBoxPages;
    }

    public static int getSlotCount() {
        return getPageCount() * SLOTS_PER_PAGE;
    }

    /**
     * Reads the box inventory. Only encoded patterns are accepted.
     */
    public static ItemStackTransfer getInventory(ItemStack pouch) {
        ItemStackTransfer transfer = new ItemStackTransfer(getSlotCount());
        transfer.setFilter(stack -> stack.isEmpty() || PatternDetailsHelper.isEncodedPattern(stack));
        if (pouch.getTag() != null && pouch.hasTag() && pouch.getTag().contains(INV_TAG)) {
            transfer.deserializeNBT(pouch.getTag().getCompound(INV_TAG));
        }
        return transfer;
    }

    /**
     * Writes the inventory back into the box NBT.
     */
    public static void saveInventory(ItemStack pouch, ItemStackTransfer transfer) {
        pouch.getOrCreateTag().put(INV_TAG, transfer.serializeNBT());
    }

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        ItemStack pouch = holder.getHeld();
        ItemStackTransfer inventory = getInventory(pouch);
        inventory.setOnContentsChanged(() -> {
            saveInventory(pouch, inventory);
            holder.markAsDirty();
        });

        WidgetGroup group = new WidgetGroup(0, 0, WIDTH, HEIGHT);
        group.addWidget(new LabelWidget(LEFT, 6, () -> Component.translatable("item.gtlcore.pattern_box").getString()));

        // One page of pattern slots: 4 rows x 9 columns.
        int[] page = { 0 };
        WidgetGroup patternPage = new WidgetGroup(0, 0, WIDTH, PATTERN_TOP + ROWS * SLOT_SIZE);
        rebuildPatternPage(patternPage, inventory, page);
        group.addWidget(patternPage);

        // Build the player inventory manually so it aligns with the pattern grid.
        Inventory playerInv = player.getInventory();
        // Lock the slot that currently holds this box while its UI is open.
        int heldSlot = holder.getHand() == InteractionHand.MAIN_HAND ? playerInv.selected : -1;
        // Main inventory: 3 rows x 9 columns, slots 9..35.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = col + (row + 1) * COLS;
                group.addWidget(new SlotWidget(playerInv, index,
                        LEFT + col * SLOT_SIZE, INV_TOP + row * SLOT_SIZE, true, true)
                        .setBackgroundTexture(GuiTextures.SLOT));
            }
        }
        // Hotbar: 1 row x 9 columns, slots 0..8.
        int hotbarY = INV_TOP + 3 * SLOT_SIZE + 4;
        for (int col = 0; col < COLS; col++) {
            boolean locked = col == heldSlot;
            group.addWidget(new SlotWidget(playerInv, col,
                    LEFT + col * SLOT_SIZE, hotbarY, !locked, !locked)
                    .setBackgroundTexture(GuiTextures.SLOT));
        }

        return new ModularUI(WIDTH, HEIGHT, holder, player)
                .widget(group)
                .background(GuiTextures.BACKGROUND);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        // Sneak-use is reserved for pushing patterns into a target container.
        if (player.isShiftKeyDown()) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(serverPlayer, usedHand);
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }

    @Override
    public boolean sneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack itemStack, UseOnContext context) {
        InteractionResult result = handleBlockUse(itemStack, context);
        return result == InteractionResult.PASS ? InteractionResult.PASS : InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return handleBlockUse(context.getItemInHand(), context);
    }

    public static InteractionResult handleBlockUse(ItemStack itemStack, UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer serverPlayer)) {
            return isPotentialPatternTarget(context) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        InternalInventory providerInv = resolveProviderInventory(context);
        if (providerInv == null) {
            return InteractionResult.PASS;
        }

        ItemStackTransfer pouchInv = getInventory(itemStack);
        boolean changed;
        if (serverPlayer.isShiftKeyDown()) {
            // Sneak right-click: box -> target container.
            changed = movePouchToProvider(pouchInv, providerInv);
            if (changed) {
                saveInventory(itemStack, pouchInv);
                serverPlayer.displayClientMessage(Component.translatable("message.gtlcore.pattern_box_inserted"), true);
            } else {
                serverPlayer.displayClientMessage(Component.translatable("message.gtlcore.pattern_box_insert_failed"), true);
            }
        } else {
            // Right-click: target container -> box.
            changed = moveProviderToPouch(providerInv, pouchInv);
            if (changed) {
                saveInventory(itemStack, pouchInv);
                serverPlayer.displayClientMessage(Component.translatable("message.gtlcore.pattern_box_extracted"), true);
            } else {
                serverPlayer.displayClientMessage(Component.translatable("message.gtlcore.pattern_box_extract_failed"), true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Resolves AE pattern providers, pattern buffers, and multiblock pattern containers.
     */
    @Nullable
    private static InternalInventory resolveProviderInventory(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockEntity tile = level.getBlockEntity(pos);

        InternalInventory assemblerMatrixInventory = resolveAssemblerMatrixInventory(tile, level);
        if (assemblerMatrixInventory != null) {
            return assemblerMatrixInventory;
        }

        MetaMachine metaMachine = MetaMachine.getMachine(level, pos);
        if (metaMachine instanceof PatternContainer patternContainer) {
            return patternContainer.getTerminalPatternInventory();
        }
        if (metaMachine instanceof IMultiController controller) {
            InternalInventory inventory = resolvePatternContainerInventory(controller);
            if (inventory != null) {
                return inventory;
            }
        }
        if (metaMachine instanceof IMultiPart part) {
            InternalInventory inventory = resolvePatternContainerInventory(part);
            if (inventory != null) {
                return inventory;
            }
        }

        if (tile instanceof CableBusBlockEntity cable) {
            Vec3 hitVec = context.getClickLocation();
            Vec3 hitInBlock = new Vec3(hitVec.x - pos.getX(), hitVec.y - pos.getY(), hitVec.z - pos.getZ());
            IPart part = cable.getCableBus().selectPartLocal(hitInBlock).part;
            if (part instanceof PatternProviderLogicHost providerPart) {
                return providerPart.getLogic().getPatternInv();
            }
            return null;
        }
        if (tile instanceof PatternProviderLogicHost providerBlock) {
            return providerBlock.getLogic().getPatternInv();
        }
        if (tile instanceof MetaMachineBlockEntity mmbe) {
            Object machine = mmbe.getMetaMachine();
            if (machine instanceof PatternContainer patternContainer) {
                return patternContainer.getTerminalPatternInventory();
            }
            if (machine instanceof IMultiController controller) {
                return resolvePatternContainerInventory(controller);
            }
            if (machine instanceof IMultiPart part) {
                return resolvePatternContainerInventory(part);
            }
        }
        return null;
    }

    private static boolean isPotentialPatternTarget(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockEntity tile = level.getBlockEntity(pos);
        MetaMachine metaMachine = MetaMachine.getMachine(level, pos);
        if (tile instanceof CableBusBlockEntity || tile instanceof PatternProviderLogicHost) {
            return true;
        }
        if (isAssemblerMatrix(tile)) {
            return true;
        }
        if (metaMachine instanceof PatternContainer ||
                metaMachine instanceof IMultiController ||
                metaMachine instanceof IMultiPart) {
            return true;
        }
        if (tile instanceof MetaMachineBlockEntity mmbe) {
            Object machine = mmbe.getMetaMachine();
            return machine instanceof PatternContainer ||
                    machine instanceof IMultiController ||
                    machine instanceof IMultiPart;
        }
        return false;
    }

    @Nullable
    private static InternalInventory resolveAssemblerMatrixInventory(@Nullable BlockEntity tile, Level level) {
        if (tile instanceof TileAssemblerMatrixPattern patternTile) {
            return createAssemblerMatrixPatternInventory(patternTile, level);
        }
        if (!(tile instanceof TileAssemblerMatrixBase matrixBase)) {
            return null;
        }
        ClusterAssemblerMatrix cluster = matrixBase.getCluster();
        if (cluster == null || cluster.isDestroyed()) {
            return null;
        }
        List<InternalInventory> inventories = new ArrayList<>();
        for (TileAssemblerMatrixPattern patternTile : cluster.getPatterns()) {
            if (patternTile != null && patternTile.isValid()) {
                inventories.add(createAssemblerMatrixPatternInventory(patternTile, level));
            }
        }
        if (inventories.isEmpty()) {
            return null;
        }
        return inventories.size() == 1 ? inventories.get(0) :
                new CombinedInternalInventory(inventories.toArray(InternalInventory[]::new));
    }

    private static InternalInventory createAssemblerMatrixPatternInventory(TileAssemblerMatrixPattern patternTile, Level level) {
        return new FilteredInternalInventory(patternTile.getTerminalPatternInventory(), new IAEItemFilter() {

            @Override
            public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
                return stack.getItem() instanceof EncodedPatternItem &&
                        PatternDetailsHelper.decodePattern(stack, level) instanceof IMolecularAssemblerSupportedPattern;
            }
        });
    }

    private static boolean isAssemblerMatrix(@Nullable BlockEntity tile) {
        return tile instanceof TileAssemblerMatrixBase;
    }

    @Nullable
    private static InternalInventory resolvePatternContainerInventory(IMultiController controller) {
        if (!controller.isFormed()) {
            return null;
        }
        return resolvePatternContainerInventory(controller.getParts());
    }

    @Nullable
    private static InternalInventory resolvePatternContainerInventory(IMultiPart part) {
        if (!part.isFormed()) {
            return null;
        }
        for (IMultiController controller : part.getControllers()) {
            InternalInventory inventory = resolvePatternContainerInventory(controller);
            if (inventory != null) {
                return inventory;
            }
        }
        return null;
    }

    @Nullable
    private static InternalInventory resolvePatternContainerInventory(Iterable<IMultiPart> parts) {
        for (IMultiPart part : parts) {
            if (part instanceof PatternContainer patternContainer) {
                return patternContainer.getTerminalPatternInventory();
            }
        }
        return null;
    }

    private static void rebuildPatternPage(WidgetGroup patternPage, ItemStackTransfer inventory, int[] page) {
        patternPage.clearAllWidgets();
        int pageCount = getPageCount();
        page[0] = Math.min(page[0], pageCount - 1);
        int startSlot = page[0] * SLOTS_PER_PAGE;
        int endSlot = Math.min(startSlot + SLOTS_PER_PAGE, inventory.getSlots());
        for (int i = startSlot; i < endSlot; i++) {
            int slotInPage = i - startSlot;
            int row = slotInPage / COLS;
            int col = slotInPage % COLS;
            patternPage.addWidget(new SlotWidget(inventory, i,
                    LEFT + col * SLOT_SIZE, PATTERN_TOP + row * SLOT_SIZE, true, true)
                    .setBackgroundTexture(GuiTextures.SLOT)
                    .setIngredientIO(IngredientIO.INPUT));
        }
        patternPage.addWidget(new ButtonWidget(PREVIOUS_PAGE_BUTTON_X, PAGE_BUTTON_Y, PAGE_BUTTON_SIZE, PAGE_BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("<<")), clickData -> {
                    if (page[0] > 0) {
                        page[0]--;
                        rebuildPatternPage(patternPage, inventory, page);
                    }
                }));
        patternPage.addWidget(new LabelWidget(PAGE_LABEL_X, PAGE_LABEL_Y, (page[0] + 1) + " / " + pageCount));
        patternPage.addWidget(new ButtonWidget(NEXT_PAGE_BUTTON_X, PAGE_BUTTON_Y, PAGE_BUTTON_SIZE, PAGE_BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(">>")), clickData -> {
                    if (page[0] < pageCount - 1) {
                        page[0]++;
                        rebuildPatternPage(patternPage, inventory, page);
                    }
                }));
    }

    /**
     * Moves encoded patterns from the target container into the box.
     */
    private static boolean moveProviderToPouch(InternalInventory providerInv, ItemStackTransfer pouchInv) {
        boolean changed = false;
        for (int slot = 0; slot < providerInv.size(); slot++) {
            ItemStack stack = providerInv.getStackInSlot(slot);
            if (stack.isEmpty() || !PatternDetailsHelper.isEncodedPattern(stack)) {
                continue;
            }
            int pouchSlot = findEmptyPouchSlot(pouchInv);
            if (pouchSlot < 0) {
                break;
            }
            ItemStack extracted = providerInv.extractItem(slot, stack.getCount(), false);
            if (extracted.isEmpty()) {
                continue;
            }
            pouchInv.setStackInSlot(pouchSlot, extracted);
            changed = true;
        }
        return changed;
    }

    /**
     * Moves compatible patterns from the box into the target container.
     */
    private static boolean movePouchToProvider(ItemStackTransfer pouchInv, InternalInventory providerInv) {
        boolean changed = false;
        for (int slot = 0; slot < pouchInv.getSlots(); slot++) {
            ItemStack stack = pouchInv.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int providerSlot = findInsertableProviderSlot(providerInv, stack);
            if (providerSlot < 0) {
                continue;
            }
            ItemStack remainder = providerInv.insertItem(providerSlot, stack.copy(), false);
            pouchInv.setStackInSlot(slot, remainder);
            changed = true;
        }
        return changed;
    }

    private static int findEmptyPouchSlot(ItemStackTransfer pouchInv) {
        for (int slot = 0; slot < pouchInv.getSlots(); slot++) {
            if (pouchInv.getStackInSlot(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int findInsertableProviderSlot(InternalInventory providerInv, ItemStack stack) {
        for (int slot = 0; slot < providerInv.size(); slot++) {
            if (providerInv.isItemValid(slot, stack) &&
                    providerInv.insertItem(slot, stack.copy(), true).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }
}
