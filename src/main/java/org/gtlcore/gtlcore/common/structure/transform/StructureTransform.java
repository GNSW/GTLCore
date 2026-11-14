package org.gtlcore.gtlcore.common.structure.transform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 结构坐标与 BlockState 的旋转/镜像变换。
 * <p>
 * 放置：先绕 Y 旋转，再 X/Z 镜像；导出采样使用与之配对的逆旋转。
 * </p>
 */
public final class StructureTransform {

    private StructureTransform() {}

    /**
     * pattern 局部 (lx,ly,lz) → 变换后局部坐标（未加原点贴齐偏移）。
     */
    public static StructureLocalPos transformCoords(
                                                    int lx, int ly, int lz,
                                                    int sizeX, int sizeZ,
                                                    int rotation,
                                                    boolean xMirror, boolean zMirror) {
        int rx = lx;
        int rz = lz;
        boolean swapped = normalizeRotation(rotation) == 90 || normalizeRotation(rotation) == 270;
        switch (normalizeRotation(rotation)) {
            case 90 -> {
                int t = rx;
                rx = sizeZ - 1 - rz;
                rz = t;
            }
            case 180 -> {
                rx = sizeX - 1 - rx;
                rz = sizeZ - 1 - rz;
            }
            case 270 -> {
                int t = rx;
                rx = rz;
                rz = sizeX - 1 - t;
            }
            default -> {}
        }
        // 旋转后 X/Z 包围盒尺寸可能互换，镜像必须按旋转后的尺寸取反
        int mirrorX = swapped ? sizeZ : sizeX;
        int mirrorZ = swapped ? sizeX : sizeZ;
        if (xMirror) rx = mirrorX - 1 - rx;
        if (zMirror) rz = mirrorZ - 1 - rz;
        return StructureLocalPos.of(rx, ly, rz);
    }

    /**
     * 导出时：输出网格 (outX,outZ) → 原始包围盒内的 XZ 采样坐标
     * （与 {@link #transformCoords} 配对的逆映射）。
     */
    public static StructureLocalPos exportSampleXZ(
                                                   int outX, int outZ,
                                                   int outDx, int outDz,
                                                   int rotation,
                                                   boolean xMirror, boolean zMirror) {
        int rx = outX;
        int rz = outZ;
        int rot = normalizeRotation(rotation);
        switch (rot) {
            case 90 -> {
                int t = rx;
                rx = outDz - 1 - rz;
                rz = t;
            }
            case 180 -> {
                rx = outDx - 1 - rx;
                rz = outDz - 1 - rz;
            }
            case 270 -> {
                int t = rx;
                rx = rz;
                rz = outDx - 1 - t;
            }
            default -> {}
        }
        // 旋转后 X/Z 包围盒尺寸可能互换，镜像按旋转后的尺寸取反（与放置变换一致）
        boolean swapped = rot == 90 || rot == 270;
        int mirrorX = swapped ? outDz : outDx;
        int mirrorZ = swapped ? outDx : outDz;
        if (xMirror) rx = mirrorX - 1 - rx;
        if (zMirror) rz = mirrorZ - 1 - rz;
        return StructureLocalPos.xz(rx, rz);
    }

    /**
     * 导出时的状态变换：先镜像，再做与放置配对的逆旋转。
     * <p>
     * 放置为 {@code M(R(state))}，因此导出取 {@code R⁻¹(M(state))}，两者复合为恒等。
     * </p>
     */
    public static BlockState transformStateForExport(BlockState original, int rotation,
                                                     boolean xMirror, boolean zMirror) {
        return applyMirror(original, xMirror, zMirror).rotate(exportRotation(rotation));
    }

    public static BlockState transformStateForPlace(BlockState original, int rotation,
                                                    boolean xMirror, boolean zMirror) {
        return applyMirror(original.rotate(placeRotation(rotation)), xMirror, zMirror);
    }

