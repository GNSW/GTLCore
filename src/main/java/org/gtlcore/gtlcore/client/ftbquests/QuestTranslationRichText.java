package org.gtlcore.gtlcore.client.ftbquests;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Converts FTB formatting at the authoring boundary, never during language lookup. */
public final class QuestTranslationRichText {

    // Literal JSON text must not be interpreted a second time (e.g. an escaped literal "&c").
    private static final String LITERAL = "gtlcore:rich_text";
    private static final Pattern JSON = Pattern.compile("^\\s*(?:\\{\\s*\"|\\[\\s*[\"{\\[])");
    private static final List<String> FLAGS = List.of("bold", "italic", "underlined", "strikethrough", "obfuscated");

    private QuestTranslationRichText() {}

    public static boolean isJson(String text) {
        return JSON.matcher(text).find();
    }

    public static List<String> normalize(List<String> lines) {
        return lines.stream().map(QuestTranslationRichText::normalize).toList();
    }

    public static String normalize(String raw) {
        if (!isJson(raw)) return raw;
        JsonSource source = new JsonSource(raw);
        JsonElement json = JsonParser.parseString(raw.substring(0, source.end));
        boolean legacy = !marked(json);
        json = rewrite(json, "", legacy, null);
        String suffix = raw.substring(source.end);
        if (!suffix.isBlank()) {
            // Recover the reported complete link-object + prose form, without accepting
            // truncated JSON or dropping trailing input as a lenient reader would do.
            String trimmed = suffix.stripLeading();
            if (isJson(trimmed) || "}],:".indexOf(trimmed.charAt(0)) >= 0) throw invalid();
            JsonArray parts = new JsonArray();
            parts.add(json);
            parts.add(plain(suffix));
            json = parts;
        }
        return finish(json);
    }

    public static String link(String raw, int from, int to, long destination) {
        if (from < 0 || to > raw.length() || from >= to || splitsSurrogate(raw, from) || splitsSurrogate(raw, to)) throw selection();
        JsonObject event = new JsonObject();
        event.addProperty("action", "change_page");
        event.addProperty("value", String.format(Locale.ROOT, "%016X", destination));
        Consumer<JsonObject> apply = node -> {
            node.addProperty("underlined", true);
            node.add("clickEvent", event.deepCopy());
        };
        if (isJson(raw)) return editJson(raw, from, to, apply);
        if (raw.contains("{@") || raw.contains("{image:")) throw selection();
        JsonElement json = plain(raw);
        String visible = component(json).getString();
        String before = component(plain(raw.substring(0, from))).getString();
        String through = component(plain(raw.substring(0, to))).getString();
        if (!visible.startsWith(before) || !visible.startsWith(through) || before.length() >= through.length()) throw selection();
        int[] position = { 0 };
        json = visibleRange(json, before.length(), through.length(), position, apply);
        return finish(json);
    }

    public static String format(String raw, int from, int to, String format) {
        JsonObject style = new JsonObject();
        String code = format.startsWith("&") || format.startsWith("§") ? format.substring(1) : format;
        if (code.startsWith("#") && code.matches("#[0-9a-fA-F]{6}")) style.addProperty("color", code);
        else if (code.length() == 1) applyFormat(style, code.charAt(0));
        else throw invalid();
        return editJson(raw, from, to, node -> style.entrySet().forEach(e -> node.add(e.getKey(), e.getValue())));
    }

    private static String editJson(String raw, int from, int to, Consumer<JsonObject> apply) {
        JsonSource source = new JsonSource(raw);
        if (!raw.substring(source.end).isBlank()) throw invalid();
        JsonElement json = JsonParser.parseString(raw);
        Selection selected = new Selection(raw, source.strings, from, to, apply);
        json = rewrite(json, "", !marked(json), selected);
        if (selected.matches == 0) throw selection();
        return finish(json);
    }

    private static boolean marked(JsonElement json) {
        return json.isJsonObject() && json.getAsJsonObject().has(LITERAL) &&
                json.getAsJsonObject().get(LITERAL).equals(new com.google.gson.JsonPrimitive(1));
    }

