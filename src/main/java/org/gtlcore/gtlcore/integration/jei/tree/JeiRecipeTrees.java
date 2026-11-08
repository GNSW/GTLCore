package org.gtlcore.gtlcore.integration.jei.tree;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.style.Blitter;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adds EMI-style "view recipe tree" and "set default recipe" buttons to every JEI recipe.
 */
public final class JeiRecipeTrees {

    private static final IDrawable TREE_ICON = new WrappedIcon(
            new ResourceLocation("gtlcore", "textures/gui/recipe_tree.png"),
            0, 0, 16, 16, 16, 16);
    private static final IDrawable WRENCH_OFF = aeWrench(false);
    private static final IDrawable WRENCH_ON = aeWrench(true);
    private static @Nullable IJeiRuntime runtime;

    private static IDrawable aeWrench(boolean active) {
        Blitter blitter = (active ? appeng.client.gui.Icon.WRENCH : appeng.client.gui.Icon.WRENCH_DISABLED)
                .getBlitter();
        return new WrappedIcon(
                new ResourceLocation("ae2", "textures/guis/states.png"),
                blitter.getSrcX(), blitter.getSrcY(), blitter.getSrcWidth(), blitter.getSrcHeight(),
                256, 256);
    }

    private static final class WrappedIcon implements IDrawable {

        private final ResourceLocation texture;
        private final int u;
        private final int v;
        private final int uWidth;
        private final int vHeight;
        private final int textureWidth;
        private final int textureHeight;

