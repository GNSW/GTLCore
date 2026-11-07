package org.gtlcore.gtlcore.integration.jei.tree;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges JEI's recipe-button API to GTLCore's recipe tree screen.
 *
 * <p>
 * The button is deliberately implemented as an add-on controller instead of changing JEI's
 * recipe GUI. This keeps the feature compatible with other JEI extensions and lets the normal
 * JEI transfer and bookmark buttons retain their behavior.
 * </p>
 */
public final class JeiRecipeTrees {

    private static final IDrawable ICON = new TreeIcon();
    private static @Nullable IJeiRuntime runtime;

    private JeiRecipeTrees() {}

    public static void register(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new ButtonFactory());
    }

    public static void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    public static void onRuntimeUnavailable() {
        runtime = null;
    }

    static @Nullable IJeiRuntime runtime() {
        return runtime;
    }

    static boolean hasTreeOutput(IRecipeLayoutDrawable<?> layout) {
        return layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                .anyMatch(slot -> !slot.isEmpty());
    }

    private static final class ButtonFactory implements IRecipeButtonControllerFactory {

        @Override
        public <T> @Nullable IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            return hasTreeOutput(layout) ? new RecipeTreeButton(layout) : null;
        }
    }

    private record RecipeTreeButton(IRecipeLayoutDrawable<?> layout) implements IIconButtonController {

        @Override
        public void initState(IButtonState state) {
            state.setIcon(ICON);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            if (input.isSimulate()) {
                return true;
            }
            if (runtime == null) {
                return false;
            }
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(new JeiRecipeTreeScreen(minecraft.screen, runtime, layout));
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable("gtlcore.jei.recipe_tree.open"));
            tooltip.add(Component.translatable("gtlcore.jei.recipe_tree.open.detail"));
        }
    }

    private static final class TreeIcon implements IDrawable {

        @Override
        public int getWidth() {
            return 16;
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
            int trunk = 0xFF77D79B;
            int shadow = 0xFF1F6B42;
            int leaf = 0xFFB9F3C8;
            graphics.fill(xOffset + 7, yOffset + 4, xOffset + 9, yOffset + 14, shadow);
            graphics.fill(xOffset + 3, yOffset + 5, xOffset + 13, yOffset + 7, trunk);
            graphics.fill(xOffset + 3, yOffset + 5, xOffset + 5, yOffset + 9, trunk);
            graphics.fill(xOffset + 11, yOffset + 5, xOffset + 13, yOffset + 9, trunk);
            graphics.fill(xOffset + 2, yOffset + 2, xOffset + 6, yOffset + 5, leaf);
            graphics.fill(xOffset + 10, yOffset + 2, xOffset + 14, yOffset + 5, leaf);
            graphics.fill(xOffset + 6, yOffset + 12, xOffset + 10, yOffset + 15, leaf);
        }
    }
}
