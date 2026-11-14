package org.gtlcore.gtlcore.common.structure.export;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.structure.io.StructureFileIO;
import org.gtlcore.gtlcore.common.structure.io.StructureMappingIO;
import org.gtlcore.gtlcore.common.structure.transform.StructureLocalPos;
import org.gtlcore.gtlcore.common.structure.transform.StructureTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import it.unimi.dsi.fastutil.chars.CharOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2CharLinkedOpenHashMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将世界区域导出为结构图案 + 方块映射（分 tick 采样，避免卡死服务端）。
 * 输出目录：{@code logs/platform/<timestamp>.{mbs,json}}。
 */
public final class StructureExporter {

    private static final CharOpenHashSet ILLEGAL_CHARS = new CharOpenHashSet();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final long PROGRESS_THRESHOLD = 1_000_000L;

    static {
        for (char c : new char[] { '.', '(', ')', ',', '/', '\\', '"', '\'', '`' }) {
            ILLEGAL_CHARS.add(c);
        }
        for (int i = Character.MIN_VALUE; i <= Character.MAX_VALUE; i++) {
            char c = (char) i;
            if (Character.isISOControl(c)) {
                ILLEGAL_CHARS.add(c);
            }
        }
    }

    private StructureExporter() {}

    /** 一次区域导出任务；每 tick 采样若干格，完成后写盘。 */
    public static final class ExportTask {

        private final ServerLevel level;
        private final BlockPos min;
        private final int dx, dy, dz;
        private final int outDx, outDz;
        private final int rotation;
        private final boolean xMirror;
        private final boolean zMirror;

        private final Reference2CharLinkedOpenHashMap<BlockState> stateToChar = new Reference2CharLinkedOpenHashMap<>();
        private char nextChar = nextValid('A');
        private final Map<ChunkPos, LevelChunk> chunkCache = new HashMap<>();
        private final List<String[]> zSlices;
        private final List<String> ySlices = new ArrayList<>();
        private final StringBuilder row;
        private final long total;
        private long progress;

        private int outZ;
        private int outY;
        private int outX;
        private boolean finished;
        private boolean failed;
        private String savedFileName = "";
        private String failureMessage = "";

        public ExportTask(ServerLevel level, BlockPos pos1, BlockPos pos2,
                          boolean xMirror, boolean zMirror, int rotation) {
            this.level = level;
            this.rotation = StructureTransform.normalizeRotation(rotation);
            this.xMirror = xMirror;
            this.zMirror = zMirror;

            int minX = Math.min(pos1.getX(), pos2.getX());
            int minY = Math.min(pos1.getY(), pos2.getY());
            int minZ = Math.min(pos1.getZ(), pos2.getZ());
            int maxX = Math.max(pos1.getX(), pos2.getX());
            int maxY = Math.max(pos1.getY(), pos2.getY());
            int maxZ = Math.max(pos1.getZ(), pos2.getZ());
            this.min = new BlockPos(minX, minY, minZ);
            this.dx = maxX - minX + 1;
            this.dy = maxY - minY + 1;
            this.dz = maxZ - minZ + 1;

            boolean swapXZ = this.rotation == 90 || this.rotation == 270;
            this.outDx = swapXZ ? dz : dx;
            this.outDz = swapXZ ? dx : dz;
            this.total = (long) outDx * dy * outDz;

            this.zSlices = new ArrayList<>(outDz);
            this.row = new StringBuilder(outDx);
            stateToChar.put(Blocks.AIR.defaultBlockState(), ' ');
        }

        public int progressPercentage() {
            if (total <= 0) return 100;
            return (int) Math.min(100L, progress * 100L / total);
        }

        public boolean isFinished() {
            return finished || failed;
        }

        public boolean isFailed() {
            return failed;
        }

        /** 导出尺寸（含旋转/镜像后的输出尺寸）。 */
        public int outSizeX() {
            return outDx;
        }

        public int outSizeY() {
            return dy;
        }

        public int outSizeZ() {
            return outDz;
        }

        /** 用到的不重复方块状态数（含空气）。 */
        public int distinctStates() {
            return stateToChar.size();
        }

        /** 成功写盘后的文件名（如 {@code 20261115-015055-643.mbs}）；未完成时为空串。 */
        public String savedFileName() {
            return savedFileName;
        }

        /** 失败原因；未失败时为空串。 */
        public String failureMessage() {
            return failureMessage;
        }

        /** @return true 表示已完成（成功或失败） */
        public boolean tick(int budget) {
            if (finished || failed) return true;
            try {
                int processed = 0;
                while (processed < budget && outZ < outDz) {
                    StructureLocalPos sample = StructureTransform.exportSampleXZ(
                            outX, outZ, outDx, outDz, rotation, xMirror, zMirror);
                    BlockPos pos = min.offset(sample.x(), outY, sample.z());
                    BlockState original = getCached(pos);
                    BlockState transformed = StructureTransform.transformStateForExport(
                            original, rotation, xMirror, zMirror);
                    if (!stateToChar.containsKey(transformed)) {
                        stateToChar.put(transformed, nextChar);
                        nextChar = nextValid((char) (nextChar + 1));
                    }
                    row.append(stateToChar.getChar(transformed));
                    progress++;
                    processed++;

                    if (++outX >= outDx) {
                        outX = 0;
                        ySlices.add(row.toString());
                        row.setLength(0);
                        if (++outY >= dy) {
                            outY = 0;
                            zSlices.add(ySlices.toArray(new String[0]));
                            ySlices.clear();
                            outZ++;
                        }
                    }
                }

                if (progress % PROGRESS_THRESHOLD == 0 && progress > 0) {
                    GTLCore.LOGGER.info("Export progress: {} / {} ({}%)",
                            progress, total, String.format("%.2f", progress * 100.0 / total));
                }

                if (outZ >= outDz) {
                    save();
                    finished = true;
                    return true;
                }
                return false;
            } catch (Exception e) {
                failed = true;
                failureMessage = e.toString();
                GTLCore.LOGGER.error("Structure export failed", e);
                return true;
            }
        }

        private void save() throws Exception {
            Path outDir = Paths.get("logs", "platform");
            Files.createDirectories(outDir);
            String stamp = LocalDateTime.now().format(TIMESTAMP);
            Path structurePath = outDir.resolve(stamp + ".mbs");
            Path mappingPath = outDir.resolve(stamp + ".json");

            StructureFileIO.save(structurePath, zSlices.toArray(new String[0][]));
            StructureMappingIO.save(StructureMappingIO.invert(stateToChar), mappingPath);
            savedFileName = structurePath.getFileName().toString();
            GTLCore.LOGGER.info("Exported structure: {} ({}x{}x{}, {} states)",
                    structurePath.toAbsolutePath(), outDx, dy, outDz, stateToChar.size());
            GTLCore.LOGGER.info("Exported mapping: {}", mappingPath.toAbsolutePath());
        }

        private BlockState getCached(BlockPos pos) {
            ChunkPos cp = new ChunkPos(pos);
            LevelChunk chunk = chunkCache.computeIfAbsent(cp, c -> level.getChunk(c.x, c.z));
            return chunk.getBlockState(pos);
        }
    }

    private static char nextValid(char start) {
        char ch = start;
        while (ILLEGAL_CHARS.contains(ch)) {
            ch++;
        }
        return ch;
    }

    public static void start() {}
}
