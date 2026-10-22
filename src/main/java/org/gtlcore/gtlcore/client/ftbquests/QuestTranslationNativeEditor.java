package org.gtlcore.gtlcore.client.ftbquests;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationCatalog;
import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationNetwork;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** A server-confirmed translation edit inside FTB's existing modal and quest/config screen. */
public final class QuestTranslationNativeEditor {

    private static final String LIBRARY = "dev.ftb.mods.ftblibrary.";
    private static final Map<Object, Binding> BINDINGS = new WeakHashMap<>();
    private static State active;
    private static boolean reported;

    private QuestTranslationNativeEditor() {}

    public static void bind(Object config, Object owner, String field) {
        BINDINGS.put(config, new Binding(owner, field));
    }

    public static Component display(Object config, Object value) {
        Binding binding = BINDINGS.get(config);
        if (binding == null) return null;
        if (binding.field.isEmpty()) return QuestTranslationScreen.label("advanced_editor");
        try {
            if (binding.field.equals("title")) {
                return (Component) binding.owner.getClass().getMethod("getTitle").invoke(binding.owner);
            }
            if (binding.field.equals("subtitle")) {
                return (Component) binding.owner.getClass().getMethod("getSubtitle").invoke(binding.owner);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
        }
        return null;
    }

    public static boolean openConfigured(Object config, Object widget) {
        Binding binding = BINDINGS.get(config);
        if (binding == null || binding.field.isEmpty()) return false;
        List<String> keys = QuestTranslationAccess.fieldKeys(binding.owner, binding.field);
        if (!open(widget, binding.owner, keys)) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(new QuestTranslationScreen(minecraft.screen, binding.owner, keys, false));
        }
        return true;
    }

    public static boolean tooltip(Object config, Object list) {
        Binding binding = BINDINGS.get(config);
        if (binding == null) return false;
        try {
            Component text = display(config, null);
            if (text != null) list.getClass().getMethod("add", Component.class).invoke(list, text);
            if (!binding.field.isEmpty()) {
                list.getClass().getMethod("add", Component.class).invoke(list, QuestTranslationScreen.label("edit_in_place"));
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
            return false;
        }
    }

    /** Returns false for multiline/rich fields which need the complete translation editor. */
    public static boolean open(Object widgetOrPanel, Object owner, List<String> keys) {
        if (keys.size() != 1 || active != null) return false;
        String locale = QuestTranslationsClient.language();
        var snapshot = QuestTranslationsClient.snapshot(locale);
        String key = keys.get(0);
        if (snapshot == null || !snapshot.enabled() || !snapshot.canEdit() || !snapshot.source().containsKey(key)) return false;
        String expected = snapshot.translations().get(key);
        String draft = expected == null ? snapshot.source().get(key) : expected;
        if (draft.contains("\n") || draft.contains("\r")) return false;
        try {
            Object gui = widgetOrPanel.getClass().getMethod("getGui").invoke(widgetOrPanel);
            Object config = Class.forName(LIBRARY + "config.StringConfig").getConstructor().newInstance();
            config.getClass().getMethod("setValue", Object.class).invoke(config, draft);
            config.getClass().getMethod("setDefaultValue", Object.class).invoke(config, snapshot.source().get(key));
            Class<?> callbackType = Class.forName(LIBRARY + "config.ConfigCallback");
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[] { callbackType },
                    (proxy, method, args) -> null);
            Class<?> overlayType = Class.forName(LIBRARY + "config.ui.EditStringConfigOverlay");
            Object overlay = overlayType.getConstructor(Class.forName(LIBRARY + "ui.Panel"),
                    Class.forName(LIBRARY + "config.ConfigFromString"), callbackType, Component.class)
                    .newInstance(gui, config, callback, label(locale));
            active = new State(Minecraft.getInstance().screen, gui, overlay, config, locale, key, expected);
            int width = Math.max(100, Math.min(360, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 24));
            overlayType.getMethod("setWidth", int.class).invoke(overlay, width);
            overlayType.getMethod("atMousePosition").invoke(overlay);
            gui.getClass().getMethod("pushModalPanel", Class.forName(LIBRARY + "ui.ModalPanel")).invoke(gui, overlay);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            active = null;
            report(e);
            return false;
        }
    }

