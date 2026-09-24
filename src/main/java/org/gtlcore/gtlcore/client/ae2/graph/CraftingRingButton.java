package org.gtlcore.gtlcore.client.ae2.graph;

import net.minecraft.network.chat.Component;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

/** Same size and background as AE's toolbar, with a distinct circular-arrow icon. */
public final class CraftingRingButton extends IconButton {

    public CraftingRingButton(Runnable action) {
        super(button -> action.run());
        setMessage(Component.translatable("gtlcore.ae.ring.title"));
    }

    @Override
    protected Icon getIcon() {
        return Icon.SCHEDULING_ROUND_ROBIN;
    }
}
