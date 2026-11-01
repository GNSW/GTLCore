package org.gtlcore.gtlcore.common.cover;

import com.gregtechceu.gtceu.api.gui.GuiTextures;

import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class GroupedSupplyWidget extends SwitchWidget {

    public GroupedSupplyWidget(WidgetGroup group, BooleanSupplier enabled, BooleanSupplier available, Consumer<Boolean> setter) {
        super(10, group.getSize().height, group.getSize().width - 20, 20, (click, pressed) -> {
            if (!pressed || available.getAsBoolean()) setter.accept(pressed);
        });
        setBaseTexture(GuiTextures.VANILLA_BUTTON, new TextTexture("gtlcore.cover.supply_all.off"));
        setPressedTexture(GuiTextures.VANILLA_BUTTON, new TextTexture("gtlcore.cover.supply_all.on"));
        setSupplier(enabled::getAsBoolean);
        setPressed(enabled.getAsBoolean());
        setHoverTooltips("gtlcore.cover.supply_all.tooltip", "gtlcore.cover.supply_all.requirements", "gtlcore.cover.supply_all.handlers");
        // Inactive widgets are not ticked by LDLib, so they cannot re-enable themselves.
        // Keep ticking and validate eligibility in the click callback instead.
        group.setSize(group.getSize().width, group.getSize().height + getSize().height + 2);
        group.addWidget(this);
    }
}