    /** Cancels FTB's immediate local acceptance; only a matching server response closes this modal. */
    public static boolean accept(Object overlay) {
        State state = active;
        if (state == null || state.overlay != overlay) return false;
        if (state.pending) return true;
        var snapshot = QuestTranslationsClient.snapshot(state.locale);
        if (snapshot == null) {
            status(state, QuestTranslationScreen.label("loading"), false);
            QuestTranslationsClient.request(state.locale, false);
            return true;
        }
        if (!snapshot.enabled() || !snapshot.canEdit() || !snapshot.source().containsKey(state.key)) {
            status(state, QuestTranslationScreen.label(snapshot.enabled() ? "permission" : "disabled"), true);
            return true;
        }
        try {
            String value = String.valueOf(field(overlay, "currentValue"));
            QuestTranslationCatalog.validateValue(value, QuestTranslationsClient.prefix());
            state.pending = true;
            state.pendingValue = value;
            state.pendingTicks = 0;
            state.config.getClass().getMethod("setCanEdit", boolean.class).invoke(state.config, false);
            status(state, QuestTranslationScreen.label("saving"), false);
            QuestTranslationNetwork.edit(state.locale, state.key, state.expected, value);
        } catch (IOException e) {
            status(state, Component.literal(e.getMessage()), true);
        } catch (ReflectiveOperationException | LinkageError e) {
            state.pending = false;
            report(e);
            status(state, QuestTranslationScreen.label("save_failed"), true);
        }
        return true;
    }

    public static void cancelled(Object overlay) {
        if (active != null && active.overlay == overlay) active = null;
    }

    public static boolean active() {
        return active != null;
    }

    public static void clear() {
        active = null;
        BINDINGS.clear();
    }

    public static void tick() {
        State state = active;
        if (state == null) return;
        if (Minecraft.getInstance().screen != state.screen || Minecraft.getInstance().getConnection() == null) {
            active = null;
        } else if (state.pending && ++state.pendingTicks >= 200) {
            state.pending = false;
            editable(state);
            status(state, QuestTranslationScreen.label("timeout"), true);
        }
    }

    public static void edited(String locale, String key, String value, boolean success, String message) {
        State state = active;
        if (state == null || !state.locale.equals(locale) || !state.key.equals(key)) return;
        if (success && state.pending && Objects.equals(value, state.pendingValue)) {
            try {
                state.gui.getClass().getMethod("popModalPanel").invoke(state.gui);
                active = null;
            } catch (ReflectiveOperationException | LinkageError e) {
                report(e);
                state.pending = false;
                editable(state);
                status(state, QuestTranslationScreen.label("saved"), false);
            }
        } else {
            if (!success) {
                state.pending = false;
                editable(state);
            }
            // Keep the original compare-and-set value: a second click must not silently
            // overwrite another editor's work. Reopen/load explicitly to adopt that value.
            status(state, success ? QuestTranslationScreen.label("conflict") : Component.literal(message), true);
        }
    }

    public static void align(Object overlay) {
        State state = active;
        if (state == null || state.overlay != overlay) return;
        try {
            int width = ((Number) overlay.getClass().getMethod("getWidth").invoke(overlay)).intValue();
            Object title = field(overlay, "titleField");
            title.getClass().getMethod("setMaxWidth", int.class).invoke(title, width - 8);
            Component heading = label(state.locale);
            if (state.status != null) heading = heading.copy().append("\n").append(state.status);
            title.getClass().getMethod("setText", Component.class).invoke(title, heading);
            int titleHeight = ((Number) title.getClass().getMethod("getHeight").invoke(title)).intValue() + 4;
            Object textBox = field(overlay, "textBox");
            textBox.getClass().getMethod("setPosAndSize", int.class, int.class, int.class, int.class)
                    .invoke(textBox, 2, titleHeight + 1, width - 36, 14);
            field(overlay, "accept").getClass().getMethod("setPos", int.class, int.class)
                    .invoke(field(overlay, "accept"), width - 34, titleHeight + 1);
            field(overlay, "cancel").getClass().getMethod("setPos", int.class, int.class)
                    .invoke(field(overlay, "cancel"), width - 17, titleHeight + 1);
            overlay.getClass().getMethod("setHeight", int.class).invoke(overlay, titleHeight + 17);
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
        }
    }

    private static Component label(String locale) {
        return Component.translatable("gtlcore.quest_i18n.edit_current_language", locale);
    }

    private static void status(State state, Component text, boolean error) {
        state.status = text.copy().withStyle(error ? ChatFormatting.RED : ChatFormatting.YELLOW);
        try {
            state.overlay.getClass().getMethod("alignWidgets").invoke(state.overlay);
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
        }
    }

    private static void editable(State state) {
        try {
            state.config.getClass().getMethod("setCanEdit", boolean.class).invoke(state.config, true);
        } catch (ReflectiveOperationException | LinkageError e) {
            report(e);
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

    private static void report(Throwable exception) {
        if (!reported) {
            reported = true;
            GTLCore.LOGGER.warn("FTB Quests native translation editor is incompatible", exception);
        }
    }

    private record Binding(Object owner, String field) {}

    private static final class State {

        private final Screen screen;
        private final Object gui;
        private final Object overlay;
        private final Object config;
        private final String locale;
        private final String key;
        private final String expected;
        private String pendingValue;
        private boolean pending;
        private int pendingTicks;
        private Component status;

        private State(Screen screen, Object gui, Object overlay, Object config, String locale, String key, String expected) {
            this.screen = screen;
            this.gui = gui;
            this.overlay = overlay;
            this.config = config;
            this.locale = locale;
            this.key = key;
            this.expected = expected;
        }
    }
}
