package org.gtlcore.gtlcore.integration.ae2.storage;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.integration.ae2.InfinityCell;
import org.gtlcore.gtlcore.integration.ae2.InfinityWaterCellData;
import org.gtlcore.gtlcore.utils.NumberUtils;
import org.gtlcore.gtlcore.utils.StorageManager;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.cells.*;
import appeng.core.AELog;
import it.unimi.dsi.fastutil.objects.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class InfinityCellInventory implements StorageCell, PreciseStorageAmount {

    private final ISaveProvider container;
    private final AEKeyType keyType;
    private double storedItemCount;
    private Object2ObjectOpenHashMap<AEKey, BigInteger> storedMap;
    private final ItemStack stack;
    private boolean isPersisted = true;
    private final KeyCounter lists = new KeyCounter();
    private final TerminalSourceIndex displayIndex = new TerminalSourceIndex();
    private final Map<AEKey, BigInteger> displayOverflow = new HashMap<>();

    public InfinityCellInventory(AEKeyType keyType, ItemStack stack, ISaveProvider saveProvider) {
        this.stack = stack;
        this.container = saveProvider;
        this.keyType = keyType;
        this.storedMap = null;
        initData();
    }

    private InfinityCellDataStorage getDiskStorage() {
        if (getDiskUUID() != null)
            return getStorageInstance().getOrCreateDisk(getDiskUUID(), false);
        else
            return InfinityCellDataStorage.EMPTY;
    }

    private void initData() {
        if (hasDiskUUID()) {
            this.storedItemCount = getDiskStorage().totalAmount;
        } else {
            this.storedItemCount = 0;
            getCellItems();
        }
    }

    @Override
    public CellState getStatus() {
        if (this.storedItemCount == 0) return CellState.EMPTY;
        return CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void persist() {
        if (this.isPersisted) {
            return;
        }

        if (storedItemCount == 0) {
            if (hasDiskUUID()) {
                getStorageInstance().removeDisk(getDiskUUID());
                if (stack.getTag() != null) {
                    stack.getTag().remove("diskuuid");
                    stack.getTag().remove("count");
                }
                initData();
            }
            return;
        }
        var keys = new ListTag();
        var amount = new ListTag();
        var count = BigInteger.ZERO;

        for (var it = storedMap.object2ObjectEntrySet().fastIterator(); it.hasNext();) {
            var entry = it.next();
            var a = entry.getValue();
            if (a.compareTo(BigInteger.ZERO) > 0) {
                count = count.add(a);
                keys.add(entry.getKey().toTagGeneric());
                amount.add(StringTag.valueOf(a.toString()));
            }
        }

        if (keys.isEmpty()) {
            getStorageInstance().updateDisk(getDiskUUID(), new InfinityCellDataStorage(false));
        } else {
            getStorageInstance().modifyDisk(getDiskUUID(), keys, amount, count.doubleValue(), false);
        }

        this.storedItemCount = count.doubleValue();
        stack.getOrCreateTag().putDouble("count", this.storedItemCount);

        this.isPersisted = true;
    }

    @Override
    public Component getDescription() {
        return null;
    }

    public static InfinityCellInventory createInventory(ItemStack stack, ISaveProvider saveProvider) {
        Objects.requireNonNull(stack, "Cannot create cell inventory for null itemstack");

        if (!(stack.getItem() instanceof InfinityCell cellType)) {
            return null;
        }

        return new InfinityCellInventory(cellType.getKeyType(), stack, saveProvider);
    }

    public boolean hasDiskUUID() {
        return stack.hasTag() && stack.getOrCreateTag().contains("diskuuid");
    }

    public static boolean hasDiskUUID(ItemStack disk) {
        if (disk.getItem() instanceof InfinityCell) {
            return disk.hasTag() && disk.getOrCreateTag().contains("diskuuid");
        }
        return false;
    }

    public UUID getDiskUUID() {
        if (hasDiskUUID())
            return stack.getOrCreateTag().getUUID("diskuuid");
        else
            return null;
    }

    private boolean isStorageCell(AEItemKey key) {
        var type = getStorageCell(key);
        return type != null;
    }

    private static InfinityCell getStorageCell(AEItemKey itemKey) {
        if (itemKey.getItem() instanceof InfinityCell infinityCell) {
            return infinityCell;
        }

        return null;
    }

    private static boolean isCellEmpty(InfinityCellInventory inv) {
        if (inv != null) {
            return inv.getAvailableStacks().isEmpty();
        }
        return true;
    }

    protected Object2ObjectOpenHashMap<AEKey, BigInteger> getCellItems() {
        if (this.storedMap == null) {
            this.storedMap = new Object2ObjectOpenHashMap<>();
            this.loadCellItems();
        }
        return this.storedMap;
    }

    @Override
    public BigInteger getExactStoredAmount(AEKey key) {
        return getCellItems().getOrDefault(key, BigInteger.ZERO);
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        this.getCellItems();
        if (TerminalDisplayRead.tracks(out)) TerminalDisplayRead.append(out, lists, displayOverflow, displayIndex);
        else out.addAll(lists);
    }

    private void updateDisplayAmount(AEKey key, BigInteger amount) {
        lists.set(key, NumberUtils.getLongValue(amount));
        if (amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 || amount.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) displayOverflow.put(key, amount);
        else displayOverflow.remove(key);
        displayIndex.set(key, lists.get(key), displayOverflow.get(key));
    }

    private void loadCellItems() {
        boolean corruptedTag = false;

        if (!stack.hasTag()) {
            return;
        }

        var amounts = getDiskStorage().amounts;
        var stackKeys = getDiskStorage().stackKeys;
        if (amounts.size() != stackKeys.size()) {
            AELog.warn("Loading storage cell with mismatched amounts/tags: %d != %d", amounts.size(), stackKeys.size());
        }

        for (int i = 0; i < amounts.size(); i++) {
            var amount = amounts.getString(i);
            var key = AEKey.fromTagGeneric(stackKeys.getCompound(i));
            if (amount.isEmpty() || key == null) corruptedTag = true;
            else {
                var count = new BigInteger(amount);
                var total = count;
                if (key instanceof AEItemKey itemKey && InfinityWaterCellData.isCell(itemKey.getItem())) {
                    total = storedMap.getOrDefault(key, BigInteger.ZERO).add(count);
                }
                storedMap.put(key, total);
                updateDisplayAmount(key, total);
                this.storedItemCount += count.doubleValue();
            }
        }

        if (corruptedTag) {
            this.saveChanges(0);
        }
    }

    protected void saveChanges(double incur) {
        this.storedItemCount += incur;

        this.isPersisted = false;
        if (this.container != null) this.container.saveChanges();
        else this.persist();
    }

    private StorageManager getStorageInstance() {
        return GTLCore.STORAGE_INSTANCE;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount == 0 || !keyType.contains(what)) {
            return 0;
        }

        if (what instanceof AEItemKey itemKey && this.isStorageCell(itemKey)) {
            var meInventory = createInventory(itemKey.toStack(), null);
            if (!isCellEmpty(meInventory)) {
                return 0;
            }
        }

        if (!hasDiskUUID()) {
            stack.getOrCreateTag().putUUID("diskuuid", UUID.randomUUID());
            getStorageInstance().getOrCreateDisk(getDiskUUID(), false);
            loadCellItems();
        }

        if (mode == Actionable.MODULATE) {
            BigInteger finalAmount = BigInteger.valueOf(amount);
            BigInteger updated = getCellItems().compute(what, (k, v) -> v == null ? finalAmount : v.add(finalAmount));
            updateDisplayAmount(what, updated);
            this.saveChanges(amount);
        }

        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        var currentAmount = getCellItems().get(what);
        if (currentAmount == null) {
            return 0L;
        } else if (currentAmount.signum() > 0) {
            var extractAmount = BigInteger.valueOf(amount);
            if (currentAmount.compareTo(extractAmount) < 1) {
                if (mode == Actionable.MODULATE) {
                    this.storedMap.remove(what);
                    lists.remove(what);
                    displayOverflow.remove(what);
                    displayIndex.set(what, 0, null);
                    this.saveChanges(-amount);
                }
                return currentAmount.longValue();
            } else {
                if (mode == Actionable.MODULATE) {
                    var sub = currentAmount.subtract(extractAmount);
                    this.storedMap.put(what, sub);
                    updateDisplayAmount(what, sub);
                    this.saveChanges(-amount);
                }
                return amount;
            }
        } else {
            return 0L;
        }
    }

    public double getNbtItemCount() {
        if (hasDiskUUID()) {
            if (stack.getTag() != null) {
                return stack.getTag().getDouble("count");
            }
        }
        return 0;
    }
}
