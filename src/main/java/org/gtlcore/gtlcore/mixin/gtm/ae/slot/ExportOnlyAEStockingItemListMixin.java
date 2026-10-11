package org.gtlcore.gtlcore.mixin.gtm.ae.slot;

import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IOptimizedMEList;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Objects;

@SuppressWarnings("DuplicatedCode")
@Mixin(targets = "com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine$ExportOnlyAEStockingItemList", remap = false)
public abstract class ExportOnlyAEStockingItemListMixin extends ExportOnlyAEItemList implements IConfigurableSlotList, IOptimizedMEList {

    @Unique
    protected ObjectArrayList<AEItemKey> gTLCore$configList;
    @Unique
    protected IntArrayList gTLCore$configIndexList;

    @Unique
    private static final boolean ENABLE_ULTIMATE_ME_STOCKING = ConfigHolder.INSTANCE.machines.enableUltimateMEStocking;

    @SuppressWarnings("target")
    @Shadow(remap = false)
    @Final
    MEStockingBusPartMachine this$0;

    public ExportOnlyAEStockingItemListMixin(MetaMachine machine, int slots) {
        super(machine, slots);
    }

    @SuppressWarnings("target")
    @Inject(method = "<init>(Lcom/gregtechceu/gtceu/integration/ae2/machine/MEStockingBusPartMachine;Lcom/gregtechceu/gtceu/api/machine/MetaMachine;I)V",
            at = @At("TAIL"))
    private void gtl$onInit(MEStockingBusPartMachine holder, MetaMachine slots, int par3, CallbackInfo ci) {
        gTLCore$configList = new ObjectArrayList<>();
        gTLCore$configIndexList = new IntArrayList();
        for (ExportOnlyAEItemSlot exportOnlyAEItemSlot : inventory) {
            ((IMESlot) exportOnlyAEItemSlot).setOnConfigChanged(this::onConfigChanged);
        }
    }

    @Override
    public void clearInventory(int startIndex) {
        for (int i = startIndex; i < this.getConfigurableSlots(); ++i) {
            IConfigurableSlot slot = this.getConfigurableSlot(i);
            ((IMESlot) slot).setConfigWithoutNotify(null);
            slot.setStock(null);
        }
        onConfigChanged();
    }

    @Override
    public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> left, @Nullable String slotName, boolean simulate) {
        if (io != IO.IN || left.isEmpty()) {
            return left;
        }
        if (!this$0.isWorkingEnabled()) {
            return left;
        }
        IGrid grid = this$0.getMainNode().getGrid();
        if (grid == null) {
            return left;
        }

        MEStorage aeNetwork = grid.getStorageService().getInventory();
        boolean changed = false;
        var listIterator = left.listIterator();

        while (listIterator.hasNext()) {
            Ingredient ingredient = listIterator.next();
            if (ingredient.isEmpty()) {
                listIterator.remove();
            } else {
                long amount;
                if (ingredient instanceof LongIngredient li) amount = li.getActualAmount();
                else if (ingredient instanceof SizedIngredient si) amount = si.getAmount();
                else amount = 1;
                if (amount < 1) listIterator.remove();
                else {
                    for (int i = 0, configListSize = gTLCore$configList.size(); i < configListSize; i++) {
                        AEItemKey aeItemKey = gTLCore$configList.get(i);
                        if (aeItemKey.matches(ingredient)) {
                            long extracted = aeNetwork.extract(aeItemKey, amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE, this$0.getActionSource());
                            if (extracted > 0) {
                                changed = true;
                                amount -= extracted;
                                if (!simulate) {
                                    var slot = this.inventory[gTLCore$configIndexList.getInt(i)];
                                    if (slot.getStock() != null) {
                                        long amt = slot.getStock().amount() - extracted;
                                        if (amt == 0) slot.setStock(null);
                                        else slot.setStock(new GenericStack(aeItemKey, amt));
                                    }
                                }
                            }
                        }
                        if (amount <= 0L) {
                            listIterator.remove();
                            break;
                        }
                    }
                }
            }
        }
        if (!simulate && changed) {
            setChanged(true);
            this.onContentsChanged();
        }

        return left.isEmpty() ? null : left;
    }

    @SuppressWarnings("AddedMixinMembersNamePattern")
    @Override
    public void onConfigChanged() {
        setChanged(true);
        gTLCore$configList.clear();
        gTLCore$configIndexList.clear();
        for (int i = 0, inventoryLength = inventory.length; i < inventoryLength; i++) {
            final var config = inventory[i].getConfig();
            if (config != null && config.what() instanceof AEItemKey key) {
                gTLCore$configList.add(key);
                gTLCore$configIndexList.add(i);
            }
        }
    }

    @SuppressWarnings("AddedMixinMembersNamePattern")
    @Override
    public Object2LongMap<ItemStack> getMEItemMap() {
        final var itemMap = getItemMap();
        if (ENABLE_ULTIMATE_ME_STOCKING || getChanged()) {
            setChanged(false);
            itemMap.clear();
            final MEStorage aeNetwork = Objects.requireNonNull(this$0.getMainNode().getGrid()).getStorageService().getInventory();
            for (var key : gTLCore$configList) {
                long extracted = aeNetwork.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, this$0.getActionSource());
                if (extracted > 0) {
                    itemMap.addTo(key.toStack(), extracted);
                }
            }
        }
        return itemMap.isEmpty() ? null : itemMap;
    }
}
