package org.gtlcore.gtlcore.integration.jei.grouping;

import net.minecraft.client.gui.GuiGraphics;

import mezz.jei.gui.overlay.elements.IElement;

public interface TagGroupGridAccess {

    void gtlcore$setTagGroupOverlay(TagGroupOverlay overlay);

    <T> void gtlcore$drawTagGroupTooltip(GuiGraphics graphics, int mouseX, int mouseY, IElement<T> element);
}
