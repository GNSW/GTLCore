package org.gtlcore.gtlcore.integration.ae2.storage;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.integration.ae2.FastInfinityCell;
import org.gtlcore.gtlcore.integration.ae2.InfinityWaterCellData;
import org.gtlcore.gtlcore.utils.StorageManager;
import org.gtlcore.gtlcore.utils.datastructure.Int128;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.core.AELog;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class FastInfinityCellInventory implements StorageCell, PreciseStorageAmount {

    private final ISaveProvider container;
    private double storedItemCount;
    private Object2ObjectOpenHashMap<AEKey, Int128> storedMap;
    private final ItemStack stack;
    private boolean isPersisted = true;
    private final KeyCounter lists = new KeyCounter();
    private final TerminalSourceIndex displayIndex = new TerminalSourceIndex();
    private final Map<AEKey, BigInteger> displayOverflow = new HashMap<>();

    // Pool
    private final Int128 tempInt128 = Int128.ZERO();
    private final Int128 sumInt128 = Int128.ZERO();

    public FastInfinityCellInventory(ItemStack stack, ISaveProvider saveProvider) {
        this.stack = stack;
        this.container = saveProvider;
        this.storedMap = null;
        initData();
    }

    private InfinityCellDataStorage getDiskStorage() {
        if (getDiskUUID() != null)
            return getStorageInstance().getOrCreateDisk(getDiskUUID(), true);
        else
            return InfinityCellDataStorage.FAST_EMPTY;
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
        sumInt128.set(0, 0); // 重置为0

        for (var it = storedMap.object2ObjectEntrySet().fastIterator(); it.hasNext();) {
            var entry = it.next();
            var a = entry.getValue();
            if (a.isPositive()) {
                sumInt128.add(a);
                keys.add(entry.getKey().toTagGeneric());
                // 使用LongArrayTag存储高低位，格式为[high, low]
                amount.add(new LongArrayTag(new long[] { a.getHigh(), a.getLow() }));
            }
        }

        if (keys.isEmpty()) {
            getStorageInstance().updateDisk(getDiskUUID(), new InfinityCellDataStorage(true));
        } else {
            getStorageInstance().modifyDisk(getDiskUUID(), keys, amount, sumInt128.doubleValue(), true);
        }

        this.storedItemCount = sumInt128.doubleValue();
        stack.getOrCreateTag().putDouble("count", this.storedItemCount);

        this.isPersisted = true;
    }

    @Override
    public Component getDescription() {
        return null;
    }

    public static FastInfinityCellInventory createInventory(ItemStack stack, ISaveProvider saveProvider) {
        Objects.requireNonNull(stack, "Cannot create cell inventory for null itemstack");

        if (!(stack.getItem() instanceof FastInfinityCell)) {
            return null;
        }

        return new FastInfinityCellInventory(stack, saveProvider);
    }

    public boolean hasDiskUUID() {
        return stack.hasTag() && stack.getOrCreateTag().contains("diskuuid");
    }

    public static boolean hasDiskUUID(ItemStack disk) {
        if (disk.getItem() instanceof FastInfinityCell) {
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

    private static FastInfinityCell getStorageCell(AEItemKey itemKey) {
        if (itemKey.getItem() instanceof FastInfinityCell fastInfinityCell) {
            return fastInfinityCell;
        }

        return null;
    }

    private static boolean isCellEmpty(FastInfinityCellInventory inv) {
        if (inv != null) {
            return inv.getAvailableStacks().isEmpty();
        }
        return true;
    }

    protected Object2ObjectOpenHashMap<AEKey, Int128> getCellItems() {
        if (this.storedMap == null) {
            this.storedMap = new Object2ObjectOpenHashMap<>();
            this.loadCellItems();
        }
        return this.storedMap;
    }

    /** A snapshot for terminal display; never expose the mutable storage counter. */
    public Int128 getDisplayAmount(AEKey key) {
        Int128 amount = getCellItems().get(key);
        return amount == null ? Int128.ZERO() : amount.copy();
    }

    @Override
    public java.math.BigInteger getExactStoredAmount(AEKey key) {
        return getDisplayAmount(key).toBigInteger();
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        this.getCellItems();
        if (TerminalDisplayRead.tracks(out)) TerminalDisplayRead.append(out, lists, displayOverflow, displayIndex);
        else out.addAll(lists);
    }

    private void updateDisplayAmount(AEKey key, Int128 amount) {
        long nativeAmount = amount.longValue();
        lists.set(key, nativeAmount);
        if (nativeAmount == Long.MAX_VALUE || nativeAmount == Long.MIN_VALUE) {
            BigInteger exact = amount.toBigInteger();
            if (exact.bitLength() >= 64) displayOverflow.put(key, exact);
            else displayOverflow.remove(key);
        } else displayOverflow.remove(key);
        displayIndex.set(key, nativeAmount, displayOverflow.get(key));
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
            var amountTag = amounts.get(i);
            var key = AEKey.fromTagGeneric(stackKeys.getCompound(i));
            if (key == null || !(amountTag instanceof LongArrayTag longArrayTag)) corruptedTag = true;
            else {
                var longArray = longArrayTag.getAsLongArray();
                var count = new Int128(longArray[0], longArray[1]);
                var total = count;
                if (key instanceof AEItemKey itemKey && InfinityWaterCellData.isCell(itemKey.getItem()) && storedMap.containsKey(key)) {
                    total = storedMap.get(key).copy().add(count);
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
        if (amount == 0 || what == null) {
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
            getStorageInstance().getOrCreateDisk(getDiskUUID(), true);
            loadCellItems();
        }

        if (mode == Actionable.MODULATE) {
            tempInt128.set(0, amount); // 复用临时对象
            Int128 newValue = getCellItems().compute(what, (k, v) -> {
                if (v == null) {
                    return new Int128(0, amount);
                } else {
                    return v.add(tempInt128);
                }
            });
            // 直接设置为Int128转换后的long值，而不是累加
            updateDisplayAmount(what, newValue);
            this.saveChanges(amount);
        }

        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        var currentAmount = getCellItems().get(what);
        if (currentAmount == null) {
            return 0L;
        } else if (currentAmount.isPositive()) {
            tempInt128.set(0, amount); // 复用临时对象
            if (currentAmount.compareTo(tempInt128) <= 0) {
                if (mode == Actionable.MODULATE) {
                    this.storedMap.remove(what);
                    lists.remove(what);
                    displayOverflow.remove(what);
                    displayIndex.set(what, 0, null);
                    this.saveChanges(-currentAmount.longValue());
                }
                return currentAmount.longValue();
            } else {
                if (mode == Actionable.MODULATE) {
                    // 直接修改现有对象以减少对象创建
                    currentAmount.subtract(tempInt128);
                    // 直接设置为Int128转换后的long值
                    updateDisplayAmount(what, currentAmount);
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
