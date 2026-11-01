package org.gtlcore.gtlcore.mixin.gtm.gui;

import org.gtlcore.gtlcore.client.gui.MEStorageConfiguratorTabLayout;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.integration.terminal.host.TerminalAccess;
import org.gtlcore.gtlcore.integration.terminal.host.TerminalHostPanel;

import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
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

    @Inject(method = "setupFancyUI(Lcom/gregtechceu/gtceu/api/gui/fancy/IFancyUIProvider;Z)V",
            at = @At("TAIL"))
    private void gtlcore$attachTerminalHostControls(IFancyUIProvider page, boolean hasPlayerInventory,
                                                    CallbackInfo ci) {
        FancyMachineUIWidget self = (FancyMachineUIWidget) (Object) this;
        for (TerminalHostPanel panel : self.getWidgetsByType(TerminalHostPanel.class)) {
            self.removeWidget(panel);
        }

        if (self.getGui() == null || self.getGui().entityPlayer == null ||
                !(self.getMainPage() instanceof IMultiController controller)) {
            return;
        }

        TerminalAccess.EquippedTerminal terminal = TerminalAccess.find(self.getGui().entityPlayer);
        self.addWidget(new TerminalHostPanel(self, controller, terminal));
    }
}
