package org.gtlcore.gtlcore.mixin.gtm.gui;

import org.gtlcore.gtlcore.client.gui.PageHighlightOverlay;

import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.PageSwitcher;

import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import com.llamalad7.mixinextras.sugar.Local;
import org.apache.commons.lang3.mutable.MutableInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.function.Consumer;

@Mixin(PageSwitcher.class)
public abstract class PageSwitcherMixin {

    @Inject(method = "lambda$createMainPage$2", at = @At("RETURN"), remap = false)
    private void gtlcore$attachRightClickHighlights(MutableInt x, MutableInt y,
                                                    DraggableScrollableWidgetGroup content,
                                                    IFancyUIProvider page, CallbackInfo ci,
                                                    @Local WidgetGroup cluster) {
        if (cluster == null || page == null) {
            return;
        }
        for (ButtonWidget button : cluster.getWidgetsByType(ButtonWidget.class)) {
            button.appendHoverTooltips(
                    net.minecraft.network.chat.Component.translatable("gui.gtlcore.page_highlight.right_click").getString());
            gtlcore$wrapButton(button, page);
        }
    }

    private static final java.util.Set<ButtonWidget> WRAPPED = java.util.Collections
            .newSetFromMap(new java.util.IdentityHashMap<>());

    @SuppressWarnings("unchecked")
    private static void gtlcore$wrapButton(ButtonWidget button, IFancyUIProvider page) {
        if (!WRAPPED.add(button)) {
            return;
        }
        try {
            Field field = ButtonWidget.class.getDeclaredField("onPressCallback");
            field.setAccessible(true);
            Consumer<ClickData> original = (Consumer<ClickData>) field.get(button);
            field.set(button, (Consumer<ClickData>) data -> {
                if (data != null && data.button == 1) {
                    PageHighlightOverlay.highlight(page);
                } else if (original != null) {
                    original.accept(data);
                }
            });
        } catch (ReflectiveOperationException ignored) {
            // Leave the original page switching behavior untouched.
        }
    }
}
