package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.ExternalInventoryIdentity;

import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

import appeng.api.stacks.AEKeyType;
import appeng.me.storage.ExternalStorageFacade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ExternalStorageFacade.class)
public abstract class ExternalStorageFacadeMixin {

    @Inject(method = "of(Lnet/minecraftforge/items/IItemHandler;)Lappeng/me/storage/ExternalStorageFacade;",
            at = @At("RETURN"),
            remap = false)
    private static void gtlcore$registerItemIdentity(IItemHandler handler,
                                                     CallbackInfoReturnable<ExternalStorageFacade> cir) {
        ExternalStorageFacade facade = cir.getReturnValue();
        if (facade == null) {
            return;
        }
        Container container;
        if (handler instanceof InvWrapper wrapper) {
            container = wrapper.getInv();
        } else if (handler instanceof SidedInvWrapper sided) {
            container = gtlcore$sidedContainer(sided);
        } else {
            return;
        }
        if (container == null) {
            return;
        }
        long identity;
        if (container instanceof CompoundContainer compound) {
            CompoundContainerAccessor accessor = (CompoundContainerAccessor) compound;
            long first = System.identityHashCode(accessor.gtlcore$container1());
            long second = System.identityHashCode(accessor.gtlcore$container2());
            identity = gtlcore$combine(Math.min(first, second), Math.max(first, second), facade.getKeyType());
        } else {
            identity = gtlcore$combine(System.identityHashCode(container), 0L, facade.getKeyType());
        }
        ExternalInventoryIdentity.put(facade, identity);
    }

    @Unique
    private static long gtlcore$combine(long first, long second, AEKeyType type) {
        long key = first * 31L + second;
        return key * 31L + type.getId().hashCode();
    }

    @Unique
    private static Container gtlcore$sidedContainer(SidedInvWrapper sided) {
        try {
            var invField = SidedInvWrapper.class.getDeclaredField("inv");
            invField.setAccessible(true);
            WorldlyContainer inv = (WorldlyContainer) invField.get(sided);
            var sideField = SidedInvWrapper.class.getDeclaredField("side");
            sideField.setAccessible(true);
            Direction side = (Direction) sideField.get(sided);
            if (!(inv instanceof ShulkerBoxBlockEntity)) {
                return null;
            }
            if (!gtlcore$coversAllSlots(inv.getSlotsForFace(side), inv.getContainerSize())) {
                return null;
            }
            return inv;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Unique
    private static boolean gtlcore$coversAllSlots(int[] slots, int size) {
        if (slots.length != size) {
            return false;
        }
        boolean[] seen = new boolean[size];
        for (int slot : slots) {
            if (slot < 0 || slot >= size || seen[slot]) {
                return false;
            }
            seen[slot] = true;
        }
        return true;
    }
}
