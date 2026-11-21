package org.gtlcore.gtlcore.common.structure.io;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.chars.Char2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.chars.Char2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2CharLinkedOpenHashMap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 字符 → BlockState 映射 JSON（platforms 布局）。
 */
public final class StructureMappingIO {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StructureMappingIO() {}

    /**
     * 从 {@code platforms/&lt;ns&gt;/&lt;path&gt;.json} 加载映射。
     * path 可带或不带 {@code .json}。
     */
    public static Char2ReferenceOpenHashMap<BlockState> loadResource(ResourceLocation resLoc) {
        String resourcePath = StructureResources.json(resLoc);
        try (InputStream stream = StructureMappingIO.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                GTLCore.LOGGER.error("mapping not found: {}", resourcePath);
                return new Char2ReferenceOpenHashMap<>();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return parse(reader);
            }
        } catch (Exception e) {
            GTLCore.LOGGER.error("Failed to load mapping from {}", resLoc, e);
            return new Char2ReferenceOpenHashMap<>();
        }
    }

    /** 从本地文件加载映射（本地蓝图）；失败抛 {@link IOException}。 */
    public static Char2ReferenceOpenHashMap<BlockState> load(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    private static Char2ReferenceOpenHashMap<BlockState> parse(BufferedReader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        Char2ReferenceOpenHashMap<BlockState> map = new Char2ReferenceOpenHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) continue;
            map.put(key.charAt(0), deserializeState(entry.getValue().getAsJsonObject()));
        }
        return map;
    }

    public static void save(Map<Character, BlockState> mapping, Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            JsonObject root = new JsonObject();
            for (Map.Entry<Character, BlockState> e : mapping.entrySet()) {
                root.add(String.valueOf(e.getKey()), serializeState(e.getValue()));
            }
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            GTLCore.LOGGER.error("Failed to save mapping to {}", path, e);
        }
    }

    public static Char2ReferenceLinkedOpenHashMap<BlockState> invert(
                                                                     Reference2CharLinkedOpenHashMap<BlockState> stateToChar) {
        Char2ReferenceLinkedOpenHashMap<BlockState> charToState = new Char2ReferenceLinkedOpenHashMap<>();
        stateToChar.reference2CharEntrySet().fastForEach(e -> charToState.put(e.getCharValue(), e.getKey()));
        return charToState;
    }

    public static JsonObject serializeState(BlockState src) {
        JsonObject obj = new JsonObject();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(src.getBlock());
        obj.addProperty("id", id.toString());
        JsonObject props = new JsonObject();
        for (Property<?> prop : src.getProperties()) {
            props.addProperty(prop.getName(), getPropertyValue(src, prop));
        }
        obj.add("properties", props);
        return obj;
    }

    public static BlockState deserializeState(JsonObject obj) {
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(obj.get("id").getAsString()));
        if (block == null || block == Blocks.AIR) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = block.defaultBlockState();
        JsonObject props = obj.getAsJsonObject("properties");
        if (props != null) {
            for (var entry : props.entrySet()) {
                Property<?> prop = block.getStateDefinition().getProperty(entry.getKey());
                if (prop != null) {
                    Optional<?> valueOpt = prop.getValue(entry.getValue().getAsString());
                    if (valueOpt.isPresent()) {
                        state = setValue(state, prop, valueOpt.get());
                    }
                }
            }
        }
        return state;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static BlockState setValue(BlockState state, Property prop, Object value) {
        return state.setValue(prop, (Comparable) value);
    }

    private static <T extends Comparable<T>> String getPropertyValue(BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }
}
