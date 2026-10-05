package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resource/config rules and script registrations. Published snapshots are immutable. */
public final class BloomRules {

    public record BlockRule(ResourceLocation block, Map<String, String> state) {

        public BlockRule {
            state = Map.copyOf(state);
        }

        public static BlockRule parse(String selector) {
            int bracket = selector.indexOf('[');
            if (bracket < 0) return new BlockRule(new ResourceLocation(selector), Map.of());
            if (!selector.endsWith("]")) throw new IllegalArgumentException("Missing ] in " + selector);
            var properties = new LinkedHashMap<String, String>();
            String body = selector.substring(bracket + 1, selector.length() - 1);
            if (body.isBlank()) throw new IllegalArgumentException("Empty state selector: " + selector);
            for (String pair : body.split(",", -1)) {
                String[] parts = pair.split("=", -1);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || properties.putIfAbsent(parts[0].trim(), parts[1].trim()) != null)
                    throw new IllegalArgumentException("Invalid state selector: " + selector);
            }
            return new BlockRule(new ResourceLocation(selector.substring(0, bracket)), properties);
        }
    }

    public record Data(List<BlockRule> blocks, Set<ResourceLocation> textures) {

        public Data {
            blocks = List.copyOf(blocks);
            textures = Set.copyOf(textures);
        }
    }

    private record Snapshot(Set<BlockState> blocks, Set<ResourceLocation> textures) {}

    private static Data loaded = new Data(List.of(), Set.of());
    private static final Set<BlockRule> scriptBlocks = new HashSet<>();
    private static final Set<ResourceLocation> scriptTextures = new HashSet<>();
    private static volatile Snapshot snapshot = new Snapshot(Set.of(), Set.of());

    public static Data read(ResourceManager resources) {
        var blocks = new ArrayList<BlockRule>();
        var textures = new HashSet<ResourceLocation>();
        // Each named file follows normal resource-pack override priority.
        resources.listResources("gtlbloom", id -> id.getPath().endsWith(".json")).forEach((id, resource) -> {
            try (var reader = resource.openAsReader()) {
                readFile(reader, id.toString(), false, blocks, textures);
            } catch (IOException e) {
                warn(id.toString(), e);
            }
        });
        // Shimmer uses all layers of assets/shimmer/shimmer.json; other namespaces have one config.
        for (String namespace : resources.getNamespaces()) {
            var id = new ResourceLocation(namespace, "shimmer.json");
            var stack = namespace.equals("shimmer") ? resources.getResourceStack(id) : resources.getResource(id).stream().toList();
            for (var resource : stack) {
                try (var reader = resource.openAsReader()) {
                    readFile(reader, id + " from " + resource.sourcePackId(), true, blocks, textures);
                } catch (IOException e) {
                    warn(id.toString(), e);
                }
            }
        }
        Path config = FMLPaths.CONFIGDIR.get();
        readPath(config.resolve("gtlbloom-bloom.json"), false, blocks, textures);
        Path shimmer = config.resolve("shimmer");
        if (Files.isDirectory(shimmer)) {
            try (var files = Files.list(shimmer)) {
                files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json"))
                        .sorted().forEach(p -> readPath(p, true, blocks, textures));
            } catch (IOException e) {
                warn(shimmer.toString(), e);
            }
        }
        return new Data(blocks, textures);
    }

    private static void readPath(Path path, boolean shimmer, List<BlockRule> blocks, Set<ResourceLocation> textures) {
        if (!Files.isRegularFile(path)) return;
        try (var reader = Files.newBufferedReader(path)) {
            readFile(reader, path.toString(), shimmer, blocks, textures);
        } catch (IOException e) {
            warn(path.toString(), e);
        }
    }

    // A bad entry must not suppress unrelated valid registrations in the same file.
    private static void readFile(Reader reader, String source, boolean shimmer,
                                 List<BlockRule> blocks, Set<ResourceLocation> textures) {
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String enabled = shimmer ? "Enable" : "enabled";
            if (root.has(enabled) && !root.get(enabled).getAsBoolean()) return;
            String key = shimmer ? "Bloom" : "blocks";
            int unsupported = 0;
            if (root.has(key)) for (JsonElement value : root.getAsJsonArray(key)) {
                try {
                    if (shimmer && value.isJsonObject() && !value.getAsJsonObject().has("block")) {
                        unsupported++;
                        continue;
                    }
                    if (value.isJsonPrimitive()) blocks.add(BlockRule.parse(value.getAsString()));
                    else {
                        JsonObject entry = value.getAsJsonObject();
                        var rule = BlockRule.parse(entry.get("block").getAsString());
                        var state = new LinkedHashMap<>(rule.state());
                        if (entry.has("state")) for (var property : entry.getAsJsonObject("state").entrySet()) {
                            if (state.putIfAbsent(property.getKey(), property.getValue().getAsString()) != null)
                                throw new IllegalArgumentException("Repeated property " + property.getKey());
                        }
                        blocks.add(new BlockRule(rule.block(), state));
                    }
                } catch (RuntimeException e) {
                    warn(source + " entry " + value, e);
                }
            }
            if (unsupported > 0) GTLCore.LOGGER.info("{}: skipped {} particle/fluid bloom entries (unsupported)", source, unsupported);
            if (!shimmer && root.has("textures")) for (JsonElement value : root.getAsJsonArray("textures")) {
                try {
                    textures.add(new ResourceLocation(value.getAsString()));
                } catch (RuntimeException e) {
                    warn(source + " texture " + value, e);
                }
            }
        } catch (RuntimeException e) {
            warn(source, e);
        }
    }

    public static synchronized void install(Data data) {
        loaded = data;
        publish();
    }

    static synchronized void scriptBlock(BlockRule rule, boolean add) {
        if (add ? scriptBlocks.add(rule) : scriptBlocks.remove(rule)) publish();
    }

    static synchronized void scriptTexture(ResourceLocation texture, boolean add) {
        if (add ? scriptTextures.add(texture) : scriptTextures.remove(texture)) publish();
    }

    static synchronized void clearScripts() {
        scriptBlocks.clear();
        scriptTextures.clear();
        publish();
    }

    private static void publish() {
        var blocks = new HashSet<BlockState>();
        var rules = new HashSet<>(loaded.blocks());
        rules.addAll(scriptBlocks);
        for (BlockRule rule : rules) {
            try {
                if (!ForgeRegistries.BLOCKS.containsKey(rule.block()))
                    throw new IllegalArgumentException("Unknown block " + rule.block());
                var block = ForgeRegistries.BLOCKS.getValue(rule.block());
                for (var entry : rule.state().entrySet()) {
                    Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                    if (property == null || property.getValue(entry.getValue()).isEmpty())
                        throw new IllegalArgumentException("Invalid property " + entry + " for " + rule.block());
                }
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    boolean matches = true;
                    for (var entry : rule.state().entrySet()) {
                        Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                        if (!property.getValue(entry.getValue()).orElseThrow().equals(state.getValue(property))) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches) blocks.add(state);
                }
            } catch (RuntimeException e) {
                warn(rule.toString(), e);
            }
        }
        var textures = new HashSet<>(loaded.textures());
        textures.addAll(scriptTextures);
        Set<ResourceLocation> previousTextures = snapshot.textures();
        Set<BlockState> previousBlocks = snapshot.blocks();
        snapshot = new Snapshot(Set.copyOf(blocks), Set.copyOf(textures));
        if (!previousTextures.equals(snapshot.textures()) || !previousBlocks.equals(snapshot.blocks())) ShaderEmissionBridge.rulesChanged();
        BloomClient.requestRebuild();
    }

    public static boolean fullBlock(BlockState state) {
        return snapshot.blocks().contains(state);
    }

    public static boolean texture(ResourceLocation id) {
        return snapshot.textures().contains(id);
    }

    public static int stateCount() {
        return snapshot.blocks().size();
    }

    public static int textureCount() {
        return snapshot.textures().size();
    }

    private static void warn(String source, Exception error) {
        GTLCore.LOGGER.warn("Ignoring invalid bloom rule in {}: {}", source, error.toString());
    }

    private BloomRules() {}
}
