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
final class QuestTranslationRichEditor {

    private QuestTranslationRichEditor() {}

    static boolean open(Screen parent, Component title, String text, Consumer<List<String>> accepted) {
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
