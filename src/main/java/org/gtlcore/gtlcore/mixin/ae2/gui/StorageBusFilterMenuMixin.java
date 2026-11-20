package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.integration.ae2.storage.StorageBusFilterInventory;

import appeng.helpers.externalstorage.GenericStackInv;
import appeng.menu.implementations.StorageBusMenu;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.util.ConfigMenuInventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(UpgradeableMenu.class)
public abstract class StorageBusFilterMenuMixin {

    @WrapOperation(method = "addExpandableConfigSlots",
                   remap = false,
                   at = @At(value = "INVOKE", target = "Lappeng/helpers/externalstorage/GenericStackInv;createMenuWrapper()Lappeng/util/ConfigMenuInventory;"))
    private ConfigMenuInventory gtlcore$uniqueStorageBusFilters(GenericStackInv inventory,
                                                                Operation<ConfigMenuInventory> original) {
        if ((Object) this instanceof StorageBusMenu menu) {
            return new StorageBusFilterInventory(inventory, menu.isClientSide());
        }
        return original.call(inventory);
    }
}