    private static JsonElement rewrite(JsonElement json, String path, boolean legacy, Selection selected) {
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) array.set(i, rewrite(array.get(i), path + "/" + i, legacy, selected));
            return array;
        }
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            JsonObject node = text(json.getAsString());
            rewriteText(node, path, legacy, selected);
            return node;
        }
        if (!json.isJsonObject()) return json;
        JsonObject node = json.getAsJsonObject();
        legacy &= !marked(node);
        // Visit original children before adding any new text runs.
        for (String name : List.of("extra", "with")) {
            if (node.has(name)) node.add(name, rewrite(node.get(name), path + "/" + name, legacy, selected));
        }
        if (node.has("hoverEvent") && node.get("hoverEvent").isJsonObject()) {
            JsonObject hover = node.getAsJsonObject("hoverEvent");
            if (hover.has("action") && "show_text".equals(hover.get("action").getAsString())) {
                for (String name : List.of("value", "contents")) {
                    if (hover.has(name)) hover.add(name, rewrite(hover.get(name), path + "/hoverEvent/" + name, legacy, selected));
                }
            }
        }
        if (node.has("text") && node.get("text").isJsonPrimitive()) rewriteText(node, path + "/text", legacy, selected);
        return node;
    }

    private static void rewriteText(JsonObject node, String path, boolean legacy, Selection selected) {
        String value = node.get("text").getAsString();
        int[] range = selected == null ? null : selected.range(path, value);
        LegacyText parsed = legacy ? legacy(value) : null;
        if (parsed != null) {
            JsonElement replacement = parsed.json;
            if (range != null) {
                int from = parsed.offsets[range[0]], to = parsed.offsets[range[1]];
                if (from < 0 || to <= from) throw selection();
                replacement = visibleRange(replacement, from, to, new int[1], selected.apply);
            }
            replaceContent(node, replacement);
        } else if (range != null) slice(node, range[0], range[1], selected.apply);
    }

    private static JsonElement visibleRange(JsonElement json, int from, int to, int[] position, Consumer<JsonObject> apply) {
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) array.set(i, visibleRange(array.get(i), from, to, position, apply));
            return array;
        }
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) json = text(json.getAsString());
        if (!json.isJsonObject()) throw selection();
        JsonObject node = json.getAsJsonObject();
        JsonElement extra = node.get("extra");
        int start = position[0];
        String value = node.has("text") ? node.get("text").getAsString() : "";
        position[0] += value.length();
        if (extra != null) visibleRange(extra, from, to, position, apply);
        if (to > start && from < start + value.length()) slice(node, Math.max(0, from - start), Math.min(value.length(), to - start), apply);
        return node;
    }

    private static void slice(JsonObject node, int from, int to, Consumer<JsonObject> apply) {
        String value = node.get("text").getAsString();
        if (from == 0 && to == value.length() && !node.has("extra")) {
            apply.accept(node);
            return;
        }
        JsonArray parts = new JsonArray();
        if (from > 0) parts.add(text(value.substring(0, from)));
        JsonObject middle = text(value.substring(from, to));
        apply.accept(middle);
        parts.add(middle);
        if (to < value.length()) parts.add(text(value.substring(to)));
        replaceContent(node, parts);
    }

    private static void replaceContent(JsonObject node, JsonElement content) {
        JsonArray parts = new JsonArray();
        if (content.isJsonArray()) parts.addAll(content.getAsJsonArray());
        else parts.add(content);
        if (node.has("extra")) parts.addAll(node.getAsJsonArray("extra"));
        node.addProperty("text", "");
        if (parts.isEmpty()) node.remove("extra");
        else node.add("extra", parts);
    }

    private static JsonObject text(String value) {
        JsonObject node = new JsonObject();
        node.addProperty("text", value);
        return node;
    }

    private static String finish(JsonElement json) {
        JsonObject root;
        if (json.isJsonObject()) root = json.getAsJsonObject();
        else {
            root = text("");
            if (json.isJsonArray()) {
                if (!json.getAsJsonArray().isEmpty()) root.add("extra", json);
            } else throw invalid();
        }
        root.addProperty(LITERAL, 1);
        component(root); // Validate without reserializing away events or our literal-text marker.
        return root.toString();
    }

    private static Component component(JsonElement json) {
        Component result = Component.Serializer.fromJson(json);
        if (result == null) throw invalid();
        return result;
    }

    private static JsonElement plain(String text) {
        try {
            Class<?> parser = Class.forName("dev.ftb.mods.ftblibrary.util.client.ClientTextComponentUtils");
            Component parsed = (Component) parser.getMethod("parse", String.class).invoke(null, text);
            if (parsed.getString().startsWith("Invalid formatting!") || parsed.getString().startsWith("java.")) throw invalid();
            return Component.Serializer.toJsonTree(parsed);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("FTB text parser unavailable", e);
        }
    }

    /** JSON text is literal: only known legacy formatting tokens are interpreted during repair. */
    private static LegacyText legacy(String value) {
        JsonArray parts = new JsonArray();
        JsonObject style = new JsonObject();
        StringBuilder part = new StringBuilder();
        int[] offsets = new int[value.length() + 1];
        Arrays.fill(offsets, -1);
        int visible = 0;
        boolean changed = false;
        for (int i = 0; i < value.length();) {
            offsets[i] = visible;
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && (value.charAt(i + 1) == '&' || value.charAt(i + 1) == '§')) {
                part.append(value.charAt(i + 1));
                i += 2;
                visible++;
                changed = true;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < value.length()) {
                char code = value.charAt(i + 1);
                int length = ChatFormatting.getByCode(code) != null ? 2 : 0;
                if (code == '#' && i + 8 <= value.length() && value.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) length = 8;
                if (length > 0) {
                    flush(parts, part, style);
                    if (length == 8) style.addProperty("color", value.substring(i + 1, i + 8));
                    else applyFormat(style, code);
                    i += length;
                    changed = true;
                    continue;
                }
            }
            part.append(c);
            i++;
            visible++;
        }
        offsets[value.length()] = visible;
        flush(parts, part, style);
        return changed ? new LegacyText(parts, offsets) : null;
    }

    private static void flush(JsonArray parts, StringBuilder text, JsonObject style) {
        if (text.isEmpty()) return;
        JsonObject node = style.deepCopy();
        node.addProperty("text", text.toString());
        parts.add(node);
        text.setLength(0);
    }

    private static void applyFormat(JsonObject style, char code) {
        ChatFormatting format = ChatFormatting.getByCode(code);
        if (format == null) throw invalid();
        if (format == ChatFormatting.RESET) {
            style.addProperty("color", "white");
            FLAGS.forEach(flag -> style.addProperty(flag, false));
        } else if (format.isColor()) style.addProperty("color", format.getName());
        else style.addProperty(format == ChatFormatting.UNDERLINE ? "underlined" : format.getName(), true);
    }

    private record LegacyText(JsonElement json, int[] offsets) {}

    private static IllegalArgumentException selection() {
        return new IllegalArgumentException(Component.translatable("gtlcore.quest_i18n.rich_selection").getString());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(Component.translatable("gtlcore.quest_i18n.rich_invalid").getString());
    }

    private static final class Selection {

        private final String raw;
        private final Map<String, Span> spans;
        private final int from, to;
        private final Consumer<JsonObject> apply;
        private int matches;

        private Selection(String raw, Map<String, Span> spans, int from, int to, Consumer<JsonObject> apply) {
            this.raw = raw;
            this.spans = spans;
            this.from = from;
            this.to = to;
            this.apply = apply;
        }

        private int[] range(String path, String value) {
            Span span = spans.get(path);
            if (span == null || to <= span.start + 1 || from >= span.end - 1 || from == to || value.isEmpty()) return null;
            int a = decode(span, Math.max(from, span.start + 1));
            int b = decode(span, Math.min(to, span.end - 1));
            if (a == b || splitsSurrogate(value, a) || splitsSurrogate(value, b)) throw selection();
            matches++;
            return new int[] { a, b };
        }

        private int decode(Span span, int position) {
            // Decoding the selected prefix also rejects a cursor splitting a JSON escape.
            return JsonParser.parseString("\"" + raw.substring(span.start + 1, position) + "\"").getAsString().length();
        }
    }

    private record Span(int start, int end) {}

    private static boolean splitsSurrogate(String value, int index) {
        return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1)) &&
                Character.isLowSurrogate(value.charAt(index));
    }

    /** Source positions for JSON string values; Gson remains the authority for validity. */
    private static final class JsonSource {

        private final String raw;
        private final Map<String, Span> strings = new LinkedHashMap<>();
        private int end;

        private JsonSource(String raw) {
            this.raw = raw;
            value("", 0);
            JsonParser.parseString(raw.substring(0, end));
        }

        private void whitespace() {
            while (end < raw.length() && Character.isWhitespace(raw.charAt(end))) end++;
        }

        private void expect(char c) {
            whitespace();
            if (end >= raw.length() || raw.charAt(end++) != c) throw invalid();
        }

        private Span string() {
            whitespace();
            int start = end;
            expect('"');
            while (end < raw.length()) {
                char c = raw.charAt(end++);
                if (c == '"') return new Span(start, end);
                if (c == '\\') end++;
            }
            throw invalid();
        }

        private void value(String path, int depth) {
            if (depth > 128) throw invalid();
            whitespace();
            if (end >= raw.length()) throw invalid();
            char c = raw.charAt(end);
            if (c == '"') strings.put(path, string());
            else if (c == '{' || c == '[') {
                end++;
                whitespace();
                char close = c == '{' ? '}' : ']';
                int index = 0;
                if (end < raw.length() && raw.charAt(end) != close) {
                    while (true) {
                        String name = Integer.toString(index++);
                        if (c == '{') {
                            Span key = string();
                            name = JsonParser.parseString(raw.substring(key.start, key.end)).getAsString();
                            expect(':');
                        }
                        value(path + "/" + name.replace("~", "~0").replace("/", "~1"), depth + 1);
                        whitespace();
                        if (end >= raw.length()) throw invalid();
                        if (raw.charAt(end) == close) break;
                        expect(',');
                    }
                }
                expect(close);
            } else {
                int start = end;
                while (end < raw.length() && ",]}".indexOf(raw.charAt(end)) < 0 && !Character.isWhitespace(raw.charAt(end))) end++;
                if (end == start) throw invalid();
            }
        }
    }
}
