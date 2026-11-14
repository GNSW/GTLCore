package org.gtlcore.gtlcore.common.structure.material;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.ExtraMaterial;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.Structure;

import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 结构材料银行与装卸槽的纯逻辑（无 BlockEntity 依赖）。
 * <p>
 * 银行存点数（{@link StructureMaterialTable} / int[]），槽内存组件与额外物品。
 * 放置前先 {@link #isAdequate}，再 {@link #consume}（尽量原子：先规划再写入）。
 * </p>
 */
public final class StructureMaterialOps {

    private StructureMaterialOps() {}

    public static int[] ensureBankSize(int[] bank) {
        int n = StructureMaterials.bankSize();
        if (bank != null && bank.length == n) {
            return bank;
        }
        int[] next = StructureMaterials.newBankArray();
        if (bank != null) {
            System.arraycopy(bank, 0, next, 0, Math.min(bank.length, n));
        }
        return next;
    }

    public static StructureMaterialTable tableOf(int[] bank) {
        return StructureMaterialTable.fromArray(ensureBankSize(bank));
    }

    public static Map<Item, Integer> countSlots(ItemStackTransfer inv) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /**
     * 银行 + 额外物品是否覆盖结构需求。
     */
    public static boolean isAdequate(
                                     StructureMaterialTable bank,
                                     Structure structure,
                                     ItemStackTransfer materialSlots) {
        if (!bank.covers(structure.materials())) {
            return false;
        }
        if (structure.extraMaterials().isEmpty()) {
            return true;
        }
        Map<Item, Integer> counts = countSlots(materialSlots);
        for (ExtraMaterial extra : structure.extraMaterials()) {
            if (extra.amount() <= 0) continue;
            if (counts.getOrDefault(extra.getItem(), 0) < extra.amount()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 槽内结构组件 → 银行点数；返回更新后的银行表（调用方写回 int[]）。
     */
    public static StructureMaterialTable loadFromSlots(
                                                       StructureMaterialTable bank,
                                                       ItemStackTransfer inv) {
        StructureMaterialTable result = bank.copy();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            StructureMaterials.MaterialsEntry e = StructureMaterials.lookup(stack.getItem());
            if (e == null) continue;
            result.add(e.type(), e.points() * stack.getCount());
            inv.setStackInSlot(i, ItemStack.EMPTY);
        }
        return result;
    }

    /**
     * 银行点数 → 空槽兑回组件（高档优先）；返回更新后的银行表。
     */
    public static StructureMaterialTable unloadToSlots(
                                                       StructureMaterialTable bank,
                                                       ItemStackTransfer inv) {
        StructureMaterialTable result = bank.copy();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) continue;
            boolean filled = false;
            for (StructureMaterialType type : StructureMaterialType.values()) {
                if (filled) break;
                for (StructureMaterials.MaterialsEntry e : StructureMaterials.unloadEntries(type)) {
                    int count = Math.min(result.get(e.type()) / e.points(), 64);
                    if (count > 0) {
                        inv.setStackInSlot(i, new ItemStack(e.item(), count));
                        result.add(e.type(), -e.points() * count);
                        filled = true;
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * 扣除银行与额外物品。调用前应已 {@link #isAdequate}。
     * <p>
     * 先规划额外物品各槽扣减量，再一次性应用银行与槽变更，避免半扣状态。
     * </p>
     *
     * @return 新的银行表；失败返回 null（不修改槽与银行表内容——槽仅在成功时写入）
     */
    public static StructureMaterialTable consume(
                                                 StructureMaterialTable bank,
                                                 Structure structure,
                                                 ItemStackTransfer inv) {
        if (!isAdequate(bank, structure, inv)) {
            return null;
        }

        int size = inv.getSlots();
        int[] takePerSlot = new int[size];

        for (ExtraMaterial extra : structure.extraMaterials()) {
            int remaining = extra.amount();
            if (remaining <= 0) continue;
            Item item = extra.getItem();
            for (int i = 0; i < size && remaining > 0; i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.getItem() != item) continue;
                int available = stack.getCount() - takePerSlot[i];
                if (available <= 0) continue;
                int take = Math.min(available, remaining);
                takePerSlot[i] += take;
                remaining -= take;
            }
            if (remaining > 0) {
                GTLCore.LOGGER.error(
                        "Failed to plan extra material consume for structure {}: {} x{}",
                        structure.name(), item, extra.amount());
                return null;
            }
        }

        StructureMaterialTable next = bank.copy();
        next.deduct(structure.materials());

        for (int i = 0; i < size; i++) {
            int take = takePerSlot[i];
            if (take <= 0) continue;
            ItemStack stack = inv.getStackInSlot(i).copy();
            stack.shrink(take);
            inv.setStackInSlot(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
        }
        return next;
    }
}