        private WrappedIcon(ResourceLocation texture, int u, int v, int uWidth, int vHeight, int textureWidth,
                            int textureHeight) {
            this.texture = texture;
            this.u = u;
            this.v = v;
            this.uWidth = uWidth;
            this.vHeight = vHeight;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
        }

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
            graphics.blit(texture, xOffset, yOffset, 16, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);
        }
    }

    private JeiRecipeTrees() {}

    public static void register(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new TreeButtonFactory());
        registration.addRecipeButtonFactory(new DefaultButtonFactory());
    }

    public static void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        RecipeTreeState.onRuntimeAvailable(jeiRuntime);
    }

    public static void onRuntimeUnavailable() {
        runtime = null;
        RecipeTreeState.onRuntimeUnavailable();
    }

    static boolean hasTreeOutput(IRecipeLayoutDrawable<?> layout) {
        return layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                .anyMatch(slot -> !slot.isEmpty());
    }

    static List<TreeIngredient> outputs(@Nullable IJeiRuntime jeiRuntime,
                                        IRecipeLayoutDrawable<?> layout) {
        if (jeiRuntime == null) {
            return List.of();
        }
        List<TreeIngredient> outputs = new ArrayList<>();
        for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                .getSlotViews(RecipeIngredientRole.OUTPUT)) {
            List<ITypedIngredient<?>> ingredients = slot.getAllIngredientsList().stream()
                    .filter(Objects::nonNull)
                    .toList();
            if (ingredients.isEmpty()) {
                continue;
            }
            TreeIngredient ingredient = TreeIngredient.of(jeiRuntime.getIngredientManager(),
                    ingredients, 1.0F);
            if (!ingredient.isEmpty()) {
                outputs.add(ingredient);
            }
        }
        return outputs;
    }

    private static final class TreeButtonFactory implements IRecipeButtonControllerFactory {

        @Override
        public <T> @Nullable IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            return hasTreeOutput(layout) ? new RecipeTreeButton(layout) : null;
        }
    }

    private static final class DefaultButtonFactory implements IRecipeButtonControllerFactory {

        @Override
        public <T> @Nullable IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            return hasTreeOutput(layout) ? new DefaultRecipeButton(layout) : null;
        }
    }

    private static final class RecipeTreeButton implements IIconButtonController {

        private final IRecipeLayoutDrawable<?> layout;
        private @Nullable TreeRecipe recipe;

        private RecipeTreeButton(IRecipeLayoutDrawable<?> layout) {
            this.layout = layout;
        }

        private @Nullable TreeRecipe recipe() {
            if (recipe == null && runtime != null) {
                recipe = TreeRecipe.of(runtime, layout);
            }
            return recipe;
        }

        @Override
        public void initState(IButtonState state) {
            state.setIcon(TREE_ICON);
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            TreeRecipe current = recipe();
            state.setForcePressed(current != null && RecipeTreeState.isGoal(current));
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            if (input.isSimulate()) {
                return true;
            }
            IJeiRuntime jeiRuntime = runtime;
            TreeRecipe current = recipe();
            if (jeiRuntime == null || current == null) {
                return false;
            }
            Minecraft minecraft = Minecraft.getInstance();
            RecipeTreeState.setGoal(current);
            Screen parent = jeiRuntime.getRecipesGui().getParentScreen()
                    .orElse(minecraft.screen);
            minecraft.setScreen(new JeiRecipeTreeScreen(parent, jeiRuntime));
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable("gtlcore.jei.recipe_tree.open"));
            tooltip.add(Component.translatable("gtlcore.jei.recipe_tree.open.detail"));
        }
    }

    private static final class DefaultRecipeButton implements IIconButtonController {

        private final IRecipeLayoutDrawable<?> layout;
        private @Nullable TreeRecipe recipe;
        private List<TreeIngredient> outputs = List.of();
        private boolean resolved;

        private DefaultRecipeButton(IRecipeLayoutDrawable<?> layout) {
            this.layout = layout;
        }

        private void resolve() {
            if (!resolved && runtime != null) {
                recipe = TreeRecipe.of(runtime, layout);
                outputs = outputs(runtime, layout);
                resolved = true;
            }
        }

        @Override
        public void initState(IButtonState state) {
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            resolve();
            RecipeTreeState.DefaultStatus status = status();
            state.setIcon(switch (status) {
                case EMPTY, PARTIAL -> WRENCH_OFF;
                case FULL -> WRENCH_ON;
            });
            state.setForcePressed(status == RecipeTreeState.DefaultStatus.FULL);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            if (input.isSimulate()) {
                return true;
            }
            resolve();
            if (runtime == null || recipe == null || outputs.isEmpty()) {
                return false;
            }
            if (status() == RecipeTreeState.DefaultStatus.FULL) {
                RecipeTreeState.removeRecipe(recipe.identity(), outputs);
            } else {
                RecipeTreeState.addRecipe(recipe.identity(), outputs);
            }
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            resolve();
            if (recipe == null) {
                return;
            }
            RecipeTreeState.DefaultStatus status = status();
            if (status == RecipeTreeState.DefaultStatus.FULL) {
                tooltip.add(Component.translatable("gtlcore.jei.recipe_tree.default.clear"));
            } else {
                tooltip.add(Component.translatable("gtlcore.jei.recipe_tree.default.set"));
                if (status == RecipeTreeState.DefaultStatus.PARTIAL) {
                    tooltip.add(Component.translatable("gtlcore.jei.recipe_tree.default.partial"));
                    for (TreeIngredient output : outputs) {
                        if (recipe.identity().equals(
                                RecipeTreeState.recipeIdentity(output))) {
                            tooltip.add(Component.literal("  " + output.displayVariant().name()));
                        }
                    }
                }
            }
            tooltip.add(Component.translatable("gtlcore.jei.recipe_tree.default.detail"));
        }

        private RecipeTreeState.DefaultStatus status() {
            resolve();
            if (recipe == null) {
                return RecipeTreeState.DefaultStatus.EMPTY;
            }
            return RecipeTreeState.getRecipeStatus(recipe.identity(), outputs);
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

    private static final class StarIcon implements IDrawable {

        private final int state;

        private StarIcon(int state) {
            this.state = state;
        }

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
            int gold = 0xFFFFD35A;
            int shadow = 0xFF8D6B16;
            int dark = 0xFF3A3520;
            int fill = switch (state) {
                case 2 -> gold;
                case 1 -> shadow;
                default -> dark;
            };
            graphics.fill(xOffset + 7, yOffset + 2, xOffset + 9, yOffset + 4, gold);
            graphics.fill(xOffset + 3, yOffset + 6, xOffset + 13, yOffset + 8, gold);
            graphics.fill(xOffset + 4, yOffset + 8, xOffset + 12, yOffset + 10, fill);
            graphics.fill(xOffset + 5, yOffset + 10, xOffset + 7, yOffset + 14, fill);
            graphics.fill(xOffset + 9, yOffset + 10, xOffset + 11, yOffset + 14, fill);
        }
    }
}
