package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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

    /**
     * EMI's recipe button sheet, copied verbatim: u=36 is the recipe tree button and u=48 the
     * default recipe button, whose v offset selects the empty/hover/default states.
     */
    private static final ResourceLocation EMI_BUTTONS = new ResourceLocation(GTLCore.MOD_ID, "textures/gui/emi/buttons.png");
    private static final IDrawable TREE_ICON = new TextureIcon(36, 0);
    private static final IDrawable STAR_EMPTY = new TextureIcon(48, 0);
    private static final IDrawable STAR_PARTIAL = new TextureIcon(48, 60);
    private static final IDrawable STAR_FULL = new TextureIcon(48, 36);
    private static @Nullable IJeiRuntime runtime;

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
                case EMPTY -> STAR_EMPTY;
                case PARTIAL -> STAR_PARTIAL;
                case FULL -> STAR_FULL;
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

    private static final class TextureIcon implements IDrawable {

        private final int u;
        private final int v;

        private TextureIcon(int u, int v) {
            this.u = u;
            this.v = v;
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
            // EMI draws its recipe buttons as 12x12 sprites, centred in the 16x16 button area.
            graphics.blit(EMI_BUTTONS, xOffset + 2, yOffset + 2, u, v, 12, 12);
        }
    }
}
