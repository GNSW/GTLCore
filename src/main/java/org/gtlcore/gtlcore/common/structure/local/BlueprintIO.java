package org.gtlcore.gtlcore.common.structure.local;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import it.unimi.dsi.fastutil.chars.Char2ReferenceOpenHashMap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * 蓝图文件读取：按后缀分派到不同的格式解析器，统一输出「图案 + 调色板」。
 * <p>
 * 支持（与 Litematica 可读取的格式保持一致，另加本模组的单文件格式）：
 * </p>
 * <ul>
 * <li>{@code .gtcpf} — 本模组单文件蓝图（gzip JSON）</li>
 * <li>{@code .litematic} — Litematica 投影（gzip NBT，区域 + 位压缩索引）</li>
 * <li>{@code .nbt} — 原版结构文件（gzip NBT，调色板 + 稀疏方块表）</li>
 * <li>{@code .schem} / {@code .schematic} — Sponge 结构（v1/v2/v3，调色板 + varint 数据）；
 * 旧版 MCEdit 数字 ID 的 {@code .schematic} 不受支持（请先转存为 Litematica / 原版结构）</li>
 * </ul>
 */
public final class BlueprintIO {

    /** 支持的蓝图后缀（顺序即界面优先级）。 */
    public static final List<String> EXTENSIONS = List.of(".gtcpf", ".litematic", ".nbt", ".schem", ".schematic");

    /** 空气在图案里固定用空格表示。 */
    private static final char AIR = ' ';

    /**
     * 读取上限：安全值用于目录扫描与日常加载，放宽值只能在玩家确认「有崩溃风险」后使用。
     */
    public record Limits(long nbtBytes, long fileBytes, int maxDimension, long maxVolume) {}

    /** 扫描/普通加载：超过即视为「需确认」的超大蓝图。 */
    public static final Limits SAFE = new Limits(64L << 20, 32L << 20, 512, 16L << 20);
    /** 玩家确认后：尽量读出来，但仍留一道硬上限，避免真的把堆吃穿。 */
    public static final Limits FORCED = new Limits(512L << 20, 512L << 20, 1024, 48L << 20);

    private BlueprintIO() {}

