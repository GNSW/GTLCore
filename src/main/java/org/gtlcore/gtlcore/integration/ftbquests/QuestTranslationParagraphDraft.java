package org.gtlcore.gtlcore.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** A source-language paragraph draft. Rich text stays in the quest, prose stays in the catalog. */
public final class QuestTranslationParagraphDraft {

    private static final Pattern JSON_TEXT = Pattern.compile("^\\s*(?:\\{\\s*\"|\\[\\s*[\"{\\[])");
    private final String prefix;
    private final String descriptionPrefix;
    private final Map<String, String> originalSource;
    private final Map<String, String> source;
    private final List<String> original;
    private final List<String> lines;

    public QuestTranslationParagraphDraft(long id, List<String> lines, QuestTranslationCatalog.Snapshot snapshot) {
        this.prefix = snapshot.keyPrefix();
        this.descriptionPrefix = prefix + "quest." + String.format(Locale.ROOT, "%016x", id) + ".description.";
        this.originalSource = Map.copyOf(snapshot.source());
        this.source = new TreeMap<>(originalSource);
        this.original = List.copyOf(lines);
        this.lines = new ArrayList<>(lines);
    }

    private QuestTranslationParagraphDraft(QuestTranslationParagraphDraft other) {
        prefix = other.prefix;
        descriptionPrefix = other.descriptionPrefix;
        originalSource = other.originalSource;
        source = new TreeMap<>(other.source);
        original = other.original;
        lines = new ArrayList<>(other.lines);
    }

    public QuestTranslationParagraphDraft copy() {
        return new QuestTranslationParagraphDraft(this);
    }

    public List<String> original() {
        return original;
    }

    public List<String> lines() {
        return lines;
    }

    public String text(int index) {
        return text(lines.get(index));
    }

    public String text(String raw) {
        String key = reference(raw);
        if (key != null) return source.get(key);
        if (!JSON_TEXT.matcher(raw).find()) return raw;
        try {
            JsonElement json = componentRoot(JsonParser.parseString(raw));
            visit(json, "", (node, path) -> {
                if (node.has("translate") && node.get("translate").isJsonPrimitive()) {
                    String name = node.get("translate").getAsString();
                    if (source.containsKey(name) && !node.has("with")) {
                        node.remove("translate");
                        node.addProperty("text", source.get(name));
                    }
                }
            });
            return json.toString();
        } catch (RuntimeException ignored) {
            return raw;
        }
    }

    public boolean dirty() {
        return !original.equals(lines) || !changes().isEmpty();
    }

    public Map<String, QuestTranslationCatalog.SourceEdit> changes() {
        var edits = new TreeMap<String, QuestTranslationCatalog.SourceEdit>();
        for (var entry : source.entrySet()) {
            String before = originalSource.get(entry.getKey());
            if (!Objects.equals(before, entry.getValue()) && used(entry.getKey())) {
                edits.put(entry.getKey(), new QuestTranslationCatalog.SourceEdit(before, entry.getValue()));
            }
        }
        return edits;
    }

    private boolean used(String key) {
        return lines.stream().anyMatch(raw -> raw.contains("{" + key + "}") || raw.contains("\"" + key + "\""));
    }

    public void replace(int index, List<String> replacement) throws IOException {
        change(index, true, replacement);
    }

    public void insert(int index, List<String> replacement) throws IOException {
        change(index, false, replacement);
    }

    private void change(int index, boolean replace, List<String> replacement) throws IOException {
        if (lines.size() - (replace ? 1 : 0) + replacement.size() > 4096) throw new IOException("Too many quest paragraphs");
        String previous = replace ? lines.get(index) : "";
        if (replace && text(previous).equals(String.join("\n", replacement))) return;
        var before = new TreeMap<>(source);
        try {
            var converted = new ArrayList<String>();
            for (int i = 0; i < replacement.size(); i++) {
                converted.add(convert(replacement.get(i), i == 0 ? previous : "", replacement.size() == 1));
            }
            if (replace) lines.remove(index);
            lines.addAll(index, converted);
        } catch (IOException | RuntimeException e) {
            source.clear();
            source.putAll(before);
            if (e instanceof IOException io) throw io;
            throw new IOException("Invalid quest paragraph: " + e.getMessage(), e);
        }
    }

    private String convert(String raw, String previous, boolean single) throws IOException {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("{@") || trimmed.startsWith("{image:")) return raw;
        if (reference(raw) != null) return raw;
        String base = nextParagraph();
        if (!JSON_TEXT.matcher(raw).find()) {
            String key = single ? reference(previous) : null;
            if (key == null) key = base;
            put(key, raw);
            return "{" + key + "}";
        }

