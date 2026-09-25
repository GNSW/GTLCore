package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.common.data.GTLItems;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TankWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.FluidStorage;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.fluid.FluidHelper;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Wraps one real item or fluid so an AE network can hand the wrapper around in place of the material itself.
 * <p>
 * Once published to a network a wrapper is marked and can no longer give its payload back, otherwise one locked copy
 * could be released and re-locked indefinitely while the supply machine kept publishing it.
 * <p>
 * Stateless singleton shared by every player, so nothing player- or hand-specific may be cached here; it all comes
 * from the {@link HeldItemUIFactory.HeldItemHolder}.
 * <p>
 * Item texture adapted from GTMThings (com.hepdd.gtmthings, {@code virtual_ingredient_provider}).
 */
public class VirtualIngredientBehavior implements IItemUIFactory, IAddInformation {

    public static final VirtualIngredientBehavior INSTANCE = new VirtualIngredientBehavior();

    private static final String ITEM_TAG = "VirtualItem";
    private static final String FLUID_TAG = "VirtualFluid";
    /** Same key GTMThings uses upstream. */
    private static final String MARKED_TAG = "marked";

    private static final long FLUID_CAPACITY = FluidHelper.getBucket();

    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;
    private static final int LEFT = 7;
    private static final int COLS = 9;
    private static final int SLOT_SIZE = 18;
    private static final int BOUND_ROW_Y = 30;
    private static final int ITEM_SLOT_X = 53;
    private static final int FLUID_SLOT_X = 105;
    private static final int INV_TOP = 84;
    private static final int HOTBAR_TOP = INV_TOP + 3 * SLOT_SIZE + 4;

    /** Detached copy; write it back with {@link #saveItemStorage}. */
    public static ItemStackTransfer getItemStorage(ItemStack wrapper) {
        ItemStackTransfer transfer = new ItemStackTransfer(1);
        // Nesting would let a wrapper claim a payload it does not lock.
        transfer.setFilter(stack -> stack.isEmpty() || !GTLItems.VIRTUAL_INGREDIENT.isIn(stack));
        CompoundTag tag = wrapper.getTag();
        if (tag != null && tag.contains(ITEM_TAG)) {
            transfer.deserializeNBT(tag.getCompound(ITEM_TAG));
        }
        return transfer;
    }

    public static void saveItemStorage(ItemStack wrapper, ItemStackTransfer transfer) {
        wrapper.getOrCreateTag().put(ITEM_TAG, transfer.serializeNBT());
    }

    /** Detached copy; write it back with {@link #saveFluidStorage}. */
    public static FluidStorage getFluidStorage(ItemStack wrapper) {
        FluidStorage storage = new FluidStorage(FLUID_CAPACITY);
        CompoundTag tag = wrapper.getTag();
        if (tag != null && tag.contains(FLUID_TAG)) {
            storage.deserializeNBT(tag.getCompound(FLUID_TAG));
        }
        return storage;
    }

    public static void saveFluidStorage(ItemStack wrapper, FluidStorage storage) {
        wrapper.getOrCreateTag().put(FLUID_TAG, storage.serializeNBT());
    }

    @Nullable
    public static AEItemKey payloadItemKey(ItemStack wrapper) {
        ItemStack locked = getItemStorage(wrapper).getStackInSlot(0);
        return locked.isEmpty() ? null : AEItemKey.of(locked);
    }

    @Nullable
    public static AEFluidKey payloadFluidKey(ItemStack wrapper) {
        FluidStack locked = getFluidStorage(wrapper).getFluidInTank(0);
        return locked.isEmpty() ? null : AEFluidKey.of(locked.getFluid());
    }

    public static boolean isBound(ItemStack wrapper) {
        return payloadItemKey(wrapper) != null || payloadFluidKey(wrapper) != null;
    }

    /** The published token depends on its payload, not unused UI storage tags or its display name. */
    public static ItemStack canonicalStack(ItemStack wrapper) {
        if (!GTLItems.VIRTUAL_INGREDIENT.isIn(wrapper)) return ItemStack.EMPTY;
        ItemStack item = getItemStorage(wrapper).getStackInSlot(0);
        FluidStack fluid = getFluidStorage(wrapper).getFluidInTank(0);
        // Do not turn a malformed wrapper carrying two payloads into two supplies.
        if (item.isEmpty() == fluid.isEmpty()) return ItemStack.EMPTY;
        return item.isEmpty() ? wrap(fluid) : wrap(item);
    }

    /** Sealed on creation: built for publishing, not for the player to edit. */
    public static ItemStack wrap(ItemStack payload) {
        ItemStack wrapper = new ItemStack(GTLItems.VIRTUAL_INGREDIENT.asItem());
        if (!payload.isEmpty()) {
            ItemStackTransfer transfer = new ItemStackTransfer(1);
            transfer.setStackInSlot(0, payload.copyWithCount(1));
            saveItemStorage(wrapper, transfer);
        }
        mark(wrapper);
        return wrapper;
    }

