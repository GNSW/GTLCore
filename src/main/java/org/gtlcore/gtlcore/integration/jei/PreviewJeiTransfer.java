package org.gtlcore.gtlcore.integration.jei;

import org.gtlcore.gtlcore.api.gui.PatternPreviewWidget;
import org.gtlcore.gtlcore.integration.ae2.pattern.MultiblockPatternEncoding;

import com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoCategory;
import com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoWrapper;

import com.lowdragmc.lowdraglib.gui.widget.Widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import appeng.api.stacks.GenericStack;
import appeng.integration.modules.jei.GenericEntryStackHelper;
import appeng.menu.me.items.PatternEncodingTermMenu;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.runtime.IJeiRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Routes the fullscreen draft through the same registered handler as JEI's transfer button. */
public final class PreviewJeiTransfer {

    private static IJeiRuntime runtime;

    private PreviewJeiTransfer() {}

    public static void setRuntime(IJeiRuntime runtime) {
        PreviewJeiTransfer.runtime = runtime;
    }

    public static void bind(MultiblockInfoWrapper recipe) {
        Widget widget = recipe.getWidget();
        if (widget instanceof PatternPreviewWidget preview) {
            preview.getPreviewControls().setPatternTransfer(PreviewJeiTransfer::available, () -> transfer(recipe, preview));
        }
    }

    private static Optional<IRecipeTransferHandler<PatternEncodingTermMenu, MultiblockInfoWrapper>> handler(PatternEncodingTermMenu menu) {
        var category = runtime.getRecipeManager().getRecipeCategory(MultiblockInfoCategory.RECIPE_TYPE);
        return runtime.getRecipeTransferManager().getRecipeTransferHandler(menu, category);
    }

    private static boolean available() {
        var player = Minecraft.getInstance().player;
        return runtime != null && player != null && player.containerMenu instanceof PatternEncodingTermMenu menu &&
                runtime.getRecipesGui().getParentScreen()
                        .filter(screen -> screen instanceof AbstractContainerScreen<?> container && container.getMenu() == menu).isPresent() &&
                handler(menu).isPresent();
    }

    private static void transfer(MultiblockInfoWrapper recipe, PatternPreviewWidget preview) {
        if (!available()) return;
        var mc = Minecraft.getInstance();
        var player = mc.player;
        var menu = (PatternEncodingTermMenu) player.containerMenu;
        var parent = runtime.getRecipesGui().getParentScreen().orElseThrow();
        var handler = handler(menu).orElseThrow();
        var slots = new ArrayList<IRecipeSlotView>();
        for (var options : preview.patternInputs()) {
            addSlot(slots, options.get(0), RecipeIngredientRole.INPUT);
        }
        for (var output : MultiblockPatternEncoding.outputs(recipe.definition)) {
            addSlot(slots, output, RecipeIngredientRole.OUTPUT);
        }
        IRecipeSlotsView view = () -> List.copyOf(slots);
        var error = handler.transferRecipe(menu, recipe, view, player, false, false);
        if (error == null || error.getType().allowsTransfer) {
            error = handler.transferRecipe(menu, recipe, view, player, false, true);
            if (error == null) {
                mc.setScreen(parent);
                return;
            }
        }
        player.displayClientMessage(Component.translatable("gui.gtlcore.preview.transfer_failed"), false);
    }

    private static void addSlot(List<IRecipeSlotView> slots, GenericStack stack, RecipeIngredientRole role) {
        var ingredient = GenericEntryStackHelper.stackToIngredient(runtime.getIngredientManager(), stack);
        slots.add(runtime.getRecipeManager().createRecipeSlotDrawable(role, List.of(Optional.of(ingredient)), Set.of(), 0));
    }
}