    private static Rotation placeRotation(int rotation) {
        return switch (normalizeRotation(rotation)) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static Rotation exportRotation(int rotation) {
        return switch (normalizeRotation(rotation)) {
            case 90 -> Rotation.COUNTERCLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static BlockState applyMirror(BlockState state, boolean xMirror, boolean zMirror) {
        if (xMirror && zMirror) {
            return state.mirror(Mirror.LEFT_RIGHT).mirror(Mirror.FRONT_BACK);
        }
        if (xMirror) {
            return state.mirror(Mirror.FRONT_BACK);
        }
        if (zMirror) {
            return state.mirror(Mirror.LEFT_RIGHT);
        }
        return state;
    }

    /**
     * 旋转/镜像后包围盒贴齐 startPos 所需的局部原点偏移（-min）。
     */
    public static StructureLocalPos calcOriginOffset(
                                                     int sizeX, int sizeY, int sizeZ,
                                                     int rotation,
                                                     boolean xMirror, boolean zMirror) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (StructureLocalPos corner : corners(sizeX, sizeY, sizeZ)) {
            StructureLocalPos t = transformCoords(
                    corner.x(), corner.y(), corner.z(), sizeX, sizeZ, rotation, xMirror, zMirror);
            minX = Math.min(minX, t.x());
            minY = Math.min(minY, t.y());
            minZ = Math.min(minZ, t.z());
        }
        return StructureLocalPos.of(-minX, -minY, -minZ);
    }

    /** 变换后世界包围盒 [min, max]（含两端）。 */
    public static BlockPosBounds transformedBounds(
                                                   BlockPos start,
                                                   int sizeX, int sizeY, int sizeZ,
                                                   int rotation,
                                                   boolean xMirror, boolean zMirror) {
        StructureLocalPos off = calcOriginOffset(sizeX, sizeY, sizeZ, rotation, xMirror, zMirror);
        int minWX = Integer.MAX_VALUE, minWY = Integer.MAX_VALUE, minWZ = Integer.MAX_VALUE;
        int maxWX = Integer.MIN_VALUE, maxWY = Integer.MIN_VALUE, maxWZ = Integer.MIN_VALUE;
        for (StructureLocalPos corner : corners(sizeX, sizeY, sizeZ)) {
            StructureLocalPos t = transformCoords(
                    corner.x(), corner.y(), corner.z(), sizeX, sizeZ, rotation, xMirror, zMirror);
            int wx = start.getX() + t.x() + off.x();
            int wy = start.getY() + t.y() + off.y();
            int wz = start.getZ() + t.z() + off.z();
            minWX = Math.min(minWX, wx);
            maxWX = Math.max(maxWX, wx);
            minWY = Math.min(minWY, wy);
            maxWY = Math.max(maxWY, wy);
            minWZ = Math.min(minWZ, wz);
            maxWZ = Math.max(maxWZ, wz);
        }
        return new BlockPosBounds(
                new BlockPos(minWX, minWY, minWZ),
                new BlockPos(maxWX, maxWY, maxWZ));
    }

    /** 规范化到 {0,90,180,270}。 */
    public static int normalizeRotation(int rotation) {
        int r = rotation % 360;
        if (r < 0) r += 360;
        return switch (r) {
            case 90, 180, 270 -> r;
            default -> 0;
        };
    }

    private static StructureLocalPos[] corners(int sx, int sy, int sz) {
        int x1 = Math.max(0, sx - 1);
        int y1 = Math.max(0, sy - 1);
        int z1 = Math.max(0, sz - 1);
        return new StructureLocalPos[] {
                StructureLocalPos.of(0, 0, 0), StructureLocalPos.of(x1, 0, 0),
                StructureLocalPos.of(0, y1, 0), StructureLocalPos.of(x1, y1, 0),
                StructureLocalPos.of(0, 0, z1), StructureLocalPos.of(x1, 0, z1),
                StructureLocalPos.of(0, y1, z1), StructureLocalPos.of(x1, y1, z1)
        };
    }

    public record BlockPosBounds(BlockPos min, BlockPos max) {}
}
