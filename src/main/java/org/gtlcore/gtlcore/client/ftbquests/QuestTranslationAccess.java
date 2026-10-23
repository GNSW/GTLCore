package org.gtlcore.gtlcore.client.ftbquests;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Optional FTB integration: loading Core does not require FTB Quests or its GUI classes. */
public final class QuestTranslationAccess {

    private static final String CLIENT_FILE = "dev.ftb.mods.ftbquests.client.ClientQuestFile";
    private static final Map<Object, Object> CONFIG_OWNERS = new WeakHashMap<>();
    private static boolean reported;

    private QuestTranslationAccess() {}

    /** Text visible in this object, including a quest's inherited title and its targets/rewards. */
    public static List<String> keys(Object object) {
        return List.copyOf(entries(object).keySet());
    }

    /** A field's actual language keys; an automatic quest title belongs to its first target. */
    public static List<String> fieldKeys(Object object, String field) {
        if (object == null) return List.of();
        try {
            object = current(object);
            CompoundTag tag = data(object);
            if (field.equals("title") && tag.getString("title").isEmpty()) {
                List<?> tasks = children(object, "getTasks");
                if (!tasks.isEmpty()) tag = data(tasks.get(0));
            }
            Set<String> keys = new LinkedHashSet<>();
            collect(tag.get(field), keys);
            return List.copyOf(keys);
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
            return List.of();
        }
    }

    /** Human-facing field labels preserve ownership when several targets have their own titles. */
    public static Component keyLabel(Object object, String key) {
        return entries(object).getOrDefault(key, Component.literal(key));
    }

