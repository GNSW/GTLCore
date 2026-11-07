package org.gtlcore.gtlcore.mixin.extendedae;

import org.gtlcore.gtlcore.client.ae2.JeiTerminalSearchTarget;
import org.gtlcore.gtlcore.client.ae2.PatternSearchHighlight;
import org.gtlcore.gtlcore.client.ae2.wireless.UniversalSearch;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternEncoderMetadata;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.me.patternaccess.PatternSlot;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal;
import com.glodblock.github.extendedae.container.ContainerExPatternTerminal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

@Mixin(value = GuiExPatternTerminal.class, remap = false)
public abstract class GuiExPatternTerminalMixin implements JeiTerminalSearchTarget {

    @Shadow
    @Final
    private Set<ItemStack> matchedStack;

    @Shadow
    @Final
    private AETextField searchOutField;

    @Shadow
    @Final
    private AETextField searchInField;

    @Override
    public void gtlcore$setJeiSearchText(String searchText) {
        AETextField targetField = this.searchInField.isFocused() ? this.searchInField : this.searchOutField;
        targetField.setValue(searchText);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void gtlcore$updateOutputSearchTooltip(ContainerExPatternTerminal menu, Inventory inventory,
                                                   Component title, ScreenStyle style, CallbackInfo ci) {
        this.searchOutField.setTooltipMessage(List.of(Component.translatable(
                "tooltip.gtlcore.ex_pattern_terminal.output_search")));
    }

    @Inject(method = "itemStackMatchesSearchTerm", at = @At("HEAD"), cancellable = true)
    private void gtlcore$matchPatternEncoder(ItemStack stack, List<String> searchTokens, boolean outputSearch,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (!outputSearch || stack.isEmpty()) {
            return;
        }
        var encoder = PatternEncoderMetadata.readEncoder(stack);
        if (encoder.isEmpty()) {
            return;
        }
        String encoderName = encoder.get().displayName();
        if (searchTokens.stream().allMatch(searchToken -> UniversalSearch.contains(encoderName, searchToken))) {
            this.matchedStack.add(stack);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "drawFG", at = @At("TAIL"))
    private void gtlcore$drawMatchedPatternBorders(GuiGraphics graphics, int offsetX, int offsetY,
                                                   int mouseX, int mouseY, CallbackInfo ci) {
        if (this.searchOutField.getValue().isEmpty() && this.searchInField.getValue().isEmpty()) {
            return;
        }
        var menu = ((GuiExPatternTerminal<?>) (Object) this).getMenu();
        for (var slot : menu.slots) {
            if (slot instanceof PatternSlot patternSlot && this.matchedStack.contains(patternSlot.getItem())) {
                PatternSearchHighlight.drawBorder(graphics, patternSlot.x, patternSlot.y);
            }
        }
    }
}
