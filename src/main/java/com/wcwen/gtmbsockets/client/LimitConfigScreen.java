package com.wcwen.gtmbsockets.client;

import com.wcwen.gtmbsockets.GtmBSockets;
import com.wcwen.gtmbsockets.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Minimal config screen reachable from the Forge mod list's "Config" button.
 *
 * <p>Only one value is exposed, so a click-to-cycle button keeps the screen small and avoids
 * depending on Cloth Config or any other UI library.
 */
public final class LimitConfigScreen extends Screen {

    private static final int[] VALUES = {5, 10, 15, 20, 30, 40, 60, 80, 120, 160, 200};

    private final Screen parent;
    private int value;

    public LimitConfigScreen(Screen parent) {
        super(Component.m_237115_("gui.gtmbsockets.config.title"));
        this.parent = parent;
        this.value = Config.limitRefreshTicks();
    }

    @Override
    protected void m_7856_() {
        int centerX = this.f_96543_ / 2;
        int centerY = this.f_96544_ / 2;

        this.m_142416_(Button.m_253074_(refreshLabel(), button -> {
            this.value = nextValue(this.value);
            button.m_93666_(refreshLabel());
        }).m_252987_(centerX - 100, centerY - 30, 200, 20).m_253136_());

        this.m_142416_(Button.m_253074_(Component.m_237115_("gui.gtmbsockets.config.done"), button -> {
            Config.LIMIT_REFRESH_TICKS.set(this.value);
            Config.SPEC.save();
            this.f_96541_.m_91152_(this.parent);
        }).m_252987_(centerX - 100, centerY + 10, 95, 20).m_253136_());

        this.m_142416_(Button.m_253074_(Component.m_237115_("gui.gtmbsockets.config.cancel"), button ->
                this.f_96541_.m_91152_(this.parent))
                .m_252987_(centerX + 5, centerY + 10, 95, 20).m_253136_());
    }

    @Override
    public void m_88315_(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.m_280273_(graphics);
        graphics.m_280653_(this.f_96547_, this.f_96539_, this.f_96543_ / 2, 40, 0xFFFFFF);
        graphics.m_280653_(this.f_96547_, Component.m_237115_("gui.gtmbsockets.config.hint"),
                this.f_96543_ / 2, 58, 0xA0A0A0);
        super.m_88315_(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void m_7379_() {
        this.f_96541_.m_91152_(this.parent);
    }

    private Component refreshLabel() {
        return Component.m_237110_("gui.gtmbsockets.config.refresh", this.value);
    }

    private static int nextValue(int current) {
        for (int value : VALUES) {
            if (current < value) {
                return value;
            }
        }
        return VALUES[0];
    }
}
