package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.client.ae2.JeiTerminalSearchTarget;
import org.gtlcore.gtlcore.utils.TextUtil;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.api.stacks.AEFluidKey;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.integration.abstraction.ItemListMod;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.MEStorageMenu;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(MEStorageScreen.class)
public abstract class MEStorageScreenMixin<C extends MEStorageMenu> extends AEBaseScreen<C>
                                          implements JeiTerminalSearchTarget {

    @Shadow(remap = false)
    @Final
    private AETextField searchField;

    private MEStorageScreenMixin(C menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Override
    public void gtlcore$setJeiSearchText(String searchText) {
        this.searchField.setValue(searchText);
        if (this.config.isUseExternalSearch() || this.config.isSyncWithExternalSearch()) {
            ItemListMod.setSearchText(searchText);
        }
    }

    @ModifyArg(
               method = "renderGridInventoryEntryTooltip",
               at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/GuiGraphics;renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V"),
               index = 1)
    private List<Component> gtlcore$appendFluidChemicalFormula(
                                                               List<Component> tooltip, @Local GridInventoryEntry entry) {
        if (entry.getWhat() instanceof AEFluidKey fluidKey) {
            TextUtil.appendChemicalFormulaTooltip(fluidKey.getFluid(), tooltip);
        }
        return tooltip;
    }
}
