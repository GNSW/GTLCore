package org.gtlcore.gtlcore.common.structure.local;

import org.gtlcore.gtlcore.common.structure.io.StructureMappingIO;

import net.minecraft.world.level.block.state.BlockState;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.chars.Char2ReferenceOpenHashMap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 单文件蓝图 {@code .gtcpf}：gzip 压缩的 JSON，内含图案、方块调色板与元数据。
 * <p>
 * 结构：{@code {format, version, name, created, size:[x,y,z], pattern:[[row,...],...], palette:{char:state}}}，
 * 其中 {@code pattern[z][y]} 是沿 X 的一行字符
 * 图案与映射打包成一个文件，便于分享和文件夹管理。
 * </p>
 */
public final class BlueprintBundle {

    /** 蓝图文件后缀。 */
    public static final String EXTENSION = ".gtcpf";

    private static final String FORMAT = "gtlcore.blueprint";
    private static final int VERSION = 1;
    private static final Gson GSON = new Gson();

    /** 解析后的蓝图：尺寸、图案与字符→方块调色板。 */
    public record Bundle(String name, String author, int sizeX, int sizeY, int sizeZ,
                         String[][] pattern, Char2ReferenceOpenHashMap<BlockState> palette) {

        /** 非空气方块数（材料点数按它等比折算）。 */
        public int blocks() {
            int count = 0;
            for (String[] aisle : pattern) {
                for (String row : aisle) {
                    for (int i = 0; i < row.length(); i++) {
                        if (row.charAt(i) != ' ') count++;
                    }
                }
            }
            return count;
        }
    }

    private BlueprintBundle() {}

    /** 读取蓝图；格式不符抛 {@link IOException}。 */
    public static Bundle load(Path file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String format = root.has("format") ? root.get("format").getAsString() : "";
            if (!FORMAT.equals(format)) {
                throw new IOException("not a " + EXTENSION + " blueprint: " + file.getFileName());
            }
            JsonArray aisles = root.getAsJsonArray("pattern");
            if (aisles == null || aisles.isEmpty()) {
                throw new IOException("empty blueprint pattern: " + file.getFileName());
            }
            String[][] pattern = new String[aisles.size()][];
            int sizeX = -1;
            int sizeY = -1;
            for (int z = 0; z < aisles.size(); z++) {
                JsonArray rows = aisles.get(z).getAsJsonArray();
                if (rows.isEmpty()) throw new IOException("empty aisle in blueprint: " + file.getFileName());
                if (sizeY < 0) sizeY = rows.size();
                else if (rows.size() != sizeY) {
                    throw new IOException("inconsistent Y size in blueprint: " + file.getFileName());
                }
                String[] aisle = new String[rows.size()];
                for (int y = 0; y < rows.size(); y++) {
                    String row = rows.get(y).getAsString();
                    if (sizeX < 0) sizeX = row.length();
                    else if (row.length() != sizeX) {
                        throw new IOException("inconsistent X size in blueprint: " + file.getFileName());
                    }
                    aisle[y] = row;
                }
                pattern[z] = aisle;
            }
            if (sizeX <= 0 || sizeY <= 0) {
                throw new IOException("invalid blueprint size: " + file.getFileName());
            }
            Char2ReferenceOpenHashMap<BlockState> palette = new Char2ReferenceOpenHashMap<>();
            JsonObject paletteJson = root.getAsJsonObject("palette");
            if (paletteJson != null) {
                for (Map.Entry<String, JsonElement> entry : paletteJson.entrySet()) {
                    String key = entry.getKey();
                    if (key == null || key.isEmpty() || !entry.getValue().isJsonObject()) continue;
                    palette.put(key.charAt(0), StructureMappingIO.deserializeState(entry.getValue().getAsJsonObject()));
                }
            }
            if (palette.isEmpty()) {
                throw new IOException("empty blueprint palette: " + file.getFileName());
            }
            String name = root.has("name") ? root.get("name").getAsString() : file.getFileName().toString();
            String author = root.has("author") ? root.get("author").getAsString() : "";
            return new Bundle(name, author, sizeX, sizeY, pattern.length, pattern, palette);
        }
    }

    /** 写出蓝图（单文件，自动创建上级目录）；author 为空时写空串。 */
    public static void save(Path file, String name, String author, String[][] pattern,
                            Map<Character, BlockState> palette) throws IOException {
        int sizeX = pattern.length == 0 || pattern[0].length == 0 ? 0 : pattern[0][0].length();
        int sizeY = pattern.length == 0 ? 0 : pattern[0].length;

        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("name", name);
        root.addProperty("author", author == null ? "" : author);
        root.addProperty("created", LocalDateTime.now().toString());
        JsonArray size = new JsonArray();
        size.add(sizeX);
        size.add(sizeY);
        size.add(pattern.length);
        root.add("size", size);

        JsonArray aisles = new JsonArray();
        for (String[] aisle : pattern) {
            JsonArray rows = new JsonArray();
            for (String row : aisle) rows.add(row);
            aisles.add(rows);
        }
        root.add("pattern", aisles);

        JsonObject paletteJson = new JsonObject();
        palette.forEach((key, state) -> paletteJson.add(String.valueOf(key), StructureMappingIO.serializeState(state)));
        root.add("palette", paletteJson);

        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8))) {
            GSON.toJson(root, writer);
        }
    }
}
