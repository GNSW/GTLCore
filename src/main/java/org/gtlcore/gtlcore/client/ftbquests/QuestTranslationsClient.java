package org.gtlcore.gtlcore.client.ftbquests;

import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationCatalog;
import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationNetwork;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The server supplies dictionaries, but only this client chooses its display language. */
public final class QuestTranslationsClient implements QuestTranslationNetwork.ClientReceiver {

    private static final QuestTranslationsClient INSTANCE = new QuestTranslationsClient();
    private static final Map<String, QuestTranslationCatalog.Snapshot> SNAPSHOTS = new HashMap<>();
    private static final Set<String> REQUESTED = new HashSet<>();
    private static final Map<String, Integer> REQUEST_TICKS = new HashMap<>();
    private static final Set<String> STALE = new HashSet<>();
    private static Map<String, String> source = Map.of();
    private static String prefix = "gtlcore.quest.gtl.";
    private static String selectedLanguage = "";
    private static long generation;
    private static boolean registered;
    private static boolean refreshPending;
    private static int ticks;

    private QuestTranslationsClient() {}

    public static void init() {
        if (registered) return;
        registered = true;
        QuestTranslationNetwork.setClientReceiver(INSTANCE);
        MinecraftForge.EVENT_BUS.addListener(QuestTranslationsClient::tick);
        MinecraftForge.EVENT_BUS.addListener(QuestTranslationsClient::loggedOut);
    }

    public static long generation() {
        return generation;
    }

    public static String language() {
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

    public static String prefix() {
        return prefix;
    }

    public static boolean managedKey(String key) {
        return key.startsWith(prefix);
    }

    /** Null means leave Minecraft/resource-pack translation lookup unchanged. */
    public static String lookup(String key) {
        if (!managedKey(key)) return null;
        QuestTranslationCatalog.Snapshot snapshot = SNAPSHOTS.get(language());
        if (snapshot != null && snapshot.enabled() && snapshot.translations().containsKey(key)) {
            return snapshot.translations().get(key);
        }
        return source.get(key);
    }

    public static QuestTranslationCatalog.Snapshot snapshot(String locale) {
        // Keep the last complete dictionary for display while editors wait for fresh authority.
        return STALE.contains(locale) ? null : SNAPSHOTS.get(locale);
    }

    public static boolean available() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && QuestTranslationNetwork.isAvailable(connection.getConnection());
    }

    public static void request(String locale, boolean force) {
        boolean timedOut = (!SNAPSHOTS.containsKey(locale) || STALE.contains(locale)) &&
                ticks - REQUEST_TICKS.getOrDefault(locale, ticks) >= 200;
        if (available() && (force || !REQUESTED.contains(locale) || timedOut)) {
            REQUESTED.add(locale);
            REQUEST_TICKS.put(locale, ticks);
            QuestTranslationNetwork.request(locale);
        }
    }

    private static void changed() {
        generation++;
        refreshPending = true;
    }

    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ticks++;
        Minecraft minecraft = Minecraft.getInstance();
        QuestTranslationNativeEditor.tick();
        if (minecraft.getConnection() == null) return;
        String locale = language();
        if (!selectedLanguage.equals(locale)) {
            selectedLanguage = locale;
            changed();
        }
        request(locale, false);
        if (refreshPending && !QuestTranslationNativeEditor.active()) {
            refreshPending = false;
            QuestTranslationAccess.refresh();
        }
    }

    private static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        QuestTranslationNativeEditor.clear();
        SNAPSHOTS.clear();
        STALE.clear();
        REQUESTED.clear();
        REQUEST_TICKS.clear();
        source = Map.of();
        prefix = "gtlcore.quest.gtl.";
        selectedLanguage = "";
        QuestTranslationNetwork.clearClientTransfer();
        changed();
    }

    @Override
    public void acceptSnapshot(QuestTranslationCatalog.Snapshot snapshot) {
        if (!snapshot.enabled()) {
            SNAPSHOTS.clear();
            STALE.clear();
            source = Map.of();
        } else {
            if (!prefix.equals(snapshot.keyPrefix())) {
                SNAPSHOTS.clear();
                STALE.clear();
            }
            // All locale views share the newly received source. A later edit broadcast must
            // never republish an older source map from a cached, inactive display locale.
            for (var entry : new ArrayList<>(SNAPSHOTS.entrySet())) {
                var previous = entry.getValue();
                SNAPSHOTS.put(entry.getKey(), new QuestTranslationCatalog.Snapshot(previous.enabled(),
                        snapshot.defaultLanguage(), snapshot.keyPrefix(), previous.locale(), snapshot.languages(),
                        snapshot.source(), previous.locale().equals(snapshot.defaultLanguage()) ? snapshot.source() : previous.translations(),
                        previous.canEdit()));
            }
        }
        SNAPSHOTS.put(snapshot.locale(), snapshot);
        STALE.remove(snapshot.locale());
        REQUESTED.add(snapshot.locale());
        if (snapshot.enabled()) {
            prefix = snapshot.keyPrefix();
            source = snapshot.source();
        }
        changed();
        if (Minecraft.getInstance().screen instanceof QuestTranslationScreen screen) screen.received(snapshot);
    }

    @Override
    public void edited(String locale, String key, String value, boolean success, String message) {
        if (success && !key.isEmpty() && !locale.isEmpty()) {
            for (var entry : new ArrayList<>(SNAPSHOTS.entrySet())) {
                var previous = entry.getValue();
                Map<String, String> sourceValues = previous.source();
                Map<String, String> translations = previous.translations();
                if (previous.defaultLanguage().equals(locale)) sourceValues = patched(sourceValues, key, value);
                if (previous.locale().equals(locale)) translations = patched(translations, key, value);
                List<String> languages = previous.languages();
                if (!languages.contains(locale)) {
                    languages = new ArrayList<>(languages);
                    languages.add(locale);
                }
                SNAPSHOTS.put(entry.getKey(), new QuestTranslationCatalog.Snapshot(previous.enabled(),
                        previous.defaultLanguage(), previous.keyPrefix(), previous.locale(), List.copyOf(languages),
                        sourceValues, translations, previous.canEdit()));
                source = sourceValues;
            }
            changed();
        }
        QuestTranslationNativeEditor.edited(locale, key, value, success, message);
        if (Minecraft.getInstance().screen instanceof QuestTranslationScreen screen) {
            screen.edited(locale, key, value, success, message);
        }
    }

    private static Map<String, String> patched(Map<String, String> previous, String key, String value) {
        Map<String, String> updated = new HashMap<>(previous);
        if (value == null) updated.remove(key);
        else updated.put(key, value);
        return Map.copyOf(updated);
    }

    @Override
    public void invalidated() {
        // Do not publish an empty dictionary between invalidation and the replacement chunks.
        // FTB parses plain references into literal components, which can outlive that gap.
        STALE.addAll(SNAPSHOTS.keySet());
        REQUESTED.clear();
        REQUEST_TICKS.clear();
        request(language(), true);
        if (Minecraft.getInstance().screen instanceof QuestTranslationScreen screen) screen.invalidated();
    }
}
