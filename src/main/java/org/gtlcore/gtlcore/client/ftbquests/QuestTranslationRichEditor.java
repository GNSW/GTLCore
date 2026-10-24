package org.gtlcore.gtlcore.client.ftbquests;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Uses FTB's full editor, including formatting, image and quest-link tools. */
public final class QuestTranslationRichEditor {

    private QuestTranslationRichEditor() {}

    public static void link(Object editor, long destination) {
        edit(editor, null, destination);
    }

    public static boolean format(Object editor, String format) {
        return edit(editor, format, 0);
    }

    private static boolean edit(Object editor, String format, long destination) {
        try {
            var field = editor.getClass().getDeclaredField("textBox");
            field.setAccessible(true);
            Object box = field.get(editor);
            Class<?> type = box.getClass();
            String text = (String) type.getMethod("getText").invoke(box);
            Object selection = type.getMethod("getSelected").invoke(box);
            Class<?> extents = selection.getClass();
            int from = (int) extents.getMethod("start").invoke(selection);
            int to = (int) extents.getMethod("end").invoke(selection);
            int start = text.lastIndexOf('\n', Math.max(-1, from - 1)) + 1;
            int end = text.indexOf('\n', from);
            if (end < 0) end = text.length();
            String line = text.substring(start, end);
            // Plain paragraphs keep native FTB syntax and their existing language keys.
            if (format != null && !QuestTranslationRichText.isJson(line)) {
                boolean includesJson = to > end && Arrays.stream(text.substring(start, to).split("\n", -1))
                        .anyMatch(QuestTranslationRichText::isJson);
                if (!includesJson) return false;
                error(Component.translatable("gtlcore.quest_i18n.rich_selection"));
                return true;
            }
            if (from == to || to > end) {
                error(Component.translatable("gtlcore.quest_i18n.rich_selection"));
                return true;
            }
            String replacement = format == null ? QuestTranslationRichText.link(line, from - start, to - start, destination) :
                    QuestTranslationRichText.format(line, from - start, to - start, format);
            // Native replacement records an undo step and restores the editor's focus/cursor.
            var replace = editor.getClass().getDeclaredMethod("replaceText", extents, String.class);
            replace.setAccessible(true);
            replace.invoke(editor, extents.getConstructor(int.class, int.class).newInstance(start, end), replacement);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (e instanceof ReflectiveOperationException) GTLCore.LOGGER.error("Could not edit quest rich text", e);
            error(Component.translatable("gtlcore.quest_i18n.rich_edit_failed",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        return true;
    }

    private static void error(Component message) {
        try {
            Class.forName("dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen")
                    .getMethod("displayError", Component.class).invoke(null, message);
        } catch (ReflectiveOperationException e) {
            GTLCore.LOGGER.warn("{}", message.getString());
        }
    }

    static boolean open(Screen parent, Component title, String text, Consumer<List<String>> accepted) {
        try {
            text = String.join("\n", QuestTranslationRichText.normalize(Arrays.asList(text.split("\n", -1))));
        } catch (RuntimeException ignored) {
            // Keep malformed user input editable; accepting it will show the validation error.
        }
        try {
            Class<?> valueType = Class.forName("dev.ftb.mods.ftblibrary.config.ConfigValue");
            Class<?> stringType = Class.forName("dev.ftb.mods.ftblibrary.config.StringConfig");
            Class<?> listType = Class.forName("dev.ftb.mods.ftblibrary.config.ListConfig");
            Object list = listType.getConstructor(valueType).newInstance(stringType.getConstructor().newInstance());
            listType.getMethod("setValue", Object.class).invoke(list, new ArrayList<>(List.of(text)));
            Class<?> callbackType = Class.forName("dev.ftb.mods.ftblibrary.config.ConfigCallback");
            Class<?> editorType = Class.forName("dev.ftb.mods.ftbquests.client.gui.MultilineTextEditorScreen");
            Object[] holder = new Object[1];
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[] { callbackType },
                    (proxy, method, args) -> {
                        if (method.getName().equals("save")) {
                            Minecraft.getInstance().setScreen(parent);
                            if (Boolean.TRUE.equals(args[0])) {
                                // Native ListConfig drops trailing blank lines on acceptance.
                                // Read the editor buffer so intentionally empty paragraphs survive.
                                var field = editorType.getDeclaredField("textBox");
                                field.setAccessible(true);
                                Object box = field.get(holder[0]);
                                String value = (String) box.getClass().getMethod("getText").invoke(box);
                                accepted.accept(Arrays.asList(value.split("\n", -1)));
                            }
                        }
                        return null;
                    });
            Object editor = editorType.getConstructor(Component.class, listType, callbackType).newInstance(title, list, callback);
            holder[0] = editor;
            editorType.getMethod("openGui").invoke(editor);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            GTLCore.LOGGER.error("Could not open the FTB quest text editor", e);
            return false;
        }
    }
}
