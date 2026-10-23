package org.gtlcore.gtlcore.mixin.gtm;

import org.gtlcore.gtlcore.integration.jei.PreviewJeiTransfer;
import org.gtlcore.gtlcore.integration.jei.SlotRecipeWidget;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoCategory;
import com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoWrapper;

import com.lowdragmc.lowdraglib.gui.ingredient.IRecipeIngredientSlot;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TankWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.jei.ModularUIRecipeCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.BlockableEventLoop;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoCategory.RECIPE_TYPE;

/**
 * @author EasterFG on 2024/10/28
 */
@Mixin(MultiblockInfoCategory.class)
public abstract class MultiblockInfoCategoryMixin extends ModularUIRecipeCategory<MultiblockInfoWrapper> {

    @Inject(method = "registerRecipes", at = @At("HEAD"), cancellable = true, remap = false)
    private static void registerRecipes(IRecipeRegistration registry, @NotNull CallbackInfo ci) {
        BlockableEventLoop<?> executor = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> GTRegistries.MACHINES.values().stream()
                .filter(MultiblockMachineDefinition.class::isInstance)
                .map(MultiblockMachineDefinition.class::cast)
                .filter(MultiblockMachineDefinition::isRenderXEIPreview)
                .map(MultiblockInfoWrapper::new)
                .toList(), executor).thenAcceptAsync(recipes -> {
                    registry.addRecipes(RECIPE_TYPE, recipes);
                }, executor);
        ci.cancel();
    }

    @Override
    public void setRecipe(@NotNull IRecipeLayoutBuilder builder, MultiblockInfoWrapper wrapper, @NotNull IFocusGroup focuses) {
        wrapper.setRecipeWidget(0, 0);
        PreviewJeiTransfer.bind(wrapper);
        List<Widget> flatVisibleWidgetCollection = wrapper.modularUI.getFlatWidgetCollection();
        for (int i = 0; i < flatVisibleWidgetCollection.size(); i++) {
            var widget = flatVisibleWidgetCollection.get(i);
            if (widget instanceof IRecipeIngredientSlot slot) {
                var role = mapToRole(slot.getIngredientIO());
                if (role == null) {
                    gTLCore$addJEISlot(builder, slot, RecipeIngredientRole.INPUT, i);
                    gTLCore$addJEISlot(builder, slot, RecipeIngredientRole.OUTPUT, i);
                } else {
                    gTLCore$addJEISlot(builder, slot, role, i);
                }
            }
        }
    }

    @Unique
    @SuppressWarnings("removal")
    private static void gTLCore$addJEISlot(IRecipeLayoutBuilder builder, IRecipeIngredientSlot slot, RecipeIngredientRole role, int index) {
        var slotName = "slot_" + index;
        IRecipeSlotBuilder slotBuilder = builder.addSlotToWidget(role, (extrasBuilder, recipe, slots) -> {
            var jeiSlot = slots.stream().filter(s -> s.getSlotName().map(name -> name.equals(slotName)).orElse(false)).findFirst();
            jeiSlot.ifPresent(drawable -> extrasBuilder.addWidget(new SlotRecipeWidget(slot, drawable)));
        });
        for (Object ingredient : slot.getXEIIngredients()) {
            if (ingredient instanceof IClickableIngredient clickableIngredient) {
                var type = clickableIngredient.getIngredientType();
                var ingredients = clickableIngredient.getIngredient();
                slotBuilder.addIngredient(type, ingredients);
            }
        }
        slotBuilder.setSlotName(slotName);
        slotBuilder.addRichTooltipCallback((recipeSlotView, tooltipBuilder) -> {
            if (slot instanceof SlotWidget slotWidget) {
                tooltipBuilder.addAll(slotWidget.getAdditionalToolTips(new ArrayList<>()));
            } else if (slot instanceof TankWidget tankWidget) {
                tooltipBuilder.addAll(tankWidget.getAdditionalToolTips(new ArrayList<>()));
            }
        });
    }
}
