package org.gtlcore.gtlcore.common.structure.transform;

/**
 * 结构局部坐标（pattern 空间或变换后的局部偏移），避免到处传 {@code int[]}。
 */
public record StructureLocalPos(int x, int y, int z) {

    public static StructureLocalPos of(int x, int y, int z) {
        return new StructureLocalPos(x, y, z);
    }

    public static StructureLocalPos xz(int x, int z) {
        return new StructureLocalPos(x, 0, z);
    }
}
