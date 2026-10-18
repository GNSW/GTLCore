package org.gtlcore.gtlcore.mixin.jei;

import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupGridAccess;
import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupOverlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.input.IDraggableIngredientInternal;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.ingredients.IngredientGrid;
import mezz.jei.gui.overlay.ingredients.IngredientListSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.stream.Stream;

@Pseudo
@Mixin(value = IngredientGrid.class, remap = false)
public abstract class TagGroupGridMixin implements TagGroupGridAccess {

    @Unique
    private TagGroupOverlay gtlcore$tagGroups;

    @Override
    public void gtlcore$setTagGroupOverlay(TagGroupOverlay overlay) {
        gtlcore$tagGroups = overlay;
    }

    @Override
    @Invoker("drawTooltip")
    public abstract <T> void gtlcore$drawTagGroupTooltip(GuiGraphics graphics, int mouseX, int mouseY, IElement<T> element);

    @Inject(method = "getIngredientUnderMouse", at = @At("HEAD"), cancellable = true)
    private void gtlcore$expandedClickable(double mouseX, double mouseY, CallbackInfoReturnable<Stream<IClickableIngredientInternal<?>>> cir) {
        if (gtlcore$tagGroups != null && gtlcore$tagGroups.contains(mouseX, mouseY)) {
            cir.setReturnValue(gtlcore$tagGroups.clickable(mouseX, mouseY));
        }
    }

    @Inject(method = "getDraggableIngredientUnderMouse", at = @At("HEAD"), cancellable = true)
    private void gtlcore$expandedDraggable(double mouseX, double mouseY, CallbackInfoReturnable<Stream<IDraggableIngredientInternal<?>>> cir) {
        if (gtlcore$tagGroups != null && gtlcore$tagGroups.contains(mouseX, mouseY)) {
            cir.setReturnValue(gtlcore$tagGroups.draggable(mouseX, mouseY));
        }
    }

    @Inject(method = "getHighlightedSlot", at = @At("HEAD"), cancellable = true)
    private void gtlcore$hideCoveredHighlight(Minecraft minecraft, int mouseX, int mouseY, CallbackInfoReturnable<Optional<IngredientListSlot>> cir) {
        if (gtlcore$tagGroups != null && gtlcore$tagGroups.contains(mouseX, mouseY)) cir.setReturnValue(Optional.empty());
    }
}
