package org.gtlcore.gtlcore.common.structure.material;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构材料物品总表：类型 × 档位 → 物品。
 * <p>
 * 类加载时创建全部 9 个物品（延迟注册），Item 反查表在首次 {@link #lookup} 时惰性构建
 * （此时注册表已就绪）。
 * </p>
 */
public final class StructureMaterials {

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, GTLCore.MOD_ID);

    @SuppressWarnings("unchecked")
    private static final RegistryObject<Item>[][] TABLE = new RegistryObject[StructureMaterialType.count()][StructureMaterialTier.count()];

    /** Item → 条目；注册完成前为空，首次查询时填充。 */
    private static volatile Map<Item, MaterialsEntry> lookupByItem;

    static {
        for (StructureMaterialType type : StructureMaterialType.values()) {
            for (StructureMaterialTier tier : StructureMaterialTier.values()) {
                String id = "structure_component_" + type.getSerializedName() + "_" + tier.getSerializedName();
                TABLE[type.index()][tier.index()] = ITEMS.register(id, () -> new Item(new Item.Properties()));
            }
        }
    }

    private StructureMaterials() {}

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    public record MaterialsEntry(StructureMaterialType type, StructureMaterialTier tier, int points, Item item) {}

    public static RegistryObject<Item> entry(StructureMaterialType type, StructureMaterialTier tier) {
        return TABLE[type.index()][tier.index()];
    }

    public static Item item(StructureMaterialType type, StructureMaterialTier tier) {
        return entry(type, tier).get();
    }

    public static MaterialsEntry lookup(Item item) {
        if (item == null) return null;
        return ensureLookup().get(item);
    }

    public static List<MaterialsEntry> unloadEntries(StructureMaterialType type) {
        List<MaterialsEntry> list = new ArrayList<>(StructureMaterialTier.count());
        for (StructureMaterialTier tier : StructureMaterialTier.unloadOrder()) {
            list.add(new MaterialsEntry(type, tier, tier.getPoints(), item(type, tier)));
        }
        return list;
    }

    public static int bankSize() {
        return StructureMaterialType.count();
    }

    public static int[] newBankArray() {
        return new int[bankSize()];
    }

    private static Map<Item, MaterialsEntry> ensureLookup() {
        Map<Item, MaterialsEntry> cached = lookupByItem;
        if (cached != null) {
            return cached;
        }
        synchronized (StructureMaterials.class) {
            if (lookupByItem != null) {
                return lookupByItem;
            }
            Map<Item, MaterialsEntry> map = new HashMap<>();
            for (StructureMaterialType type : StructureMaterialType.values()) {
                for (StructureMaterialTier tier : StructureMaterialTier.values()) {
                    RegistryObject<Item> e = TABLE[type.index()][tier.index()];
                    if (e == null) continue;
                    Item it = e.get();
                    if (it == null) continue;
                    map.put(it, new MaterialsEntry(type, tier, tier.getPoints(), it));
                }
            }
            // 仅在全部条目解析成功后缓存；否则下次再试
            if (map.size() == StructureMaterialType.count() * StructureMaterialTier.count()) {
                lookupByItem = Collections.unmodifiableMap(map);
                return lookupByItem;
            }
            return map;
        }
    }
}
