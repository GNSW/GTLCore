package org.gtlcore.gtlcore.common.structure.io;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.resources.ResourceLocation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GTO 兼容的 {@code .mbs} 结构图案 IO。
 * <p>
 * 文件布局（与 {@code MultiBlockFileReader} 一致）：
 *
 * <pre>
 *   GZIP(
 *     VarInt  versionMarker = -1
 *     VarInt  formatVersion = 1
 *     VarInt  charDirOrdinal   // RelativeDirection, 默认 LEFT=2
 *     VarInt  stringDirOrdinal // RelativeDirection, 默认 UP=0
 *     VarInt  aisleDirOrdinal  // RelativeDirection, 默认 FRONT=4
 *     VarInt  aisleCount (Z)
 *     for each aisle:
 *       VarInt rowCount (Y)
 *       for each row: modified-UTF (沿 X 的字符行)
 *   )
 * </pre>
 *
 * 语义：{@code pattern[z][y]} 为沿 X 的字符串。放置侧不依赖相对方向枚举，
 * 仅原样读写 ordinal 以保持与 GTO 资源互通。
 */
public final class StructureFileIO {

    public static final int FORMAT_VERSION = 1;
    private static final int VERSIONED_FORMAT_MARKER = -1;

    /** GTCEu RelativeDirection ordinals: UP=0 DOWN=1 LEFT=2 RIGHT=3 FRONT=4 BACK=5 */
    public static final int DIR_UP = 0;
    public static final int DIR_LEFT = 2;
    public static final int DIR_FRONT = 4;

    private StructureFileIO() {}

    public record PatternData(
                              String[][] pattern,
                              int sizeX,
                              int sizeY,
                              int sizeZ,
                              int charDir,
                              int stringDir,
                              int aisleDir) {

        public PatternData(String[][] pattern, int sizeX, int sizeY, int sizeZ) {
            this(pattern, sizeX, sizeY, sizeZ, DIR_LEFT, DIR_UP, DIR_FRONT);
        }

        public int volume() {
            return sizeX * sizeY * sizeZ;
        }
    }

    public static PatternData load(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            int marker = readVarInt(in);
            if (marker != VERSIONED_FORMAT_MARKER) {
                throw new IOException("unsupported .mbs payload without version marker: " + marker);
            }
            int version = readVarInt(in);
            if (version != FORMAT_VERSION) {
                throw new IOException("unsupported .mbs version: " + version);
            }
            int charDir = readVarInt(in);
            int stringDir = readVarInt(in);
            int aisleDir = readVarInt(in);
            validateDirections(charDir, stringDir, aisleDir);

            int aisleCount = readVarInt(in);
            if (aisleCount < 0) {
                throw new IOException("negative aisle count: " + aisleCount);
            }
            String[][] pattern = new String[aisleCount][];
            int sizeY = -1;
            int sizeX = -1;
            for (int z = 0; z < aisleCount; z++) {
                int rowCount = readVarInt(in);
                if (rowCount < 0) {
                    throw new IOException("negative row count in aisle " + z);
                }
                if (sizeY < 0) sizeY = rowCount;
                else if (rowCount != sizeY) {
                    throw new IOException("inconsistent Y size at aisle " + z);
                }
                pattern[z] = new String[rowCount];
                for (int y = 0; y < rowCount; y++) {
                    String row = in.readUTF();
                    if (sizeX < 0) sizeX = row.length();
                    else if (row.length() != sizeX) {
                        throw new IOException("inconsistent X size at aisle " + z + " row " + y);
                    }
                    pattern[z][y] = row;
                }
            }
            if (sizeX <= 0 || sizeY <= 0) {
                throw new IOException("invalid structure size");
            }
            return new PatternData(pattern, sizeX, sizeY, aisleCount, charDir, stringDir, aisleDir);
        }
    }

    public static PatternData loadResource(String classpathPath) throws IOException {
        InputStream in = StructureFileIO.class.getClassLoader().getResourceAsStream(classpathPath);
        if (in == null) {
            throw new IOException("structure resource not found: " + classpathPath);
        }
        try (in) {
            return load(in);
        }
    }

    public static PatternData loadResource(ResourceLocation id) throws IOException {
        return loadResource(StructureResources.mbs(id));
    }

    public static void save(Path path, String[][] pattern) throws IOException {
        save(path, pattern, DIR_LEFT, DIR_UP, DIR_FRONT);
    }

    public static void save(Path path, String[][] pattern, int charDir, int stringDir, int aisleDir)
                                                                                                     throws IOException {
        Objects.requireNonNull(pattern, "pattern");
        validateDirections(charDir, stringDir, aisleDir);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (OutputStream fos = Files.newOutputStream(path);
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(new GZIPOutputStream(fos)))) {
            writeVarInt(out, VERSIONED_FORMAT_MARKER);
            writeVarInt(out, FORMAT_VERSION);
            writeVarInt(out, charDir);
            writeVarInt(out, stringDir);
            writeVarInt(out, aisleDir);
            writeVarInt(out, pattern.length);
            for (String[] aisle : pattern) {
                writeVarInt(out, aisle.length);
                for (String row : aisle) {
                    out.writeUTF(row);
                }
            }
            out.flush();
        }
    }

    /** @deprecated 使用 {@link StructureResources#mbs(ResourceLocation)} */
    @Deprecated
    public static String resourcePath(ResourceLocation id) {
        return StructureResources.mbs(id);
    }

    public static PatternData tryLoadSizes(ResourceLocation resource) {
        try {
            return loadResource(resource);
        } catch (Exception e) {
            GTLCore.LOGGER.error(
                    "Failed to read structure size for {}: {}", resource, e.getMessage());
            return null;
        }
    }

    private static void validateDirections(int charDir, int stringDir, int aisleDir) {
        if (charDir < 0 || charDir > 5 || stringDir < 0 || stringDir > 5 || aisleDir < 0 || aisleDir > 5) {
            throw new IllegalArgumentException(
                    "direction ordinal out of range: " + charDir + "," + stringDir + "," + aisleDir);
        }
        // 与 GTCEu RelativeDirection 一致：三个方向须落在不同轴（0/1=Y, 2/3=X, 4/5=Z）
        if (axisOf(charDir) == axisOf(stringDir) || axisOf(stringDir) == axisOf(aisleDir) || axisOf(aisleDir) == axisOf(charDir)) {
            throw new IllegalArgumentException(
                    "charDir, stringDir and aisleDir must use different axes");
        }
    }

    private static int axisOf(int dir) {
        return dir >> 1; // UP/DOWN→0, LEFT/RIGHT→1, FRONT/BACK→2
    }

    /** Minecraft / Java 协议 VarInt（与 GTO DataIOStream 相同）。 */
    static void writeVarInt(DataOutputStream out, int input) throws IOException {
        while ((input & -128) != 0) {
            out.writeByte(input & 127 | 128);
            input >>>= 7;
        }
        out.writeByte(input);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int i = 0;
        int j = 0;
        byte b0;
        do {
            b0 = in.readByte();
            i |= (b0 & 127) << j++ * 7;
            if (j > 5) {
                throw new IOException("VarInt too big");
            }
        } while ((b0 & 128) == 128);
        return i;
    }
}
