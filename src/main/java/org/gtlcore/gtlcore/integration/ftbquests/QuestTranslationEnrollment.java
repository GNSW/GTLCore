package org.gtlcore.gtlcore.integration.ftbquests;

import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Explicit in-game enrollment for text added with the normal FTB structure editor. */
final class QuestTranslationEnrollment {

    private static final Pattern REFERENCE = Pattern.compile("\\{[A-Za-z0-9_./:-]+}");
    private final String prefix;
    private final Map<String, String> source;
    private final Map<String, String> additions = new TreeMap<>();
    private final Map<String, String> copies = new TreeMap<>();
    private String objectPrefix;

    private QuestTranslationEnrollment(QuestTranslationCatalog.Snapshot snapshot) {
        prefix = snapshot.keyPrefix();
        source = new TreeMap<>(snapshot.source());
    }

    static int enroll(Object file, QuestTranslationCatalog catalog, MinecraftServer server) throws IOException {
        var enrollment = new QuestTranslationEnrollment(catalog.snapshot("en_us", true));
        try {
            Class<?> baseClass = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase");
            var kindMethod = baseClass.getMethod("getObjectType");
            var idField = baseClass.getField("id");
            var objects = new LinkedHashSet<Object>();
            objects.add(file);
            objects.addAll((Collection<?>) file.getClass().getMethod("getAllObjects").invoke(file));
            var changes = new ArrayList<Change>();
            for (Object object : objects) {
                String type = ((Enum<?>) kindMethod.invoke(object)).name();
                String kind = switch (type) {
                    case "FILE" -> "book";
                    case "CHAPTER_GROUP" -> "group";
                    default -> type.toLowerCase(Locale.ROOT);
                };
                if (type.equals("NULL")) continue;
                String id = type.equals("FILE") ? "root" : String.format(Locale.ROOT, "%016x", idField.getLong(object));
                List<String> fields = switch (type) {
                    case "FILE" -> List.of("title", "lock_message");
                    case "CHAPTER" -> List.of("title", "subtitle");
                    case "QUEST" -> List.of("title", "subtitle", "description");
                    default -> List.of("title");
                };
                enrollment.objectPrefix = enrollment.prefix + kind + "." + id + ".";
                var changed = new ArrayList<FieldChange>();
                for (String name : fields) {
                    String member = switch (name) {
                        case "title" -> "rawTitle";
                        case "subtitle" -> "rawSubtitle";
                        case "description" -> "rawDescription";
                        case "lock_message" -> "lockMessage";
                        default -> name;
                    };
                    Field field = field(object.getClass(), member);
                    Object original = field.get(object);
                    Object translated = enrollment.value(original, enrollment.objectPrefix + name);
                    if (!original.equals(translated)) changed.add(new FieldChange(field, translated));
                }
                if (!changed.isEmpty()) changes.add(new Change(object, changed));
            }
            // Persist text first: a failed quest save can leave unused entries, never dangling new references.
            catalog.addSource(enrollment.additions);
            catalog.copyTranslations(enrollment.copies);
            for (Change change : changes) {
                for (FieldChange field : change.fields) {
                    if (field.value instanceof List<?> paragraphs) {
                        @SuppressWarnings("unchecked")
                        List<String> target = (List<String>) field.field.get(change.object);
                        target.clear();
                        for (Object paragraph : paragraphs) target.add((String) paragraph);
                    } else {
                        field.field.set(change.object, field.value);
                    }
                }
                baseClass.getMethod("clearCachedData").invoke(change.object);
            }
            if (!changes.isEmpty()) {
                file.getClass().getMethod("clearCachedData").invoke(file);
                file.getClass().getMethod("markDirty").invoke(file);
                file.getClass().getMethod("saveNow").invoke(file);
                Class<?> response = Class.forName("dev.ftb.mods.ftbquests.net.EditObjectResponseMessage");
                for (Change change : changes) {
                    Object packet = response.getConstructor(baseClass).newInstance(change.object);
                    response.getMethod("sendToAll", MinecraftServer.class).invoke(packet, server);
                }
            }
            return enrollment.additions.size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Cannot enroll FTB quest text; check the installed FTB Quests version", e);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private Object value(Object value, String base) throws IOException {
        if (value instanceof String string) {
            return line(string, base);
        } else if (value instanceof List<?> raw) {
            var list = new ArrayList<String>();
            for (Object entry : raw) {
                if (!(entry instanceof String text)) throw new IOException("Unexpected non-string quest paragraph");
                list.add(text);
            }
            int next = nextSegment(base);
            for (int i = 0; i < list.size(); i++) {
                String text = list.get(i);
                if (skipControl(text)) continue;
                String key = next > 9999 ? null : base + "." + String.format(Locale.ROOT, "%04d", next);
                String translated = line(text, key);
                if (!translated.equals(text)) {
                    list.set(i, translated);
                    next = Math.max(next + 1, nextSegment(base));
                }
            }
            return list;
        }
        throw new IOException("Unexpected quest text field type");
    }

    private int nextSegment(String base) {
        int next = 0;
        String start = base + ".";
        for (String key : source.keySet()) {
            if (!key.startsWith(start) || key.length() < start.length() + 4) continue;
            String segment = key.substring(start.length(), start.length() + 4);
            if (segment.chars().allMatch(Character::isDigit)) next = Math.max(next, Integer.parseInt(segment) + 1);
        }
        return next;
    }

    private static boolean skipControl(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() || trimmed.startsWith("{@") || trimmed.startsWith("{image:");
    }

    private String line(String text, String base) throws IOException {
        if (skipControl(text)) return text;
        String trimmed = text.trim();
        if (REFERENCE.matcher(trimmed).matches()) {
            String old = trimmed.substring(1, trimmed.length() - 1);
            String key = copyKey(old);
            return key.equals(old) ? text : "{" + key + "}";
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonElement original = JsonParser.parseString(text);
                JsonElement converted = component(original.deepCopy(), base, new int[] { 0 });
                return converted.equals(original) ? text : converted.toString();
            } catch (RuntimeException e) {
                throw new IOException("Invalid rich text at " + base, e);
            }
        }
        return "{" + put(base, text) + "}";
    }

