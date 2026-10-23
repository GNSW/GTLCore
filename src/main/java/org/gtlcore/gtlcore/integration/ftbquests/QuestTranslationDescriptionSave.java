package org.gtlcore.gtlcore.integration.ftbquests;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Saves a source paragraph and its stable references on the server, with conflict checks. */
final class QuestTranslationDescriptionSave {

    private QuestTranslationDescriptionSave() {}

    @SuppressWarnings("unchecked")
    static void save(Object file, QuestTranslationCatalog catalog, MinecraftServer server,
                     QuestTranslationNetwork.DescriptionEdit edit) throws IOException {
        if (edit.expected() == null || edit.lines() == null || edit.edits() == null ||
                edit.expected().size() > 4096 || edit.lines().size() > 4096 || edit.edits().size() > 4096) {
            throw new IOException("Invalid quest paragraph edit");
        }
        var snapshot = catalog.snapshot("en_us", true);
        Map<String, String> beforeSource = snapshot.source();
        Map<String, String> afterSource = new TreeMap<>(beforeSource);
        for (var entry : edit.edits().entrySet()) {
            if (entry.getValue() == null) throw new IOException("Invalid quest source edit");
            afterSource.put(entry.getKey(), entry.getValue().value());
        }
        Pattern reference = Pattern.compile("\\{(" + Pattern.quote(snapshot.keyPrefix()) + "[a-z0-9_.-]+)}");
        for (String line : edit.lines()) {
            if (line == null || line.length() > 65536 || line.indexOf('\0') >= 0) throw new IOException("Invalid quest paragraph");
            var matcher = reference.matcher(line);
            while (matcher.find()) requireKey(matcher.group(1), afterSource);
            if (line.matches("(?s)^[{\\[]\\s*\".*")) {
                try {
                    validateJson(JsonParser.parseString(line), afterSource, snapshot.keyPrefix());
                    if (Component.Serializer.fromJson(line) == null) throw new IOException("Empty rich quest paragraph");
                } catch (RuntimeException e) {
                    throw new IOException("Invalid rich quest paragraph", e);
                }
            }
        }
        try {
            Object quest = file.getClass().getMethod("getQuest", long.class).invoke(file, edit.questId());
            if (quest == null) throw new IOException("Quest no longer exists");
            List<String> raw = (List<String>) quest.getClass().getMethod("getRawDescription").invoke(quest);
            if (!raw.equals(edit.expected())) throw new IOException("Quest paragraphs changed; reopen the editor before saving");
            catalog.editSource(edit.edits());
            Class<?> base = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase");
            try {
                raw.clear();
                raw.addAll(edit.lines());
                base.getMethod("clearCachedData").invoke(quest);
                file.getClass().getMethod("markDirty").invoke(file);
                file.getClass().getMethod("saveNow").invoke(file);
            } catch (ReflectiveOperationException | RuntimeException e) {
                raw.clear();
                raw.addAll(edit.expected());
                try {
                    catalog.restoreSource(afterSource, beforeSource);
                    base.getMethod("clearCachedData").invoke(quest);
                    file.getClass().getMethod("markDirty").invoke(file);
                    file.getClass().getMethod("saveNow").invoke(file);
                } catch (IOException | ReflectiveOperationException | RuntimeException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
            Class<?> response = Class.forName("dev.ftb.mods.ftbquests.net.EditObjectResponseMessage");
            Object packet = response.getConstructor(base).newInstance(quest);
            response.getMethod("sendToAll", MinecraftServer.class).invoke(packet, server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Cannot save FTB quest paragraphs", e);
        }
    }

    private static void requireKey(String key, Map<String, String> source) throws IOException {
        if (!source.containsKey(key)) throw new IOException("Unknown quest text key: " + key);
    }

    private static void validateJson(JsonElement json, Map<String, String> source, String prefix) throws IOException {
        if (json.isJsonArray()) {
            for (JsonElement node : json.getAsJsonArray()) validateJson(node, source, prefix);
        } else if (json.isJsonObject()) {
            var node = json.getAsJsonObject();
            if (node.has("translate")) {
                String key = node.get("translate").getAsString();
                if (key.startsWith(prefix)) requireKey(key, source);
            }
            for (var entry : node.entrySet()) {
                if (entry.getValue().isJsonArray() || entry.getValue().isJsonObject()) validateJson(entry.getValue(), source, prefix);
            }
        }
    }
}
