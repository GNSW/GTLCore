package org.gtlcore.gtlcore.api.machine.trait;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;

import net.minecraft.network.chat.Component;

import java.util.List;

public interface ICheckPatternMachine {

    ResourceTexture STRUCTURE_CHECK = new ResourceTexture(GTCEu.id("textures/gui/structure_check.png"));

    default void setTime(int time) {}

    default int getTime() {
        return 0;
    }

    default boolean hasButton() {
        return false;
    }

    static void attachConfigurators(ConfiguratorPanel configuratorPanel, MetaMachine machine) {
        if (machine instanceof ICheckPatternMachine checkPatternMachine) {
            configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                    STRUCTURE_CHECK.getSubTexture(0, 0, 1, 0.5),
                    STRUCTURE_CHECK.getSubTexture(0, 0.5, 1, 0.5),
                    () -> checkPatternMachine.getTime() < 1, (clickData, pressed) -> {
                        if (checkPatternMachine.getTime() > 0) checkPatternMachine.setTime(0);
                    }).setTooltipsSupplier(pressed -> List.of(Component.translatable("gtceu.machine.structure_check"))));
        }
    }
}
