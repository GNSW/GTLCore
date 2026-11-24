package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.ExternalInventoryIdentity;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

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
        if (facade == null || !(handler instanceof InvWrapper wrapper)) {
            return;
        }
        Container container = wrapper.getInv();
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
}
