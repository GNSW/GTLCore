package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.ExternalInventoryIdentity;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceRegistry;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageOperationContext;

import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import appeng.me.storage.ExternalStorageFacade;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
        if (container instanceof CompoundContainer) {
            return;
        }
        long identity = ExternalIdentityKeyRegistry.internPair(container, facade.getKeyType(), facade.getKeyType());
        if (identity != 0L) {
            ExternalInventoryIdentity.put(facade, identity);
        }
    }

    @WrapMethod(method = "extract", remap = false)
    private long gtlcore$simulateExtract(AEKey what, long amount, Actionable mode, IActionSource source,
                                         Operation<Long> original) {
        long canonical = ExternalInventoryIdentity.get((MEStorage) (Object) this);
        StorageOperationContext context = StorageOperationContext.current();
        if (mode != Actionable.SIMULATE || canonical == 0L || context == null) {
            return original.call(what, amount, mode, source);
        }
        long ledger = SourceRegistry.idFor((MEStorage) (Object) this, canonical).runtimeId();
        long used = context.usedCanonicalExtract(ledger, what);
        long probe = amount > Long.MAX_VALUE - used ? Long.MAX_VALUE : amount + used;
        long available = original.call(what, probe, mode, source);
        long got = available <= used ? 0L : Math.min(amount, available - used);
        if (got > 0L) {
            context.recordUsedCanonicalExtract(ledger, what, got);
        }
        return got;
    }

    @WrapMethod(method = "insert", remap = false)
    private long gtlcore$simulateInsert(AEKey what, long amount, Actionable mode, IActionSource source,
                                        Operation<Long> original) {
        long canonical = ExternalInventoryIdentity.get((MEStorage) (Object) this);
        StorageOperationContext context = StorageOperationContext.current();
        if (mode != Actionable.SIMULATE || canonical == 0L || context == null) {
            return original.call(what, amount, mode, source);
        }
        long ledger = SourceRegistry.idFor((MEStorage) (Object) this, canonical).runtimeId();
        long used = context.usedCanonicalInsert(ledger, what);
        long probe = amount > Long.MAX_VALUE - used ? Long.MAX_VALUE : amount + used;
        long available = original.call(what, probe, mode, source);
        long got = available <= used ? 0L : Math.min(amount, available - used);
        if (got > 0L) {
            context.recordUsedCanonicalInsert(ledger, what, got);
        }
        return got;
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