    /** An index of actual references, independent of the spelling of generated or custom keys. */
    static Map<String, List<Location>> locations() {
        Map<String, List<Location>> result = new LinkedHashMap<>();
        try {
            Class<?> type = Class.forName(CLIENT_FILE);
            Object file = type.getField("INSTANCE").get(null);
            if (file == null) return result;
            List<Object> owners = new ArrayList<>((Collection<?>) type.getMethod("getAllObjects").invoke(file));
            if (!owners.contains(file)) owners.add(file);
            for (Object owner : owners) {
                CompoundTag tag = data(owner);
                for (String field : tag.getAllKeys().stream().sorted().toList()) {
                    if (List.of("tasks", "rewards", "quests", "chapters", "chapter_groups").contains(field)) continue;
                    if (field.equals("description") && tag.get(field) instanceof ListTag paragraphs) {
                        for (int i = 0; i < paragraphs.size(); i++) {
                            Map<String, Component> fields = new LinkedHashMap<>();
                            appendKeys(paragraphs.get(i), fields, label("entry_description", i + 1));
                            appendLocations(result, owner, fields);
                        }
                        continue;
                    }
                    Map<String, Component> fields = new LinkedHashMap<>();
                    appendField(field, tag.get(field), fields, "", 0);
                    appendLocations(result, owner, fields);
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
        }
        return result;
    }

    private static void appendLocations(Map<String, List<Location>> result, Object owner, Map<String, Component> fields) throws ReflectiveOperationException {
        // Keep each field/paragraph separately, even when the same key is reused within one object.
        if (fields.isEmpty()) return;
        String path = locationPath(owner);
        Object objectType = owner.getClass().getMethod("getObjectType").invoke(owner);
        String type = (String) objectType.getClass().getMethod("getId").invoke(objectType);
        for (var entry : fields.entrySet()) {
            Component field = Component.translatable("ftbquests." + type).append(" · ").append(entry.getValue());
            result.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(new Location(owner, field, path));
        }
    }

    record Location(Object owner, Component field, String path) {}

    private static String locationPath(Object owner) throws ReflectiveOperationException {
        List<String> path = new ArrayList<>();
        Object chapter = owner.getClass().getMethod("getQuestChapter").invoke(owner);
        Object quest = containingQuest(owner);
        if (chapter != null && chapter != owner) path.add(objectTitle(chapter));
        if (quest != null && quest != owner) path.add(objectTitle(quest));
        path.add(objectTitle(owner));
        return String.join(" / ", path);
    }

    private static String objectTitle(Object object) throws ReflectiveOperationException {
        String title = ((Component) object.getClass().getMethod("getTitle").invoke(object)).getString();
        long id = ((Number) object.getClass().getMethod("getId").invoke(object)).longValue();
        return title.isBlank() ? Long.toUnsignedString(id, 16) : title;
    }

    private static Object containingQuest(Object owner) throws ReflectiveOperationException {
        Class<?> quest = Class.forName("dev.ftb.mods.ftbquests.quest.Quest");
        if (quest.isInstance(owner)) return owner;
        try {
            return owner.getClass().getMethod("getQuest").invoke(owner);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    static boolean locate(Location location, Screen back) {
        try {
            Object owner = current(location.owner());
            Object quest = containingQuest(owner);
            Object screen = Class.forName(CLIENT_FILE).getMethod("openGui").invoke(null);
            if (screen == null) return false;
            Object chapter = owner.getClass().getMethod("getQuestChapter").invoke(owner);
            if (chapter != null) screen.getClass().getMethod("selectChapter", Class.forName("dev.ftb.mods.ftbquests.quest.Chapter")).invoke(screen, chapter);
            if (quest != null) screen.getClass().getMethod("open", Class.forName("dev.ftb.mods.ftbquests.quest.QuestObject"), boolean.class).invoke(screen, quest, false);
            QuestTranslationNavigation.returnFrom(Minecraft.getInstance().screen, back);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
            Minecraft.getInstance().setScreen(back);
            return false;
        }
    }

    static boolean editLocation(Location location, Screen back) {
        try {
            Object owner = current(location.owner());
            owner.getClass().getMethod("onEditButtonClicked", Runnable.class)
                    .invoke(owner, (Runnable) () -> Minecraft.getInstance().setScreen(back));
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
            return false;
        }
    }

    private static Map<String, Component> entries(Object object) {
        if (object == null) return Map.of();
        try {
            object = current(object);
            CompoundTag tag = data(object);
            List<?> tasks = children(object, "getTasks");
            Map<String, Component> entries = new LinkedHashMap<>();
            if (tag.getString("title").isEmpty() && !tasks.isEmpty()) {
                appendKeys(data(tasks.get(0)).get("title"), entries, label("entry_inherited_title"));
            }
            appendFields(tag, entries, "", 0);
            for (int i = 0; i < tasks.size(); i++) {
                appendFields(data(tasks.get(i)), entries, "entry_task", i + 1);
            }
            List<?> rewards = children(object, "getRewards");
            for (int i = 0; i < rewards.size(); i++) {
                appendFields(data(rewards.get(i)), entries, "entry_reward", i + 1);
            }
            return entries;
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
            return Map.of();
        }
    }

    private static CompoundTag data(Object object) throws ReflectiveOperationException {
        CompoundTag tag = new CompoundTag();
        object.getClass().getMethod("writeData", CompoundTag.class).invoke(object, tag);
        return tag;
    }

    private static List<?> children(Object object, String method) throws ReflectiveOperationException {
        try {
            Object children = object.getClass().getMethod(method).invoke(object);
            return children instanceof Collection<?> collection ? List.copyOf(collection) : List.of();
        } catch (NoSuchMethodException ignored) {
            return List.of();
        }
    }

    private static void appendFields(CompoundTag tag, Map<String, Component> entries, String scope, int ordinal) {
        List<String> preferred = List.of("title", "subtitle", "description", "lock_message");
        for (String field : preferred) appendField(field, tag.get(field), entries, scope, ordinal);
        for (String field : tag.getAllKeys()) {
            if (!preferred.contains(field) && !List.of("tasks", "rewards").contains(field)) {
                appendField(field, tag.get(field), entries, scope, ordinal);
            }
        }
    }

    private static void appendField(String field, Tag value, Map<String, Component> entries, String scope, int ordinal) {
        if (field.equals("description") && value instanceof ListTag paragraphs) {
            for (int i = 0; i < paragraphs.size(); i++) {
                appendKeys(paragraphs.get(i), entries, scoped(label("entry_description", i + 1), scope, ordinal));
            }
        } else {
            Component name = switch (field) {
                case "title" -> label("entry_title");
                case "subtitle" -> label("entry_subtitle");
                case "lock_message" -> label("entry_lock_message");
                default -> Component.literal(field);
            };
            appendKeys(value, entries, scoped(name, scope, ordinal));
        }
    }

    private static Component scoped(Component name, String scope, int ordinal) {
        return scope.isEmpty() ? name : label(scope, ordinal, name);
    }

    private static void appendKeys(Tag value, Map<String, Component> entries, Component name) {
        Set<String> keys = new LinkedHashSet<>();
        collect(value, keys);
        int ordinal = 1;
        for (String key : keys) {
            entries.putIfAbsent(key, keys.size() > 1 ? label("entry_part", name, ordinal++) : name);
        }
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("gtlcore.quest_i18n." + key, args);
    }

    private static void collect(Tag tag, Set<String> keys) {
        if (tag instanceof CompoundTag compound) {
            // Give the editor an unsurprising order before inspecting any nested custom fields.
            for (String field : List.of("title", "subtitle", "description")) {
                if (compound.contains(field)) collect(compound.get(field), keys);
            }
            for (String field : compound.getAllKeys()) {
                if (!List.of("title", "subtitle", "description").contains(field)) collect(compound.get(field), keys);
            }
        } else if (tag instanceof ListTag list) {
            list.forEach(value -> collect(value, keys));
        } else if (tag instanceof StringTag text) {
            var matcher = Pattern.compile(Pattern.quote(QuestTranslationsClient.prefix()) + "[a-z0-9_.-]+").matcher(text.getAsString());
            while (matcher.find()) keys.add(matcher.group());
        }
    }

    public static void configure(Object object, Object config) {
        if (!QuestTranslationsClient.available()) return;
        try {
            Object entry = config.getClass().getMethod("addString", String.class, String.class, Consumer.class, String.class)
                    .invoke(config, "gtlcore_translations", "", (Consumer<String>) ignored -> {}, "");
            entry.getClass().getMethod("setNameKey", String.class).invoke(entry, "gtlcore.quest_i18n.editor");
            entry.getClass().getMethod("setOrder", int.class).invoke(entry, -128);
            CONFIG_OWNERS.put(entry, object);
            QuestTranslationNativeEditor.bind(entry, object, "");
            // Keep references as data, but show and edit their translated text in the native form.
            // Ordinary literal fields still use FTB's original authoring flow.
            for (Object value : (Collection<?>) config.getClass().getMethod("getValues").invoke(config)) {
                String id = (String) value.getClass().getField("id").get(value);
                if (List.of("title", "subtitle", "description").contains(id)) {
                    if (!fieldKeys(object, id).isEmpty()) {
                        QuestTranslationNativeEditor.bind(value, object, id);
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
        }
    }

    public static boolean openConfig(Object config) {
        Object object = CONFIG_OWNERS.get(config);
        if (object == null) return false;
        open(object, keys(object), true);
        return true;
    }

    public static boolean openConfig(Object config, Object widget, Object callback) {
        if (QuestTranslationNativeEditor.openConfigured(config, widget)) return true;
        return openConfig(config);
    }

    private static void open(Object object, List<String> keys, boolean allKeys) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new QuestTranslationScreen(minecraft.screen, object, keys, allKeys));
    }

    public static boolean editField(Object panel, String field) {
        try {
            Object object = field(panel, "quest");
            if (field.equals("title")) {
                Object tasks = field(panel, "panelTasks");
                for (Object widget : (Collection<?>) tasks.getClass().getMethod("getWidgets").invoke(tasks)) {
                    if (widget.getClass().getName().endsWith(".TaskButton") &&
                            Boolean.TRUE.equals(widget.getClass().getMethod("isMouseOver").invoke(widget))) {
                        object = field(widget, "task");
                        break;
                    }
                }
            }
            if (object == null) return false;
            List<String> keys = fieldKeys(object, field);
            if (keys.isEmpty()) return false;
            if (QuestTranslationNativeEditor.open(panel, object, keys)) return true;
            open(object, List.copyOf(keys), false);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
            return false;
        }
    }

    public static boolean editDescriptionLine(Object panel, int line) {
        if (line < 0) return false; // New paragraphs/images keep FTB's native structural editor.
        try {
            Object object = field(panel, "quest");
            if (object == null) return false;
            List<?> description = (List<?>) object.getClass().getMethod("getRawDescription").invoke(object);
            if (line >= description.size()) return false;
            Set<String> keys = new LinkedHashSet<>();
            collect(StringTag.valueOf(String.valueOf(description.get(line))), keys);
            if (keys.isEmpty()) return false;
            if (QuestTranslationNativeEditor.open(panel, object, List.copyOf(keys))) return true;
            open(object, List.copyOf(keys), false);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
            return false;
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    public static String preview(String raw) {
        return previewComponent(raw).getString();
    }

    public static Component previewComponent(String raw) {
        try {
            Class<?> text = Class.forName("dev.ftb.mods.ftbquests.util.TextUtils");
            return (Component) text.getMethod("parseRawText", String.class).invoke(null, raw);
        } catch (ReflectiveOperationException | LinkageError e) {
            return Component.literal(raw);
        }
    }

    public static long id(Object object) {
        try {
            return ((Number) object.getClass().getMethod("getId").invoke(object)).longValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Not an FTB quest object", e);
        }
    }

    private static Object current(Object object) throws ReflectiveOperationException {
        Class<?> type = Class.forName(CLIENT_FILE, false, QuestTranslationAccess.class.getClassLoader());
        Object file = type.getField("INSTANCE").get(null);
        if (file == null) return object;
        long id = ((Number) object.getClass().getMethod("getId").invoke(object)).longValue();
        Object current = type.getMethod("getBase", long.class).invoke(file, id);
        return current == null ? object : current;
    }

    public static List<String> description(Object object) {
        try {
            object = current(object);
            return new ArrayList<>((List<String>) object.getClass().getMethod("getRawDescription").invoke(object));
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
            return null;
        }
    }

    public static boolean saveDescription(Object object, List<String> expected, List<String> replacement) {
        List<String> raw = null;
        try {
            object = current(object);
            raw = (List<String>) object.getClass().getMethod("getRawDescription").invoke(object);
            if (!raw.equals(expected)) return false;
            raw.clear();
            raw.addAll(replacement);
            Class<?> base = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase");
            Class<?> packet = Class.forName("dev.ftb.mods.ftbquests.net.EditObjectMessage");
            Object edit = packet.getConstructor(base).newInstance(object);
            packet.getMethod("sendToServer").invoke(edit);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            if (raw != null) {
                raw.clear();
                raw.addAll(expected);
                refresh();
            }
            report(e);
            return false;
        }
    }

    public static void refresh() {
        try {
            Class<?> type = Class.forName(CLIENT_FILE, false, QuestTranslationAccess.class.getClassLoader());
            Object file = type.getField("INSTANCE").get(null);
            if (file != null) {
                type.getMethod("refreshGui").invoke(file);
                // FTB only rebuilds the current quest screen in refreshGui(). A translation
                // screen can cover its old widgets; also refresh those and their modal panels.
                Object screen = type.getMethod("getQuestScreen").invoke(file);
                if (screen instanceof java.util.Optional<?> optional && optional.isPresent()) {
                    Object questScreen = optional.get();
                    questScreen.getClass().getMethod("refreshWidgets").invoke(questScreen);
                }
            }
        } catch (ClassNotFoundException ignored) {
            // FTB Quests is optional.
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
        }
    }

    private static void report(Throwable exception) {
        if (!reported) {
            reported = true;
            GTLCore.LOGGER.warn("FTB Quests translation editor integration is incompatible", exception);
        }
    }
}
