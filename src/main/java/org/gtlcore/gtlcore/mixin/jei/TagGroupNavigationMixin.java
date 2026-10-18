package org.gtlcore.gtlcore.mixin.jei;

import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupGridAccess;
import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupOverlay;
import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupSource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.gui.ghost.GhostIngredientDragManager;
import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.overlay.ingredients.IIngredientGridSource;
import mezz.jei.gui.overlay.ingredients.IngredientGrid;
import mezz.jei.gui.overlay.ingredients.IngredientGridWithNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = IngredientGridWithNavigation.class, remap = false)
public abstract class TagGroupNavigationMixin {

    @Shadow
    @Final
    private IIngredientGridSource ingredientSource;
    @Shadow
    @Final
    private IngredientGrid ingredientGrid;
    @Shadow
    @Final
    private IIngredientManager ingredientManager;
    @Shadow
    @Final
    private GhostIngredientDragManager ghostIngredientDragManager;

    @Shadow
    public abstract void updateLayoutToFirstPage();

    @Unique
    private TagGroupOverlay gtlcore$tagGroups;
    @Unique
    private int gtlcore$sourceRevision = -1;

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static IIngredientGridSource gtlcore$wrapIngredientSource(IIngredientGridSource source) {
        return source instanceof IngredientFilter ? new TagGroupSource(source) : source;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void gtlcore$createTagGroups(CallbackInfo ci) {
        if (ingredientSource instanceof TagGroupSource source) {
            gtlcore$tagGroups = new TagGroupOverlay(source, ingredientGrid, ingredientManager);
            ((TagGroupGridAccess) ingredientGrid).gtlcore$setTagGroupOverlay(gtlcore$tagGroups);
        }
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private void gtlcore$updateGroups(Minecraft minecraft, GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (gtlcore$tagGroups == null) return;
        int revision = ((TagGroupSource) ingredientSource).revision();
        if (revision != gtlcore$sourceRevision) {
            gtlcore$sourceRevision = revision;
            gtlcore$tagGroups.close();
            updateLayoutToFirstPage();
        }
        gtlcore$tagGroups.update(mouseX, mouseY);
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void gtlcore$drawGroupBadges(Minecraft minecraft, GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (gtlcore$tagGroups != null) gtlcore$tagGroups.drawBadges(graphics);
    }

    @Inject(method = "drawTooltips", at = @At("HEAD"), cancellable = true)
    private void gtlcore$drawExpandedGroup(Minecraft minecraft, GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (gtlcore$tagGroups == null) return;
        if (gtlcore$tagGroups.suppressesTooltip(mouseX, mouseY)) {
            ghostIngredientDragManager.drawTooltips(minecraft, graphics, mouseX, mouseY);
            gtlcore$tagGroups.drawPopup(graphics, mouseX, mouseY);
            gtlcore$tagGroups.drawTooltip(graphics, mouseX, mouseY);
            ci.cancel();
        } else {
            gtlcore$tagGroups.drawPopup(graphics, mouseX, mouseY);
        }
        gtlcore$tagGroups.drawCarriedStack(graphics, mouseX, mouseY);
    }

    @Inject(method = "isMouseOver", at = @At("HEAD"), cancellable = true)
    private void gtlcore$includeExpandedArea(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (gtlcore$tagGroups != null && gtlcore$tagGroups.contains(mouseX, mouseY)) cir.setReturnValue(true);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void gtlcore$closeExpandedGroup(CallbackInfo ci) {
        if (gtlcore$tagGroups != null) gtlcore$tagGroups.close();
    }
}