        JsonElement json = componentRoot(JsonParser.parseString(raw));
        var bindings = new LinkedHashMap<String, Binding>();
        if (single && JSON_TEXT.matcher(previous).find()) {
            visit(componentRoot(JsonParser.parseString(previous)), "", (node, path) -> {
                if (node.has("translate") && node.get("translate").isJsonPrimitive() && !node.has("with")) {
                    String key = node.get("translate").getAsString();
                    if (source.containsKey(key)) bindings.put(path, new Binding(key, source.get(key)));
                }
            });
        }
        var textNodes = new LinkedHashMap<String, JsonObject>();
        json = normalize(json);
        visit(json, "", (node, path) -> {
            if (node.has("text") && node.get("text").isJsonPrimitive() && !node.get("text").getAsString().isEmpty()) {
                textNodes.put(path, node);
            }
        });
        // Plain FTB references and JSON translate nodes interpret formatting differently.
        // Changing representation needs new keys even for one node: reusing a plain
        // translation such as "&6Title&r" in JSON would expose the codes as literal text.
        var assigned = new java.util.HashSet<String>();
        var remainingText = textNodes.values().stream().map(node -> node.get("text").getAsString())
                .collect(java.util.stream.Collectors.toSet());
        int ordinal = 0;
        for (var entry : textNodes.entrySet()) {
            JsonObject node = entry.getValue();
            if (node.has("translate")) throw new IOException("Rich text contains both text and translate");
            String value = node.get("text").getAsString();
            Binding binding = bindings.values().stream()
                    .filter(b -> b.text.equals(value) && !assigned.contains(b.key)).findFirst().orElse(null);
            // Only reuse a changed node's key when its text-node topology is unchanged.
            // Splitting a translated sentence for a link must not assign its full English
            // translation to one of the new fragments.
            if (binding == null && bindings.size() == textNodes.size()) {
                Binding positional = bindings.get(entry.getKey());
                if (positional != null && !assigned.contains(positional.key) &&
                        !remainingText.contains(positional.text)) {
                    binding = positional;
                }
            }
            String key = binding == null ? base + ".text." + String.format(Locale.ROOT, "%04d", ordinal++) : binding.key;
            assigned.add(key);
            put(key, value);
            node.remove("text");
            node.addProperty("translate", key);
        }
        return json.toString();
    }

    private void put(String key, String value) throws IOException {
        QuestTranslationCatalog.validateKey(key, prefix);
        QuestTranslationCatalog.validateValue(value, prefix);
        source.put(key, value);
    }

    private String nextParagraph() throws IOException {
        int next = 0;
        for (String key : source.keySet()) {
            if (!key.startsWith(descriptionPrefix) || key.length() < descriptionPrefix.length() + 4) continue;
            String number = key.substring(descriptionPrefix.length(), descriptionPrefix.length() + 4);
            if (number.chars().allMatch(Character::isDigit)) next = Math.max(next, Integer.parseInt(number) + 1);
        }
        if (next > 9999) throw new IOException("Quest paragraph ID space exhausted");
        return descriptionPrefix + String.format(Locale.ROOT, "%04d", next);
    }

    private String reference(String raw) {
        if (!raw.startsWith("{" + prefix) || !raw.endsWith("}")) return null;
        String key = raw.substring(1, raw.length() - 1);
        return source.containsKey(key) ? key : null;
    }

    private static JsonElement componentRoot(JsonElement json) {
        // FTB 2001's JSON detector does not recognize arrays beginning with objects.
        // A component root keeps native link-tool output renderable after extraction.
        if (json.isJsonArray()) {
            JsonObject root = new JsonObject();
            root.addProperty("text", "");
            if (!json.getAsJsonArray().isEmpty()) root.add("extra", json);
            return root;
        }
        return json;
    }

    private static JsonElement normalize(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            JsonObject node = new JsonObject();
            node.addProperty("text", json.getAsString());
            return node;
        }
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) array.set(i, normalize(array.get(i)));
        } else if (json.isJsonObject()) {
            JsonObject node = json.getAsJsonObject();
            for (String children : List.of("extra", "with")) {
                if (node.has(children)) node.add(children, normalize(node.get(children)));
            }
            if (node.has("hoverEvent") && node.get("hoverEvent").isJsonObject()) {
                JsonObject hover = node.getAsJsonObject("hoverEvent");
                if (hover.has("action") && "show_text".equals(hover.get("action").getAsString())) {
                    for (String name : List.of("contents", "value")) {
                        if (hover.has(name)) hover.add(name, normalize(hover.get(name)));
                    }
                }
            }
        }
        return json;
    }

    private static void visit(JsonElement json, String path, java.util.function.BiConsumer<JsonObject, String> visitor) {
        if (json.isJsonArray()) {
            for (int i = 0; i < json.getAsJsonArray().size(); i++) visit(json.getAsJsonArray().get(i), path + "/" + i, visitor);
        } else if (json.isJsonObject()) {
            JsonObject node = json.getAsJsonObject();
            visitor.accept(node, path);
            for (String children : List.of("extra", "with")) {
                if (node.has(children)) visit(node.get(children), path + "/" + children, visitor);
            }
            if (node.has("hoverEvent") && node.get("hoverEvent").isJsonObject()) {
                JsonObject hover = node.getAsJsonObject("hoverEvent");
                if (hover.has("action") && "show_text".equals(hover.get("action").getAsString())) {
                    for (String name : List.of("contents", "value")) {
                        if (hover.has(name)) visit(hover.get(name), path + "/hoverEvent/" + name, visitor);
                    }
                }
            }
        }
    }

    private record Binding(String key, String text) {}
}
