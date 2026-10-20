package org.gtlcore.gtlcore.integration.ftbquests;

import net.minecraft.network.chat.Component;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

public final class QuestSearchText {

    private static final String BUTTON_CLASS = "dev.ftb.mods.ftbquests.client.gui.SelectQuestObjectScreen$QuestObjectButton";
    private static final String QUEST_CLASS = "dev.ftb.mods.ftbquests.quest.Quest";
    private static final String LINK_CLASS = "dev.ftb.mods.ftbquests.quest.QuestLink";

    private QuestSearchText() {}

    public static String forButton(Object button) {
        if (!button.getClass().getName().equals(BUTTON_CLASS)) {
            return "";
        }

        try {
            var field = button.getClass().getDeclaredField("object");
            field.setAccessible(true);
            Object object = field.get(button);
            if (object == null) {
                return "";
            }

            ClassLoader loader = button.getClass().getClassLoader();
            Class<?> questClass = Class.forName(QUEST_CLASS, false, loader);
            if (Class.forName(LINK_CLASS, false, loader).isInstance(object)) {
                object = ((Optional<?>) invoke(object, "getQuest")).orElse(null);
            }
            return questClass.isInstance(object) ? questText(object) : "";
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("FTB Quests search integration is incompatible", exception);
        }
    }

    private static String questText(Object quest) throws ReflectiveOperationException {
        StringBuilder text = new StringBuilder();
        append(text, (Component) invoke(quest, "getSubtitle"));
        for (Component line : (Collection<Component>) invoke(quest, "getDescription")) {
            append(text, line);
        }
        appendChildren(text, (Collection<?>) invoke(quest, "getTasks"));
        appendChildren(text, (Collection<?>) invoke(quest, "getRewards"));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void appendChildren(StringBuilder text, Collection<?> children) throws ReflectiveOperationException {
        for (Object child : children) {
            append(text, (Component) invoke(child, "getTitle"));
            append(text, (Component) invoke(child, "getAltTitle"));
        }
    }

    private static void append(StringBuilder text, Component component) {
        text.append(' ').append(component.getString());
    }

    private static Object invoke(Object object, String method) throws ReflectiveOperationException {
        try {
            return object.getClass().getMethod(method).invoke(object);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("FTB Quests failed to provide search text", exception.getCause());
        }
    }
}