    private String copyKey(String key) throws IOException {
        if (!key.startsWith(prefix) || key.startsWith(objectPrefix) || !source.containsKey(key)) return key;
        String[] parts = key.substring(prefix.length()).split("\\.", 3);
        // Generated keys are owned by an object. Explicit references to another kind remain shared.
        if (parts.length != 3 || !objectPrefix.startsWith(prefix + parts[0] + ".")) return key;
        String copy = put(objectPrefix + parts[2], source.get(key));
        copies.put(copy, key);
        return copy;
    }

    private String put(String candidate, String text) throws IOException {
        if (candidate == null) throw new IOException("Quest paragraph ID space exhausted");
        String key = candidate;
        int next = 0;
        String collisionBase = candidate;
        var suffix = Pattern.compile("\\.text\\.([0-9]{4})$").matcher(candidate);
        if (suffix.find()) {
            collisionBase = candidate.substring(0, suffix.start());
            next = Integer.parseInt(suffix.group(1)) + 1;
        }
        while (source.containsKey(key) && !source.get(key).equals(text)) {
            if (next > 9999) throw new IOException("Quest text ID space exhausted");
            key = collisionBase + ".text." + String.format(Locale.ROOT, "%04d", next++);
        }
        if (!source.containsKey(key)) {
            QuestTranslationCatalog.validateKey(key, prefix);
            QuestTranslationCatalog.validateValue(text, prefix);
            additions.put(key, text);
            source.put(key, text);
        }
        return key;
    }

    private JsonElement component(JsonElement value, String base, int[] ordinal) throws IOException {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            if (value.getAsString().isEmpty()) return value;
            JsonObject translated = new JsonObject();
            translated.addProperty("translate", put(textKey(base, ordinal), value.getAsString()));
            return translated;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) array.set(i, component(array.get(i), base, ordinal));
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("translate") && object.get("translate").isJsonPrimitive()) {
                object.addProperty("translate", copyKey(object.get("translate").getAsString()));
            }
            if (object.has("text") && !object.get("text").getAsString().isEmpty()) {
                if (object.has("translate")) throw new IOException("Rich text contains both text and translate");
                String key = put(textKey(base, ordinal), object.remove("text").getAsString());
                object.addProperty("translate", key);
            }
            for (String children : List.of("extra", "with")) {
                if (object.has(children)) object.add(children, component(object.get(children), base, ordinal));
            }
            if (object.has("hoverEvent") && object.get("hoverEvent").isJsonObject()) {
                JsonObject hover = object.getAsJsonObject("hoverEvent");
                if (hover.has("action") && "show_text".equals(hover.get("action").getAsString())) {
                    for (String text : List.of("contents", "value")) {
                        if (hover.has(text)) hover.add(text, component(hover.get(text), base, ordinal));
                    }
                }
            }
        }
        return value;
    }

    private static String textKey(String base, int[] ordinal) throws IOException {
        if (base == null || ordinal[0] > 9999) throw new IOException("Quest text ID space exhausted");
        return base + ".text." + String.format(Locale.ROOT, "%04d", ordinal[0]++);
    }

    private record FieldChange(Field field, Object value) {}

    private record Change(Object object, List<FieldChange> fields) {}
}