    public static ItemStack wrap(FluidStack payload) {
        ItemStack wrapper = new ItemStack(GTLItems.VIRTUAL_INGREDIENT.asItem());
        if (!payload.isEmpty()) {
            FluidStorage storage = new FluidStorage(FLUID_CAPACITY);
            storage.setFluidInTank(0, payload.copy(FLUID_CAPACITY));
            saveFluidStorage(wrapper, storage);
        }
        mark(wrapper);
        return wrapper;
    }

    /** Marked means published to a network, and so no longer able to give its payload back. */
    public static boolean isMarked(ItemStack wrapper) {
        CompoundTag tag = wrapper.getTag();
        return tag != null && tag.getBoolean(MARKED_TAG);
    }

    public static void mark(ItemStack wrapper) {
        wrapper.getOrCreateTag().putBoolean(MARKED_TAG, true);
    }

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        ItemStack wrapper = holder.getHeld();
        boolean editable = !isMarked(wrapper);

        ItemStackTransfer itemStorage = getItemStorage(wrapper);
        FluidStorage fluidStorage = getFluidStorage(wrapper);

        // Item xor fluid: AE keys a wrapper by its whole NBT, so carrying both yields a third key matching neither.
        itemStorage.setFilter(stack -> stack.isEmpty() ||
                (!GTLItems.VIRTUAL_INGREDIENT.isIn(stack) && fluidStorage.getFluidInTank(0).isEmpty()));
        fluidStorage.setValidator(fluid -> fluid.isEmpty() || itemStorage.getStackInSlot(0).isEmpty());

        itemStorage.setOnContentsChanged(() -> {
            saveItemStorage(wrapper, itemStorage);
            holder.markAsDirty();
        });
        fluidStorage.setOnContentsChanged(() -> {
            saveFluidStorage(wrapper, fluidStorage);
            holder.markAsDirty();
        });

        WidgetGroup group = new WidgetGroup(0, 0, WIDTH, HEIGHT);
        group.addWidget(new LabelWidget(LEFT, 6,
                () -> Component.translatable("item.gtlcore.virtual_ingredient").getString()));
        group.addWidget(new LabelWidget(LEFT, 18, () -> Component
                .translatable(editable ? "gui.gtlcore.virtual_ingredient.hint" : "gui.gtlcore.virtual_ingredient.sealed")
                .getString()));

        group.addWidget(new SlotWidget(itemStorage, 0, ITEM_SLOT_X, BOUND_ROW_Y, editable, editable)
                .setBackgroundTexture(GuiTextures.SLOT));
        group.addWidget(new TankWidget(fluidStorage, 0, FLUID_SLOT_X, BOUND_ROW_Y, editable, editable)
                .setBackground(GuiTextures.FLUID_SLOT));

        Inventory playerInv = player.getInventory();
        // Held wrapper must stay put while its own UI edits it.
        int heldSlot = holder.getHand() == InteractionHand.MAIN_HAND ? playerInv.selected : -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = col + (row + 1) * COLS;
                group.addWidget(new SlotWidget(playerInv, index,
                        LEFT + col * SLOT_SIZE, INV_TOP + row * SLOT_SIZE, true, true)
                        .setBackgroundTexture(GuiTextures.SLOT));
            }
        }
        for (int col = 0; col < COLS; col++) {
            boolean locked = col == heldSlot;
            group.addWidget(new SlotWidget(playerInv, col,
                    LEFT + col * SLOT_SIZE, HOTBAR_TOP, !locked, !locked)
                    .setBackgroundTexture(GuiTextures.SLOT));
        }

        return new ModularUI(WIDTH, HEIGHT, holder, player)
                .widget(group)
                .background(GuiTextures.BACKGROUND);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (player instanceof ServerPlayer serverPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(serverPlayer, usedHand);
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        AEItemKey itemKey = payloadItemKey(stack);
        if (itemKey != null) {
            tooltip.add(Component.translatable("tooltip.gtlcore.virtual_ingredient.payload",
                    itemKey.getDisplayName()).withStyle(ChatFormatting.AQUA));
        }
        AEFluidKey fluidKey = payloadFluidKey(stack);
        if (fluidKey != null) {
            tooltip.add(Component.translatable("tooltip.gtlcore.virtual_ingredient.payload",
                    fluidKey.getDisplayName()).withStyle(ChatFormatting.AQUA));
        }
        if (itemKey == null && fluidKey == null) {
            tooltip.add(Component.translatable("tooltip.gtlcore.virtual_ingredient.empty")
                    .withStyle(ChatFormatting.GRAY));
        } else if (isMarked(stack)) {
            tooltip.add(Component.translatable("tooltip.gtlcore.virtual_ingredient.sealed")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
