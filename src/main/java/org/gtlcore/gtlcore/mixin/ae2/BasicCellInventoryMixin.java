package org.gtlcore.gtlcore.mixin.ae2;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.InfinityWaterCellData;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.api.storage.cells.ISaveProvider;
import appeng.me.cells.BasicCellInventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BasicCellInventory.class)
public abstract class BasicCellInventoryMixin {

    @Shadow(remap = false)
    private int maxItemTypes;

    @Shadow(remap = false)
    @Final
    private IBasicCellItem cellType;

    @Shadow(remap = false)
    @Final
    private ItemStack i;

    @Unique
    private boolean gtlcore$mergedWaterCells;

    @Shadow(remap = false)
    protected abstract void saveChanges();

    @WrapOperation(method = "loadCellItems",
                   remap = false,
                   at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/Object2LongMap;put(Ljava/lang/Object;J)J"))
    private long gtlcore$mergeWaterCells(Object2LongMap<AEKey> amounts, Object key, long amount, Operation<Long> original) {
        if (key instanceof AEItemKey itemKey && InfinityWaterCellData.isCell(itemKey.getItem()) && amounts.containsKey(itemKey)) {
            gtlcore$mergedWaterCells = true;
            amount = Math.addExact(amounts.getLong(itemKey), amount);
        }
        return original.call(amounts, key, amount);
    }

    @Inject(method = "loadCellItems", at = @At("RETURN"), remap = false)
    private void gtlcore$saveMergedWaterCells(CallbackInfo ci) {
        if (gtlcore$mergedWaterCells) {
            gtlcore$mergedWaterCells = false;
            saveChanges();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void BasicCellInventory(IBasicCellItem cellType, ItemStack o, ISaveProvider container, CallbackInfo ci) {
        this.maxItemTypes = this.cellType.getTotalTypes(this.i) * ConfigHolder.INSTANCE.machines.cellType;
    }

    @Inject(method = "getBytesPerType", at = @At("HEAD"), remap = false, cancellable = true)
    public void getBytesPerType(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(1);
    }
}
