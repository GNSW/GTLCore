package org.gtlcore.gtlcore.api.gui;

import org.gtlcore.gtlcore.client.gui.widget.RecipePatternBrowserWidget;
import org.gtlcore.gtlcore.client.gui.widget.RecipePatternBrowserWidget.Page;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MERecipePatternBufferPartMachine;

import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.common.data.GTItems;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Dedicated pages in the machine's normal side-tab navigation. */
public class RecipePatternManagementPage implements IFancyUIProvider {

    private final MERecipePatternBufferPartMachine machine;
    private final Page page;

    public RecipePatternManagementPage(MERecipePatternBufferPartMachine machine, Page page) {
        this.machine = machine;
        this.page = page;
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget fancyMachineUIWidget) {
        return new RecipePatternBrowserWidget(0, 0, machine, page);
    }

    @Override
    public IGuiTexture getTabIcon() {
        return new ItemStackTexture(page == Page.SEARCH ? new ItemStack(Items.KNOWLEDGE_BOOK) : GTItems.ITEM_FILTER.asStack());
    }

    @Override
    public Component getTitle() {
        String title = page == Page.FILTERS ? "lists_tab" : "search_tab";
        return Component.translatable("gtceu.machine.me_recipe_pattern_buffer." + title);
    }

    @Override
    public List<Component> getTabTooltips() {
        if (page == Page.FILTERS) {
            return List.of(getTitle(), Component.translatable("gtceu.machine.me_recipe_pattern_buffer.lists_hint"));
        }
        return List.of(getTitle());
    }

    @Override
    public boolean hasPlayerInventory() {
        return false;
    }
}
