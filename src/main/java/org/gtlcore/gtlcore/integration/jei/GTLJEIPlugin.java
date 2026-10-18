package org.gtlcore.gtlcore.integration.jei;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.ae2.graph.CraftingRingScreen;
import org.gtlcore.gtlcore.client.ae2.graph.GraphViewSettingsScreen;
import org.gtlcore.gtlcore.client.ae2.wireless.MEChamberManagerTerminalScreen;
import org.gtlcore.gtlcore.common.data.machines.MultiBlockMachineA;
import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupOverlay;

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.WorldGenLayers;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import com.lowdragmc.lowdraglib.LDLib;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.Tags;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@JeiPlugin
public class GTLJEIPlugin implements IModPlugin {

    private static final String ORE_ITEM_SUFFIX = "_ore";

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return GTLCore.id("jei_plugin");
    }

    @Override
    public void registerRecipeCatalysts(@NotNull IRecipeCatalystRegistration registration) {
        if (LDLib.isReiLoaded() || LDLib.isEmiLoaded()) return;
        registration.addRecipeCatalyst(MultiBlockMachineA.ADVANCED_MULTI_SMELTER.asStack(), RecipeTypes.SMELTING);
        registration.addRecipeCatalyst(MultiBlockMachineA.DIMENSIONALLY_TRANSCENDENT_STEAM_OVEN.asStack(), RecipeTypes.SMELTING);
    }

    @Override
    public void registerItemSubtypes(@NotNull ISubtypeRegistration registration) {
        if (LDLib.isReiLoaded() || LDLib.isEmiLoaded()) return;
        registration.useNbtForSubtypes(GTItems.TURBINE_ROTOR.asItem());
        registration.useNbtForSubtypes(GTItems.INTEGRATED_CIRCUIT.asItem());
    }

    @Override
    public void registerGuiHandlers(@NotNull IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(CraftingRingScreen.class, new IGuiContainerHandler<>() {

            @Override
            public List<Rect2i> getGuiExtraAreas(CraftingRingScreen screen) {
                return screen.getExclusionZones();
            }

            @Override
            public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(CraftingRingScreen screen, double x, double y) {
                var stack = screen.getStackUnderMouse(x, y);
                return stack == null ? Optional.empty() : JeiMissingIngredientBookmarks.createClickableIngredient(stack.stack().what(), stack.bounds());
            }
        });
        registration.addGuiContainerHandler(GraphViewSettingsScreen.class, new IGuiContainerHandler<>() {

            @Override
            public List<Rect2i> getGuiExtraAreas(GraphViewSettingsScreen screen) {
                return screen.getExclusionZones();
            }
        });
        registration.addGuiContainerHandler(
                MEChamberManagerTerminalScreen.class,
                new IGuiContainerHandler<>() {

                    @Override
                    public List<Rect2i> getGuiExtraAreas(MEChamberManagerTerminalScreen screen) {
                        return screen.getExclusionZones();
                    }

                    @Override
                    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
                                                                                              MEChamberManagerTerminalScreen screen, double mouseX, double mouseY) {
                        return screen.getJeiClickableIngredientUnderMouse(mouseX, mouseY);
                    }
                });
        registration.addGhostIngredientHandler(
                MEChamberManagerTerminalScreen.class,
                new IGhostIngredientHandler<>() {

                    @Override
                    public <I> List<Target<I>> getTargetsTyped(MEChamberManagerTerminalScreen screen,
                                                               ITypedIngredient<I> ingredient, boolean doStart) {
                        return screen.getJeiGhostIngredientTargets(ingredient);
                    }

                    @Override
                    public void onComplete() {}
                });
    }

    @Override
    public void onRuntimeAvailable(@NotNull IJeiRuntime runtime) {
        JeiMeInventoryTooltip.onRuntimeAvailable(runtime);
        JeiMissingIngredientBookmarks.onRuntimeAvailable(runtime);
        hideUnobtainableOres(runtime);
        var list = new ArrayList<ItemStack>();
        for (int i = 1; i <= 32; i++) {
            list.add(IntCircuitBehaviour.stack(i));
        }
        runtime.getIngredientManager().addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, list);
    }

    @Override
    public void onRuntimeUnavailable() {
        TagGroupOverlay.closeActive();
        JeiMeInventoryTooltip.onRuntimeUnavailable();
        JeiMissingIngredientBookmarks.onRuntimeUnavailable();
    }

    private static void hideUnobtainableOres(IJeiRuntime runtime) {
        var obtainableOres = getObtainableOres();
        if (obtainableOres == null) {
            return;
        }

        var unobtainableOres = runtime.getIngredientManager().getAllItemStacks().stream()
                .filter(GTLJEIPlugin::isOreItem)
                .filter(stack -> !obtainableOres.contains(stack.getItem()))
                .toList();

        runtime.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, unobtainableOres);
        GTLCore.LOGGER.info("Hidden {} unobtainable ore blocks from JEI", unobtainableOres.size());
    }

    private static Set<Item> getObtainableOres() {
        Set<Item> obtainableOres = new HashSet<>();

        for (GTOreDefinition oreDefinition : GTRegistries.ORE_VEINS.values()) {
            Set<TagPrefix> hostPrefixes = getHostPrefixes(oreDefinition);
            for (var entry : oreDefinition.veinGenerator().getAllEntries()) {
                entry.getKey().map(
                        blockState -> obtainableOres.add(blockState.getBlock().asItem()),
                        material -> {
                            if (material != null) {
                                for (TagPrefix prefix : hostPrefixes) {
                                    var oreBlock = GTBlocks.MATERIAL_BLOCKS.get(prefix, material);
                                    if (oreBlock != null) {
                                        obtainableOres.add(oreBlock.asItem());
                                    }
                                }
                            }
                            return false;
                        });
            }
        }
        return addRecipeOutputs(obtainableOres) ? obtainableOres : null;
    }

    /**
     * Include ores produced by custom machines (for example, space elevator
     * modules), which are not represented by world-generation vein entries.
     */
    private static boolean addRecipeOutputs(Set<Item> obtainableOres) {
        var minecraft = Minecraft.getInstance();
        var recipeManager = minecraft.level != null ? minecraft.level.getRecipeManager() :
                minecraft.getConnection() != null ? minecraft.getConnection().getRecipeManager() : null;
        if (recipeManager == null) {
            GTLCore.LOGGER.warn("Skipped recipe-based ore visibility because the client recipe manager is unavailable");
            return false;
        }

        for (var recipe : recipeManager.getRecipes()) {
            if (!(recipe instanceof GTRecipe gtRecipe)) {
                continue;
            }
            addOreItemsFromContents(gtRecipe.getOutputContents(ItemRecipeCapability.CAP), obtainableOres);
            addOreItemsFromContents(gtRecipe.getTickOutputContents(ItemRecipeCapability.CAP), obtainableOres);
        }
        return true;
    }

    private static void addOreItemsFromContents(List<com.gregtechceu.gtceu.api.recipe.content.Content> contents,
                                                Set<Item> obtainableOres) {
        for (var content : contents) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
            for (ItemStack stack : ingredient.getItems()) {
                if (isOreItem(stack)) {
                    obtainableOres.add(stack.getItem());
                }
            }
        }
    }

    private static Set<TagPrefix> getHostPrefixes(GTOreDefinition oreDefinition) {
        Set<TagPrefix> hostPrefixes = new HashSet<>();
        for (var dimension : oreDefinition.dimensionFilter()) {
            var marker = GTRegistries.DIMENSION_MARKERS.get(dimension.location());
            List<TagPrefix> markerPrefixes = marker == null ? List.of() : TagPrefix.ORES.entrySet().stream()
                    .filter(entry -> entry.getValue().stoneType().get().getBlock().asItem() == marker.getIcon().getItem())
                    .map(java.util.Map.Entry::getKey)
                    .toList();
            if (markerPrefixes.isEmpty()) {
                addBuiltinLayerPrefix(hostPrefixes, oreDefinition);
            } else {
                hostPrefixes.addAll(markerPrefixes);
            }
        }

        if (oreDefinition.dimensionFilter().isEmpty()) {
            addBuiltinLayerPrefix(hostPrefixes, oreDefinition);
        }
        if (hostPrefixes.isEmpty()) {
            TagPrefix.ORES.forEach((prefix, oreType) -> {
                if (oreDefinition.layer().getTarget().test(oreType.stoneType().get(), RandomSource.create())) {
                    hostPrefixes.add(prefix);
                }
            });
        }
        return hostPrefixes;
    }

    private static void addBuiltinLayerPrefix(Set<TagPrefix> hostPrefixes, GTOreDefinition oreDefinition) {
        if (oreDefinition.layer() == WorldGenLayers.STONE) {
            hostPrefixes.add(TagPrefix.ore);
        } else if (oreDefinition.layer() == WorldGenLayers.DEEPSLATE) {
            hostPrefixes.add(TagPrefix.oreDeepslate);
        } else if (oreDefinition.layer() == WorldGenLayers.NETHERRACK) {
            hostPrefixes.add(TagPrefix.oreNetherrack);
        } else if (oreDefinition.layer() == WorldGenLayers.ENDSTONE) {
            hostPrefixes.add(TagPrefix.oreEndstone);
        }
    }

    private static boolean isOreItem(ItemStack stack) {
        if (stack.is(Tags.Items.ORES)) {
            return true;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return stack.getItem() instanceof BlockItem && itemId.getPath().endsWith(ORE_ITEM_SUFFIX);
    }
}
