package org.gtlcore.gtlcore.common.structure.material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * 材料数量表：键 = {@link StructureMaterialType}，值 = 点数/需求量。
 * <p>
 * 仅存储正数条目；银行逻辑见 {@link StructureMaterialOps}；
 * {@link #toArray()} / {@link #fromArray(int[])} 与持久化的 int[] 互通。
 * </p>
 */
public final class StructureMaterialTable {

    private final EnumMap<StructureMaterialType, Integer> map;

    private StructureMaterialTable(EnumMap<StructureMaterialType, Integer> map) {
        this.map = map;
    }

    public static StructureMaterialTable empty() {
        return new StructureMaterialTable(new EnumMap<>(StructureMaterialType.class));
    }

    public static StructureMaterialTable of(StructureMaterialType type, int amount) {
        StructureMaterialTable t = empty();
        t.set(type, amount);
        return t;
    }

    public static StructureMaterialTable fromArray(int[] arr) {
        StructureMaterialTable t = empty();
        if (arr == null) return t;
        for (StructureMaterialType type : StructureMaterialType.values()) {
            int i = type.index();
            if (i < arr.length) {
                t.set(type, arr[i]);
            }
        }
        return t;
    }

    /** 按下标数组（长度 = 枚举数），缺省为 0。 */
    public int[] toArray() {
        int[] arr = new int[StructureMaterialType.count()];
        for (var e : map.entrySet()) {
            arr[e.getKey().index()] = e.getValue();
        }
        return arr;
    }

    public int get(StructureMaterialType type) {
        Objects.requireNonNull(type, "type");
        return map.getOrDefault(type, 0);
    }

    /** 设为 amount；≤0 则移除条目。 */
    public StructureMaterialTable set(StructureMaterialType type, int amount) {
        Objects.requireNonNull(type, "type");
        if (amount > 0) {
            map.put(type, amount);
        } else {
            map.remove(type);
        }
        return this;
    }

    public StructureMaterialTable add(StructureMaterialType type, int delta) {
        if (delta == 0) return this;
        return set(type, get(type) + delta);
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * UI 可见类型：本表或 other 任一为正（存量 ∪ 需求）。
     */
    public List<StructureMaterialType> visibleWith(StructureMaterialTable other) {
        List<StructureMaterialType> list = new ArrayList<>();
        for (StructureMaterialType type : StructureMaterialType.values()) {
            int a = get(type);
            int b = other != null ? other.get(type) : 0;
            if (a > 0 || b > 0) list.add(type);
        }
        return list;
    }

    /** 是否覆盖 cost 全部需求。 */
    public boolean covers(StructureMaterialTable cost) {
        Objects.requireNonNull(cost, "cost");
        for (var e : cost.map.entrySet()) {
            if (get(e.getKey()) < e.getValue()) return false;
        }
        return true;
    }

    /** 扣除 cost（调用前应 {@link #covers}）。 */
    public void deduct(StructureMaterialTable cost) {
        Objects.requireNonNull(cost, "cost");
        for (var e : cost.map.entrySet()) {
            add(e.getKey(), -e.getValue());
        }
    }

    public StructureMaterialTable copy() {
        return new StructureMaterialTable(new EnumMap<>(map));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StructureMaterialTable that)) return false;
        return map.equals(that.map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return "StructureMaterialTable" + map;
    }
}
