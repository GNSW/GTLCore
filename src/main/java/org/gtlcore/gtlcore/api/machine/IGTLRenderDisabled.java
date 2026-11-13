package org.gtlcore.gtlcore.api.machine;

import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;

import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import java.util.List;

public interface IGTLRenderDisabled {

    boolean gtlcore$isRenderDisabled();

    void gtlcore$toggleRenderDisabled();

    static boolean isRenderDisabled(Object machine) {
        return machine instanceof IGTLRenderDisabled disabled && disabled.gtlcore$isRenderDisabled();
    }

    static void attachConfigurator(ConfiguratorPanel panel, IGTLRenderDisabled machine) {
        panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                new ItemStackTexture(Items.ENDER_EYE),
                new ItemStackTexture(Items.ENDER_PEARL),
                machine::gtlcore$isRenderDisabled,
                (clickData, pressed) -> machine.gtlcore$toggleRenderDisabled())
                .setTooltipsSupplier(pressed -> List.of(Component.translatable(
                        pressed ? "gtlcore.machine.render_disabled" : "gtlcore.machine.render_enabled"))));
    }
}
