package org.gtlcore.gtlcore.integration.ftbquests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Server-owned quest dictionaries. No Minecraft classes: the file format is usable by external tools. */
public final class QuestTranslationCatalog {

    public static final int MAX_VALUE_LENGTH = 16384;
    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ENTRIES = 100000;
    public static final int MAX_LANGUAGES = 256;
    public static final String MANIFEST = "gtlcore-i18n.json";
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,8}(?:_[a-z0-9]{2,8}){0,2}");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+");
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final Path directory;
    private final String defaultLanguage;
    private final String keyPrefix;
    private final Map<String, Map<String, String>> languages;

    private QuestTranslationCatalog(Path directory, String defaultLanguage, String keyPrefix,
                                    Map<String, Map<String, String>> languages) {
        this.directory = directory;
        this.defaultLanguage = defaultLanguage;
        this.keyPrefix = keyPrefix;
        this.languages = new TreeMap<>(languages);
    }

    /** Returns null for a quest book which has not opted into the format. */
    public static QuestTranslationCatalog load(Path questDirectory) throws IOException {
        Path directory = questDirectory.resolve("lang");
        Path manifest = directory.resolve(MANIFEST);
        if (!Files.exists(manifest)) return null;
        Map<String, String> config = readManifest(readBounded(manifest));
        String source = config.get("default_language");
        String prefix = config.get("key_prefix");
        validateLocale(source);
        if (prefix == null || !prefix.startsWith("gtlcore.quest.") || !prefix.endsWith(".") ||
                prefix.length() > 128 || !KEY.matcher(prefix).matches()) {
            throw new IOException("Invalid quest translation key_prefix");
        }
        Map<String, Map<String, String>> languages = new TreeMap<>();
        long total = Files.size(manifest);
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if (file.equals(manifest)) continue;
                String name = file.getFileName().toString();
                String locale = name.substring(0, name.length() - 5);
                validateLocale(locale);
                total += Files.size(file);
                if (total > MAX_TOTAL_BYTES || languages.size() >= MAX_LANGUAGES) {
                    throw new IOException("Quest translations exceed the catalog size limit");
                }
                languages.put(locale, readDictionary(readBounded(file), prefix));
            }
        }
        if (!languages.containsKey(source)) throw new IOException("Missing quest source language: " + source);
        for (var language : languages.entrySet()) {
            if (!languages.get(source).keySet().containsAll(language.getValue().keySet())) {
                throw new IOException("Unknown quest translation keys in " + language.getKey());
            }
        }
        return new QuestTranslationCatalog(directory, source, prefix, languages);
    }

    private static String readBounded(Path path) throws IOException {
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Quest language file too large: " + path.getFileName());
        // Bound the read as well as the initial stat (external editors may replace a file).
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Quest language file too large");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static Map<String, String> readManifest(String text) throws IOException {
        Map<String, String> values = new TreeMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (values.containsKey(key)) throw new IOException("Duplicate manifest field: " + key);
                if (key.equals("format")) {
                    if (reader.peek() != JsonToken.NUMBER || !reader.nextString().equals("1")) {
                        throw new IOException("Unsupported quest translation format");
                    }
                    values.put(key, "1");
                } else if (key.equals("default_language") || key.equals("key_prefix")) {
                    if (reader.peek() != JsonToken.STRING) throw new IOException("Manifest values must be strings");
                    values.put(key, reader.nextString());
                } else {
                    throw new IOException("Unknown quest translation manifest field: " + key);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !"1".equals(values.get("format"))) {
                throw new IOException("Invalid quest translation manifest");
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Invalid quest translation manifest", e);
        }
        return values;
    }

    public static Map<String, String> readDictionary(String text, String prefix) throws IOException {
        Map<String, String> values = new TreeMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                validateKey(key, prefix);
                if (values.size() >= MAX_ENTRIES || values.containsKey(key)) throw new IOException("Too many or duplicate quest translation keys: " + key);
                if (reader.peek() != JsonToken.STRING) throw new IOException("Quest translations must be a flat string dictionary: " + key);
                String value = reader.nextString();
                validateValue(value, prefix);
                values.put(key, value);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing quest language data");
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Invalid quest language JSON", e);
        }
        return Collections.unmodifiableMap(values);
    }

    public static void validateLocale(String locale) throws IOException {
        if (locale == null || !LOCALE.matcher(locale).matches()) throw new IOException("Invalid language code");
    }

    public static void validateKey(String key, String prefix) throws IOException {
        if (key == null || key.length() > MAX_KEY_LENGTH || !key.startsWith(prefix) || !KEY.matcher(key).matches()) {
            throw new IOException("Invalid quest translation key");
        }
    }

    public static void validateValue(String value, String prefix) throws IOException {
        if (value == null || value.length() > MAX_VALUE_LENGTH || value.indexOf('\0') >= 0) throw new IOException("Invalid quest translation text");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IOException("Invalid Unicode in quest translation");
            } else if (Character.isLowSurrogate(character)) {
                throw new IOException("Invalid Unicode in quest translation");
            }
        }
        // FTB reparses translated text recursively. Disallow self/cross-key cycles in this namespace.
        if (value.contains("{" + prefix)) throw new IOException("Quest translations cannot reference quest translation keys");
        if (value.contains("{@") || value.contains("{image:")) throw new IOException("Page and image controls belong in quest structure, not translations");
    }

    public synchronized Snapshot snapshot(String locale, boolean canEdit) throws IOException {
        validateLocale(locale);
        return new Snapshot(true, defaultLanguage, keyPrefix, locale, List.copyOf(languages.keySet()),
                languages.get(defaultLanguage), languages.getOrDefault(locale, Map.of()), canEdit);
    }

    /** Compare-and-set one key, so another language or an unrelated key cannot be overwritten. */
    public synchronized void edit(String locale, String key, String expected, String replacement) throws IOException {
        validateLocale(locale);
        validateKey(key, keyPrefix);
        if (!languages.get(defaultLanguage).containsKey(key)) throw new IOException("Unknown quest translation key");
        if (replacement != null) validateValue(replacement, keyPrefix);
        if (locale.equals(defaultLanguage) && replacement == null) throw new IOException("The source translation cannot be removed");
        if (!languages.containsKey(locale) && languages.size() >= MAX_LANGUAGES) throw new IOException("Too many quest languages");
        Map<String, String> previous = languages.getOrDefault(locale, Map.of());
        if (!Objects.equals(expected, previous.get(key))) throw new IOException("Translation changed by another editor; reload before saving");
        Map<String, String> changed = new TreeMap<>(previous);
        if (replacement == null) changed.remove(key);
        else changed.put(key, replacement);
        save(locale, previous, changed);
    }

    /** Compare-and-set source text; a null expected value denotes a new key. */
    public record SourceEdit(String expected, String value) {}

    /** Commits a paragraph draft's source edits together, after checking every original value. */
    public synchronized void editSource(Map<String, SourceEdit> edits) throws IOException {
        Map<String, String> previous = languages.get(defaultLanguage);
        Map<String, String> changed = new TreeMap<>(previous);
        for (var entry : edits.entrySet()) {
            validateKey(entry.getKey(), keyPrefix);
            SourceEdit edit = entry.getValue();
            if (edit == null) throw new IOException("Missing quest source edit");
            validateValue(edit.value, keyPrefix);
            if (!Objects.equals(previous.get(entry.getKey()), edit.expected)) {
                throw new IOException("Quest source changed by another editor; reopen the paragraph before saving");
            }
            changed.put(entry.getKey(), edit.value);
        }
        if (changed.size() > MAX_ENTRIES) throw new IOException("Too many quest translation keys");
        if (!changed.equals(previous)) save(defaultLanguage, previous, changed);
    }

    synchronized void restoreSource(Map<String, String> expected, Map<String, String> replacement) throws IOException {
        Map<String, String> previous = languages.get(defaultLanguage);
        if (!previous.equals(expected)) throw new IOException("Quest source changed during rollback");
        if (!previous.equals(replacement)) save(defaultLanguage, previous, replacement);
    }

    /** Adds newly authored text before the quest is allowed to reference its keys. */
    public synchronized void addSource(Map<String, String> additions) throws IOException {
        if (additions.isEmpty()) return;
        Map<String, String> previous = languages.get(defaultLanguage);
        Map<String, String> changed = new TreeMap<>(previous);
        for (var entry : additions.entrySet()) {
            validateKey(entry.getKey(), keyPrefix);
            validateValue(entry.getValue(), keyPrefix);
            if (changed.containsKey(entry.getKey()) && !changed.get(entry.getKey()).equals(entry.getValue())) {
                throw new IOException("Quest source key already exists: " + entry.getKey());
            }
            changed.put(entry.getKey(), entry.getValue());
        }
        if (changed.size() > MAX_ENTRIES) throw new IOException("Too many quest translation keys");
        save(defaultLanguage, previous, changed);
    }

    /** Copies translations for a duplicated quest before the new object starts using its own keys. */
    public synchronized void copyTranslations(Map<String, String> newToOld) throws IOException {
        for (String locale : List.copyOf(languages.keySet())) {
            if (locale.equals(defaultLanguage)) continue;
            Map<String, String> previous = languages.get(locale);
            Map<String, String> changed = new TreeMap<>(previous);
            for (var entry : newToOld.entrySet()) {
                if (!languages.get(defaultLanguage).containsKey(entry.getKey())) throw new IOException("Missing copied quest source key");
                if (previous.containsKey(entry.getValue()) && !changed.containsKey(entry.getKey())) {
                    changed.put(entry.getKey(), previous.get(entry.getValue()));
                }
            }
            if (!changed.equals(previous)) save(locale, previous, changed);
        }
    }

    private void save(String locale, Map<String, String> previous, Map<String, String> changed) throws IOException {
        byte[] encoded = (JSON.toJson(changed) + "\n").getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_FILE_BYTES) throw new IOException("Quest language file too large");
        Path target = directory.resolve(locale + ".json");
        long total = encoded.length;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json") && !p.equals(target)).toList()) {
                total += Files.size(file);
                if (total > MAX_TOTAL_BYTES) throw new IOException("Quest translations exceed the catalog size limit");
            }
        }
        // Refuse to silently overwrite edits made by an external translator since the last reload.
        Map<String, String> onDisk = Files.exists(target) ? readDictionary(readBounded(target), keyPrefix) : Map.of();
        if (!onDisk.equals(previous)) throw new IOException("Language file changed on disk; reload translations before saving");
        Path temporary = Files.createTempFile(directory, locale + ".", ".tmp");
        try {
            Files.write(temporary, encoded);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("This filesystem cannot atomically save quest translations", e);
            }
            languages.put(locale, Collections.unmodifiableMap(changed));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Snapshot(boolean enabled, String defaultLanguage, String keyPrefix, String locale,
                           List<String> languages, Map<String, String> source, Map<String, String> translations,
                           boolean canEdit) {

        public static Snapshot disabled(String locale) {
            return disabled(locale, false);
        }

        public static Snapshot disabled(String locale, boolean canEdit) {
            return new Snapshot(false, "zh_cn", "gtlcore.quest.gtl.", locale, List.of(), Map.of(), Map.of(), canEdit);
        }
    }
}