    /** 文件名是否是可识别的蓝图。 */
    public static boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    /** 去掉任意受支持后缀的文件名。 */
    public static String baseName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return fileName.substring(0, fileName.length() - extension.length());
        }
        return fileName;
    }

    /** 安全上限读取；未知或损坏的格式抛 {@link IOException}。 */
    public static BlueprintBundle.Bundle load(Path file) throws IOException {
        return load(file, SAFE);
    }

    /** 放宽上限读取（只在玩家确认风险后调用）。 */
    public static BlueprintBundle.Bundle loadForced(Path file) throws IOException {
        return load(file, FORCED);
    }

    /** 按给定上限读取蓝图；未知或损坏的格式抛 {@link IOException}。 */
    public static BlueprintBundle.Bundle load(Path file, Limits limits) throws IOException {
        long length = Files.size(file);
        if (length > limits.fileBytes()) {
            throw new IOException("blueprint file too large (" + (length >> 20) + " MB): " + file.getFileName());
        }
        String name = baseName(file.getFileName().toString());
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gtcpf")) return BlueprintBundle.load(file);
        if (lower.endsWith(".litematic")) return readLitematic(file, name, limits);
        if (lower.endsWith(".nbt")) return readVanillaStructure(file, name, limits);
        if (lower.endsWith(".schem") || lower.endsWith(".schematic")) return readSpongeSchematic(file, name, limits);
        throw new IOException("unsupported blueprint format: " + file.getFileName());
    }

    // -------------------------------------------------------------------------
    // Litematica 投影
    // -------------------------------------------------------------------------

    private static BlueprintBundle.Bundle readLitematic(Path file, String name, Limits limits) throws IOException {
        CompoundTag root = readNbt(file, limits);
        CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty()) throw new IOException("litematic has no regions: " + file.getFileName());

        record Region(int minX, int minY, int minZ, int sx, int sy, int sz, CompoundTag tag) {}
        List<Region> list = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String key : regions.getAllKeys()) {
            CompoundTag region = regions.getCompound(key);
            int[] position = region.getIntArray("Position");
            int[] size = region.getIntArray("Size");
            if (position.length != 3 || size.length != 3) continue;
            int sx = size[0], sy = size[1], sz = size[2];
            int ox = position[0], oy = position[1], oz = position[2];
            // 负尺寸表示从 Position 反向延伸，统一成最小角 + 正尺寸
            if (sx < 0) {
                ox += sx + 1;
                sx = -sx;
            }
            if (sy < 0) {
                oy += sy + 1;
                sy = -sy;
            }
            if (sz < 0) {
                oz += sz + 1;
                sz = -sz;
            }
            if (sx <= 0 || sy <= 0 || sz <= 0) continue;
            list.add(new Region(ox, oy, oz, sx, sy, sz, region));
            minX = Math.min(minX, ox);
            minY = Math.min(minY, oy);
            minZ = Math.min(minZ, oz);
            maxX = Math.max(maxX, ox + sx);
            maxY = Math.max(maxY, oy + sy);
            maxZ = Math.max(maxZ, oz + sz);
        }
        if (list.isEmpty()) throw new IOException("litematic has no usable region: " + file.getFileName());

        Grid grid = new Grid(maxX - minX, maxY - minY, maxZ - minZ, limits);
        Palette palette = new Palette();
        for (Region region : list) {
            ListTag paletteTag = region.tag().getList("BlockStatePalette", Tag.TAG_COMPOUND);
            long[] states = region.tag().getLongArray("BlockStates");
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteTag.size() - 1));
            BlockState[] regionStates = new BlockState[paletteTag.size()];
            for (int i = 0; i < paletteTag.size(); i++) {
                regionStates[i] = stateFromTag(paletteTag.getCompound(i));
            }
            long volume = (long) region.sx() * region.sy() * region.sz();
            for (int y = 0; y < region.sy(); y++) {
                for (int z = 0; z < region.sz(); z++) {
                    for (int x = 0; x < region.sx(); x++) {
                        long index = (long) y * region.sx() * region.sz() + (long) z * region.sx() + x;
                        if (index >= volume) break;
                        int id = readPacked(states, bits, index);
                        if (id < 0 || id >= regionStates.length) continue;
                        BlockState state = regionStates[id];
                        if (state == null || state.isAir()) continue;
                        grid.set(region.minX() - minX + x, region.minY() - minY + y, region.minZ() - minZ + z,
                                palette.charAt(state));
                    }
                }
            }
        }
        return grid.toBundle(name, palette);
    }

    /** Litematica/MC 的位压缩读取：每个值占 bits 位，可跨两个 long。 */
    private static int readPacked(long[] data, int bits, long index) {
        if (data.length == 0) return 0;
        long startOffset = index * bits;
        int startArrIndex = (int) (startOffset >> 6);
        int endArrIndex = (int) (((index + 1L) * bits - 1L) >> 6);
        if (startArrIndex < 0 || startArrIndex >= data.length) return 0;
        int startBitOffset = (int) (startOffset & 63L);
        long mask = (1L << bits) - 1L;
        if (startArrIndex == endArrIndex) {
            return (int) (data[startArrIndex] >>> startBitOffset & mask);
        }
        if (endArrIndex >= data.length) return 0;
        int endOffset = 64 - startBitOffset;
        return (int) ((data[startArrIndex] >>> startBitOffset | data[endArrIndex] << endOffset) & mask);
    }

    // -------------------------------------------------------------------------
    // 原版结构文件（.nbt）
    // -------------------------------------------------------------------------

    private static BlueprintBundle.Bundle readVanillaStructure(Path file, String name, Limits limits) throws IOException {
        CompoundTag root = readNbt(file, limits);
        int[] size = root.getIntArray("size");
        if (size.length != 3) {
            ListTag sizeList = root.getList("size", Tag.TAG_INT);
            if (sizeList.size() != 3) throw new IOException("invalid structure size: " + file.getFileName());
            size = new int[] { sizeList.getInt(0), sizeList.getInt(1), sizeList.getInt(2) };
        }
        int sizeX = size[0], sizeY = size[1], sizeZ = size[2];
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IOException("invalid structure size: " + file.getFileName());
        }
        ListTag paletteTag = root.contains("palettes", Tag.TAG_LIST) ?
                root.getList("palettes", Tag.TAG_LIST).getList(0) :
                root.getList("palette", Tag.TAG_COMPOUND);
        if (paletteTag.isEmpty()) throw new IOException("structure has no palette: " + file.getFileName());

        Grid grid = new Grid(sizeX, sizeY, sizeZ, limits);
        Palette palette = new Palette();
        BlockState[] states = new BlockState[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            states[i] = stateFromTag(paletteTag.getCompound(i));
        }
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            int[] pos = readPos(entry);
            if (pos == null) continue;
            int stateIndex = entry.getInt("state");
            if (stateIndex < 0 || stateIndex >= states.length) continue;
            BlockState state = states[stateIndex];
            if (state == null || state.isAir()) continue;
            grid.set(pos[0], pos[1], pos[2], palette.charAt(state));
        }
        return grid.toBundle(name, palette);
    }

    private static int[] readPos(CompoundTag entry) {
        if (entry.contains("pos", Tag.TAG_INT_ARRAY)) {
            int[] pos = entry.getIntArray("pos");
            return pos.length == 3 ? pos : null;
        }
        ListTag list = entry.getList("pos", Tag.TAG_INT);
        if (list.size() != 3) return null;
        return new int[] { list.getInt(0), list.getInt(1), list.getInt(2) };
    }

    // -------------------------------------------------------------------------
    // Sponge 结构（.schem / .schematic v1-v3）
    // -------------------------------------------------------------------------

    private static BlueprintBundle.Bundle readSpongeSchematic(Path file, String name, Limits limits) throws IOException {
        CompoundTag root = readNbt(file, limits);
        CompoundTag body = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        int width = body.getShort("Width") & 0xFFFF;
        int height = body.getShort("Height") & 0xFFFF;
        int length = body.getShort("Length") & 0xFFFF;
        CompoundTag paletteTag = body.getCompound("Palette");
        byte[] data = body.getByteArray("BlockData");
        if (data.length == 0 && body.contains("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag blocksTag = body.getCompound("Blocks");
            paletteTag = blocksTag.getCompound("Palette");
            data = blocksTag.getByteArray("Data");
        }
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("invalid schematic size: " + file.getFileName());
        }
        if (paletteTag.isEmpty() || data.length == 0) {
            // 旧版 MCEdit 的 .schematic 是数字方块 ID + Data 数组，需要 1.12 的 ID 表，无法可靠转换
            throw new IOException("legacy .schematic (numeric block ids) unsupported; export as .litematic/.nbt: " +
                    file.getFileName());
        }
        BlockState[] states = new BlockState[paletteTag.getAllKeys().size()];
        for (String key : paletteTag.getAllKeys()) {
            int index = paletteTag.getInt(key);
            if (index < 0 || index >= states.length) continue;
            states[index] = stateFromString(key);
        }
        Grid grid = new Grid(width, height, length, limits);
        Palette palette = new Palette();
        long volume = (long) width * height * length;
        int index = 0;
        int position = 0;
        while (position < data.length && index < volume) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (position >= data.length) break;
                byte b = data[position++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) break;
            }
            if (value >= 0 && value < states.length) {
                BlockState state = states[value];
                if (state != null && !state.isAir()) {
                    int y = (int) (index / ((long) width * length));
                    int rest = (int) (index % ((long) width * length));
                    int z = rest / width;
                    int x = rest % width;
                    grid.set(x, y, z, palette.charAt(state));
                }
            }
            index++;
        }
        return grid.toBundle(name, palette);
    }

    // -------------------------------------------------------------------------
    // 公共工具
    // -------------------------------------------------------------------------

    private static CompoundTag readNbt(Path file, Limits limits) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
            // 兼容未压缩的 NBT：文件头不是 gzip 魔数时直接按原始数据解析
            raw.mark(2);
            int first = raw.read();
            int second = raw.read();
            raw.reset();
            InputStream decoded = first == 0x1F && second == 0x8B ? new GZIPInputStream(raw) : raw;
            try (DataInputStream data = new DataInputStream(decoded)) {
                CompoundTag root = NbtIo.read(data, new NbtAccounter(limits.nbtBytes()));
                if (root == null) throw new IOException("empty nbt: " + file.getFileName());
                return root;
            }
        } catch (RuntimeException tooLarge) {
            // NbtAccounter 超限会抛 RuntimeException，转成可读的 IO 失败
            throw new IOException("blueprint nbt too large (limit " + (limits.nbtBytes() >> 20) + " MB): " +
                    file.getFileName(), tooLarge);
        }
    }

    /** {@code {Name:"minecraft:stone", Properties:{...}}} -> BlockState。 */
    private static BlockState stateFromTag(CompoundTag tag) {
        String name = tag.getString("Name");
        if (name.isEmpty()) return Blocks.AIR.defaultBlockState();
        BlockState state = BuiltInRegistries.BLOCK.get(new ResourceLocation(name)).defaultBlockState();
        CompoundTag properties = tag.getCompound("Properties");
        for (String key : properties.getAllKeys()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(key);
            if (property == null) continue;
            Optional<?> value = property.getValue(properties.getString(key));
            if (value.isPresent()) state = setProperty(state, property, value.get());
        }
        return state;
    }

    /** {@code "minecraft:oak_stairs[facing=north,half=bottom]"} -> BlockState（Sponge 调色板键）。 */
    private static BlockState stateFromString(String key) {
        String name = key;
        int bracket = key.indexOf('[');
        if (bracket < 0) return BuiltInRegistries.BLOCK.get(new ResourceLocation(name)).defaultBlockState();
        name = key.substring(0, bracket);
        BlockState state = BuiltInRegistries.BLOCK.get(new ResourceLocation(name)).defaultBlockState();
        int end = key.lastIndexOf(']');
        String props = end > bracket ? key.substring(bracket + 1, end) : "";
        for (String pair : props.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            Property<?> property = state.getBlock().getStateDefinition().getProperty(pair.substring(0, eq).trim());
            if (property == null) continue;
            Optional<?> value = property.getValue(pair.substring(eq + 1).trim());
            if (value.isPresent()) state = setProperty(state, property, value.get());
        }
        return state;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static BlockState setProperty(BlockState state, Property property, Object value) {
        return state.setValue(property, (Comparable) value);
    }

    /** 方块状态 -> 图案字符，跳过需要转义的字符。 */
    private static final class Palette {

        /** 可用字符池：去掉空格、引号、括号、逗号等解析器特殊字符。 */
        private static final char[] POOL = buildPool();

        private static char[] buildPool() {
            String skip = "()[],./\\\"'` ";
            StringBuilder builder = new StringBuilder(90);
            for (char c = '!'; c <= '~'; c++) {
                if (skip.indexOf(c) < 0) builder.append(c);
            }
            return builder.toString().toCharArray();
        }

        private final Char2ReferenceOpenHashMap<BlockState> byChar = new Char2ReferenceOpenHashMap<>();
        private final java.util.Map<BlockState, Character> byState = new java.util.HashMap<>();
        private int nextIndex;

        Palette() {
            byChar.put(AIR, Blocks.AIR.defaultBlockState());
        }

        char charAt(BlockState state) {
            Character existing = byState.get(state);
            if (existing != null) return existing;
            if (nextIndex >= POOL.length) {
                throw new IllegalStateException("too many distinct block states: " + byState.size());
            }
            char current = POOL[nextIndex++];
            byState.put(state, current);
            byChar.put(current, state);
            return current;
        }

        Char2ReferenceOpenHashMap<BlockState> mapping() {
            return byChar;
        }
    }

    /** 局部坐标网格：[x][y][z]，最后展开成 {@code pattern[z][y] = 沿 X 的字符行}。 */
    private static final class Grid {

        private final int sizeX, sizeY, sizeZ;
        private final char[][][] cells;

        Grid(int sizeX, int sizeY, int sizeZ, Limits limits) {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 ||
                    sizeX > limits.maxDimension() || sizeY > limits.maxDimension() ||
                    sizeZ > limits.maxDimension() ||
                    (long) sizeX * sizeY * sizeZ > limits.maxVolume()) {
                throw new IllegalArgumentException("blueprint too large: " + sizeX + "x" + sizeY + "x" + sizeZ);
            }
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.cells = new char[sizeX][sizeY][sizeZ];
            for (char[][] byY : cells) {
                for (char[] byZ : byY) {
                    java.util.Arrays.fill(byZ, AIR);
                }
            }
        }

        void set(int x, int y, int z, char value) {
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return;
            cells[x][y][z] = value;
        }

        BlueprintBundle.Bundle toBundle(String name, Palette palette) {
            String[][] pattern = new String[sizeZ][sizeY];
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    char[] row = new char[sizeX];
                    for (int x = 0; x < sizeX; x++) {
                        row[x] = cells[x][y][z];
                    }
                    pattern[z][y] = new String(row);
                }
            }
            return new BlueprintBundle.Bundle(name, sizeX, sizeY, sizeZ, pattern, palette.mapping());
        }
    }
}
