package org.gtlcore.gtlcore.mixin.gtm.gui;

import org.gtlcore.gtlcore.client.gui.MEStorageConfiguratorTabLayout;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;

import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.integration.ae2.machine.MEHatchPartMachine;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FancyMachineUIWidget.class, remap = false)
public abstract class FancyMachineUIWidgetMixin {

    @Shadow
    @Final
    protected ConfiguratorPanel configuratorPanel;

    @Inject(method = "setupFancyUI(Lcom/gregtechceu/gtceu/api/gui/fancy/IFancyUIProvider;Z)V", at = @At("HEAD"))
    private void gtlcore$configureMEStorageTabColumns(IFancyUIProvider page, boolean hasPlayerInventory,
                                                      CallbackInfo ci) {
        MEStorageConfiguratorTabLayout.setEnabled(configuratorPanel,
                page instanceof MEHatchPartMachine || page instanceof MEPatternBufferPartMachineBase);
    }
}
