package org.gtlcore.gtlcore.common.machine;

import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.UITemplate;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IDropSaveMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineModifyDrops;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;

/**
 * Publishes an unlimited virtual ingredient to the ME network for every real stack parked inside.
 * <p>
 * Adapted from GTOCore's {@code VirtualItemProviderMachine} (com.gtocore.common.machine.noenergy).
 */
public class VirtualIngredientSupplyMachine extends MetaMachine
                                            implements IUIMachine, IDropSaveMachine, IMachineModifyDrops, MEStorage,
                                            IGridConnectedMachine, IStorageProvider {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            VirtualIngredientSupplyMachine.class, MetaMachine.MANAGED_FIELD_HOLDER);

    public static final int SLOT_COUNT = 288;
    private static final int COLS = 9;
    private static final int SLOT_SIZE = 18;
    /** Caps how much the crafting planner will pull from here, so anything short of unlimited caps order size. */
    private static final long PUBLISHED_AMOUNT = Long.MAX_VALUE;

    @Persisted
    private final NotifiableItemStackHandler inventory;

    @Persisted
    private final GridNodeHolder nodeHolder;

    @DescSynced
    private boolean isOnline;

    /** Derived from the inventory, so rebuilt on change rather than persisted. */
    private final Object2LongMap<AEKey> published = new Object2LongOpenHashMap<>();

    private KeyCounter cachedStacks;
    private boolean dirty = true;

    public VirtualIngredientSupplyMachine(IMachineBlockEntity holder) {
        super(holder);
        // IO.NONE keeps recipe logic from seeing this as machine input or output.
        this.inventory = new NotifiableItemStackHandler(this, SLOT_COUNT, IO.NONE, IO.BOTH);
        this.nodeHolder = new GridNodeHolder(this);
        getMainNode().addService(IStorageProvider.class, this);
        this.inventory.addChangedListener(this::rebuildPublishedKeys);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        inventory.notifyListeners();
        rebuildPublishedKeys();
    }

    private void rebuildPublishedKeys() {
        published.clear();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            ItemStack wrapper;
            if (GTLItems.VIRTUAL_INGREDIENT.isIn(stack)) {
                if (!VirtualIngredientBehavior.isBound(stack)) continue;
                // Sealed in place: an editable wrapper here would turn one locked copy into unlimited real material.
                VirtualIngredientBehavior.mark(stack);
                wrapper = VirtualIngredientBehavior.canonicalStack(stack);
            } else {
                wrapper = VirtualIngredientBehavior.wrap(stack);
            }
            if (!wrapper.isEmpty()) published.put(AEItemKey.of(wrapper), PUBLISHED_AMOUNT);
        }
        dirty = true;
        if (cachedStacks != null) cachedStacks.clear();
    }

    // ==================== AE storage ====================

    @Override
    public void mountInventories(IStorageMounts storageMounts) {
        // Lowest priority: this must never win over real storage for anything.
        storageMounts.mount(this, Integer.MAX_VALUE - 1);
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return what instanceof AEItemKey itemKey && isOwnWrapper(itemKey);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !(what instanceof AEItemKey itemKey) || !isOwnWrapper(itemKey)) return 0;
        return amount;
    }

    private static boolean isOwnWrapper(AEItemKey itemKey) {
        ItemStack stack = itemKey.getReadOnlyStack();
        return GTLItems.VIRTUAL_INGREDIENT.isIn(stack) && VirtualIngredientBehavior.isMarked(stack);
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        // Never decrements: wrappers are tokens, and the payload is not reachable from here.
        if (amount <= 0 || !(what instanceof AEItemKey itemKey) || !isOwnWrapper(itemKey)) return 0;
        if (published.containsKey(what)) return amount;
        // Old patterns or manually configured wrappers can retain empty item/fluid
        // UI tags and different payload counts. Accept the same sealed payload;
        // preserve the payload's own NBT, and never supply an editable wrapper.
        ItemStack canonical = VirtualIngredientBehavior.canonicalStack(itemKey.getReadOnlyStack());
        return !canonical.isEmpty() && published.containsKey(AEItemKey.of(canonical)) ? amount : 0;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (var entry : Object2LongMaps.fastIterable(published)) {
            out.add(entry.getKey(), entry.getLongValue());
        }
    }

    @Override
    public KeyCounter getAvailableStacks() {
        if (cachedStacks == null) {
            cachedStacks = new KeyCounter();
            dirty = true;
        }
        if (dirty) {
            cachedStacks.clear();
            getAvailableStacks(cachedStacks);
            cachedStacks.removeEmptySubmaps();
            dirty = false;
        }
        return cachedStacks;
    }

    @Override
    public Component getDescription() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    // ==================== Grid ====================

    @Override
    public IManagedGridNode getMainNode() {
        return nodeHolder.getMainNode();
    }

    @Override
    public boolean isOnline() {
        return isOnline;
    }

    @Override
    public void setOnline(boolean online) {
        this.isOnline = online;
    }

    // ==================== Persistence ====================

    @Override
    public void onRotated(@NotNull Direction oldFacing, @NotNull Direction newFacing) {
        super.onRotated(oldFacing, newFacing);
        if (!getHolder().self().getPersistentData().getBoolean("isAllFacing")) {
            getMainNode().setExposedOnSides(EnumSet.of(newFacing));
        }
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.getCompound("ForgeData").getBoolean("isAllFacing")) {
            getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
        }
    }

    // Runs before saveToItem, which then stores the emptied inventory, so the material drops once rather than twice.
    @Override
    public void onDrops(List<ItemStack> drops) {
        clearInventory(inventory.storage);
    }

    @Override
    public void loadFromItem(CompoundTag tag) {
        inventory.storage.deserializeNBT(tag.getCompound("inventory"));
    }

    @Override
    public void saveToItem(CompoundTag tag) {
        tag.put("inventory", inventory.storage.serializeNBT());
    }

    @Override
    @NotNull
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ==================== GUI ====================

    @Override
    public ModularUI createUI(Player player) {
        int width = 181;
        ModularUI ui = new ModularUI(width, 244, this, player)
                .background(GuiTextures.BACKGROUND)
                .widget(new LabelWidget(5, 5, () -> Component.translatable(
                        "block.gtceu.virtual_ingredient_supply_machine").getString()))
                .widget(UITemplate.bindPlayerInventory(player.getInventory(), GuiTextures.SLOT, 7, 162, true));

        var scroll = new DraggableScrollableWidgetGroup(4, 4, width - 13, 130)
                .setYBarStyle(GuiTextures.BACKGROUND_INVERSE, GuiTextures.BUTTON)
                .setYScrollBarWidth(4);

        int x = 0;
        int y = 0;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            scroll.addWidget(new SlotWidget(inventory.storage, slot, x * SLOT_SIZE, y * SLOT_SIZE) {

                @Override
                public boolean isEnabled() {
                    // Default disables slots scrolled out of view, which makes them silently reject insertion.
                    return true;
                }
            }.setBackgroundTexture(GuiTextures.SLOT));
            if (++x == COLS) {
                x = 0;
                y++;
            }
        }

        return ui.widget(new WidgetGroup(3, 17, width - 6, 140).addWidget(scroll));
    }
}
