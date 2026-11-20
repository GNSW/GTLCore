package org.gtlcore.gtlcore.mixin.gtm.cover;

import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.cover.CoverBehavior;
import com.gregtechceu.gtceu.api.gui.widget.directional.handlers.CoverableConfigHandler;

import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CoverableConfigHandler.class)
public abstract class CoverableConfigHandlerMixin {

    @Shadow(remap = false)
    private ICoverable machine;
    @Shadow(remap = false)
    private Direction side;
    @Shadow(remap = false)
    private ItemStackTransfer transfer;

    @Unique
    private CoverBehavior gtlcore$lastPlaced;
    @Unique
    private long gtlcore$lastPlacedTick = -1L;

    @Inject(method = "coverItemChanged", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$skipRedundantCoverChange(CallbackInfo ci) {
        if (machine == null || side == null || transfer == null) {
            return;
        }
        CoverBehavior current = machine.getCoverAtSide(side);
        if (current == null || current != gtlcore$lastPlaced) {
            return;
        }
        var level = machine.getLevel();
        if (level == null || level.getGameTime() != gtlcore$lastPlacedTick) {
            return;
        }
        ItemStack stack = transfer.getStackInSlot(0);
        if (ItemStack.isSameItemSameTags(stack, current.getAttachItem())) {
            ci.cancel();
        }
    }

    @Inject(method = "coverItemChanged", at = @At("TAIL"), remap = false)
    private void gtlcore$rememberPlacedCover(CallbackInfo ci) {
        if (machine == null || side == null) {
            return;
        }
        var level = machine.getLevel();
        if (level == null) {
            return;
        }
        gtlcore$lastPlaced = machine.getCoverAtSide(side);
        gtlcore$lastPlacedTick = level.getGameTime();
    }
}
