package org.gtlcore.gtlcore.common.structure.material;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/**
 * 结构材料类型。
 * <p>
 * 现在只保留框架组件；{@link #index()}（ordinal）作银行/需求数组下标，序列化名用于存档与物品 id 片段。
 * </p>
 */
public enum StructureMaterialType implements StringRepresentable {

    FRAME("frame", "框架", 0xaa66fd3c);

    /** 材料折算比例：多少方块算 1 点。 */
    public static final int BLOCKS_PER_POINT = 1;

    private final String name;
    private final String nameCn;
    private final int color;

    StructureMaterialType(String name, String nameCn, int color) {
        this.name = name;
        this.nameCn = nameCn;
        this.color = color;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public String getNameCn() {
        return nameCn;
    }

    public int getColor() {
        return color;
    }

    public int index() {
        return ordinal();
    }

    public String langKey() {
        return "ui.quantum_chromodynamic_charge.structure.material.type." + name;
    }

    public Component displayName() {
        return Component.translatable(langKey());
    }

    /**
     * 方块数 -> 材料点数：每 {@value #BLOCKS_PER_POINT} 方块 1 点（不足也按 1 点），
     * 再向上取整到 10 的倍数。
     */
    public static int pointsForBlocks(int blocks) {
        if (blocks <= 0) return 0;
        int points = (blocks + BLOCKS_PER_POINT - 1) / BLOCKS_PER_POINT;
        return (points + 9) / 10 * 10;
    }

    public static int count() {
        return values().length;
    }

    public static StructureMaterialType of(int index) {
        StructureMaterialType[] all = values();
        if (index < 0 || index >= all.length) {
            throw new IllegalArgumentException("unknown StructureMaterialType index: " + index);
        }
        return all[index];
    }
}
