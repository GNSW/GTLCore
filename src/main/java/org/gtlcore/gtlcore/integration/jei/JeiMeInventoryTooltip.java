package org.gtlcore.gtlcore.integration.jei;

import org.gtlcore.gtlcore.client.ae2.MeInventoryAmountClient;
import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupOverlay;
import org.gtlcore.gtlcore.utils.TextUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.mojang.datafixers.util.Either;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Optional;

public final class JeiMeInventoryTooltip {

    private static final String FTB_QUESTS_SCREEN_CLASS = "dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen";
    private static final String FTB_QUESTS_CLIENT_FILE_CLASS = "dev.ftb.mods.ftbquests.client.ClientQuestFile";
    private static final String FTB_SCREEN_WRAPPER_CLASS = "dev.ftb.mods.ftblibrary.ui.IScreenWrapper";
    private static final TooltipListener LISTENER = new TooltipListener();
    private static @Nullable IJeiRuntime runtime;
    private static boolean registered;

    private JeiMeInventoryTooltip() {}

    public static void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiMeInventoryTooltip.runtime = runtime;
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(LISTENER);
            registered = true;
        }
    }

    public static void onRuntimeUnavailable() {
        runtime = null;
        if (registered) {
            MinecraftForge.EVENT_BUS.unregister(LISTENER);
            registered = false;
        }
    }

    public static Optional<String> getHoveredIngredientName() {
        IJeiRuntime runtime = JeiMeInventoryTooltip.runtime;
        if (runtime == null) {
            return Optional.empty();
        }
        Optional<ITypedIngredient<?>> hovered = getHoveredIngredient(runtime);
        if (hovered.isPresent()) {
            return toSearchText(hovered.get());
        }

        if (runtime.getRecipesGui().getParentScreen().isEmpty()) {
            return Optional.empty();
        }
        Optional<ItemStack> item = runtime.getRecipesGui().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        if (item.isPresent()) {
            return toSearchText(item.get());
        }
        Optional<FluidStack> fluid = runtime.getRecipesGui().getIngredientUnderMouse(ForgeTypes.FLUID_STACK);
        if (fluid.isPresent()) {
            return toSearchText(fluid.get());
        }
        return Optional.empty();
    }

    public static Optional<AEKey> getHoveredIngredientKey() {
        IJeiRuntime runtime = JeiMeInventoryTooltip.runtime;
        return runtime == null ? Optional.empty() : Optional.ofNullable(getHoveredKey(runtime, null, 0, 0));
    }

    public static Optional<AEKey> getHoveredIngredientKey(Screen screen, double mouseX, double mouseY) {
        IJeiRuntime runtime = JeiMeInventoryTooltip.runtime;
        return runtime == null ? Optional.empty() : Optional.ofNullable(getHoveredKey(runtime, screen, mouseX, mouseY));
    }

    public static boolean isFtbQuestsEditingScreen(Screen screen) {
        boolean questsScreen = false;
        try {
            Object ftbGui = getFtbGui(screen);
            questsScreen = Class.forName(FTB_QUESTS_SCREEN_CLASS).isInstance(ftbGui);
            if (!questsScreen) {
                return false;
            }
            Class<?> clientQuestFileClass = Class.forName(FTB_QUESTS_CLIENT_FILE_CLASS);
            Object clientQuestFile = clientQuestFileClass.getField("INSTANCE").get(null);
            return clientQuestFile != null && (boolean) clientQuestFileClass.getMethod("canEdit").invoke(clientQuestFile);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return questsScreen || screen.getClass().getName().equals(FTB_QUESTS_SCREEN_CLASS);
        }
    }

    private static @Nullable Object getFtbGui(Screen screen) throws ReflectiveOperationException {
        Class<?> questScreenClass = Class.forName(FTB_QUESTS_SCREEN_CLASS);
        if (questScreenClass.isInstance(screen)) {
            return screen;
        }
        Class<?> screenWrapperClass = Class.forName(FTB_SCREEN_WRAPPER_CLASS);
        return screenWrapperClass.isInstance(screen) ? screenWrapperClass.getMethod("getGui").invoke(screen) : null;
    }

    private static @Nullable AEKey getHoveredKey(IJeiRuntime runtime, @Nullable Screen screen, double mouseX,
                                                 double mouseY) {
        Optional<ITypedIngredient<?>> hovered = getHoveredIngredient(runtime, screen, mouseX, mouseY);
        if (hovered.isPresent()) {
            return toKey(hovered.get());
        }
        var overlay = TagGroupOverlay.active();
        if (overlay != null) {
            var minecraft = Minecraft.getInstance();
            var window = minecraft.getWindow();
            double x = screen == null ? minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth() : mouseX;
            double y = screen == null ? minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight() : mouseY;
            // Blank popup cells and its header must not extract an ingredient
            // from the recipe or container underneath the panel.
            if (overlay.contains(x, y)) return null;
        }

        if (runtime.getRecipesGui().getParentScreen().isEmpty()) {
            return null;
        }
        Optional<ItemStack> item = runtime.getRecipesGui().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        if (item.isPresent() && !item.get().isEmpty()) {
            return AEItemKey.of(item.get());
        }
        Optional<FluidStack> fluid = runtime.getRecipesGui().getIngredientUnderMouse(ForgeTypes.FLUID_STACK);
        if (fluid.isPresent() && !fluid.get().isEmpty()) {
            return AEFluidKey.of(fluid.get());
        }
        return null;
    }

    private static Optional<ITypedIngredient<?>> getHoveredIngredient(IJeiRuntime runtime) {
        return getHoveredIngredient(runtime, null, 0, 0);
    }

    private static Optional<ITypedIngredient<?>> getHoveredIngredient(IJeiRuntime runtime, @Nullable Screen screen,
                                                                      double mouseX, double mouseY) {
        var overlay = TagGroupOverlay.active();
        if (overlay != null) {
            if (screen == null) {
                var minecraft = Minecraft.getInstance();
                var window = minecraft.getWindow();
                mouseX = minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
                mouseY = minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
            }
            if (overlay.contains(mouseX, mouseY)) {
                // Resolve the exact expanded element before the normal JEI list
                // or the underlying screen. Shared by extraction, ordering and search.
                return overlay.hoveredSlot(mouseX, mouseY).flatMap(slot -> slot.getOptionalElement())
                        .map(element -> element.getTypedIngredient());
            }
        }
        Optional<ITypedIngredient<?>> hovered = runtime.getIngredientListOverlay().getIngredientUnderMouse();
        if (hovered.isEmpty()) {
            hovered = runtime.getBookmarkOverlay().getIngredientUnderMouse();
        }
        if (hovered.isEmpty() && screen != null && isFtbScreen(screen)) {
            Optional<IClickableIngredient<?>> clickable = runtime.getScreenHelper()
                    .getClickableIngredientUnderMouse(screen, mouseX, mouseY)
                    .findFirst();
            if (clickable.isPresent()) {
                hovered = Optional.of(clickable.get().getTypedIngredient());
            }
        }
        return hovered;
    }

    private static boolean isFtbScreen(Screen screen) {
        try {
            Class<?> screenWrapper = Class.forName(FTB_SCREEN_WRAPPER_CLASS);
            return screenWrapper.isInstance(screen);
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private static Optional<String> toSearchText(ITypedIngredient<?> ingredient) {
        Optional<ItemStack> item = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
        if (item.isPresent()) {
            return toSearchText(item.get());
        }
        Optional<FluidStack> fluid = ingredient.getIngredient(ForgeTypes.FLUID_STACK);
        if (fluid.isPresent()) {
            return toSearchText(fluid.get());
        }
        return Optional.empty();
    }

    private static Optional<String> toSearchText(ItemStack item) {
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(item.getHoverName().getString()).filter(name -> !name.isBlank());
    }

    private static Optional<String> toSearchText(FluidStack fluid) {
        if (fluid.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fluid.getDisplayName().getString()).filter(name -> !name.isBlank());
    }

    private static @Nullable AEKey toKey(ITypedIngredient<?> ingredient) {
        Optional<ItemStack> item = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
        if (item.isPresent() && !item.get().isEmpty()) {
            return AEItemKey.of(item.get());
        }
        Optional<FluidStack> fluid = ingredient.getIngredient(ForgeTypes.FLUID_STACK);
        if (fluid.isPresent() && !fluid.get().isEmpty()) {
            return AEFluidKey.of(fluid.get());
        }
        return null;
    }

    private static final class TooltipListener {

        @SubscribeEvent
        public void gatherTooltip(RenderTooltipEvent.GatherComponents event) {
            IJeiRuntime runtime = JeiMeInventoryTooltip.runtime;
            if (runtime == null) {
                return;
            }
            AEKey key = getHoveredKey(runtime, null, 0, 0);
            if (key == null) {
                return;
            }
            Optional<ITypedIngredient<?>> overlayIngredient = getHoveredIngredient(runtime);
            if (overlayIngredient.isPresent() && toKey(overlayIngredient.get()) instanceof AEFluidKey fluidKey) {
                appendChemicalFormula(event, fluidKey);
            }
            MeInventoryAmountClient.getTooltip(key).ifPresent(component -> event.getTooltipElements()
                    .add(Either.<FormattedText, TooltipComponent>left(component)));
        }

        private static void appendChemicalFormula(RenderTooltipEvent.GatherComponents event, AEFluidKey fluidKey) {
            var formula = new ArrayList<Component>(1);
            TextUtil.appendChemicalFormulaTooltip(fluidKey.getFluid(), formula);
            if (!formula.isEmpty()) {
                event.getTooltipElements().add(
                        Math.min(1, event.getTooltipElements().size()),
                        Either.<FormattedText, TooltipComponent>left(formula.get(0)));
            }
        }
    }
}
