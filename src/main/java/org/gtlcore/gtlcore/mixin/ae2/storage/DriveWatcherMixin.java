package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceRegistry;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageOperationContext;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.DriveWatcher;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DriveWatcher.class)
public abstract class DriveWatcherMixin {

    @WrapMethod(method = "extract", remap = false)
    private long gtlcore$ledgerExtract(AEKey what, long amount, Actionable mode, IActionSource source,
                                       Operation<Long> original) {
        return gtlcore$ledger((MEStorage) (Object) this, what, amount, mode, source, original, false);
    }

    @WrapMethod(method = "insert", remap = false)
    private long gtlcore$ledgerInsert(AEKey what, long amount, Actionable mode, IActionSource source,
                                      Operation<Long> original) {
        return gtlcore$ledger((MEStorage) (Object) this, what, amount, mode, source, original, true);
    }

    private static long gtlcore$ledger(MEStorage storage, AEKey what, long amount, Actionable mode,
                                       IActionSource source, Operation<Long> original, boolean insert) {
        StorageOperationContext context = StorageOperationContext.current();
        if (mode != Actionable.SIMULATE || context == null) {
            return original.call(what, amount, mode, source);
        }
        long id = SourceRegistry.idFor(storage).runtimeId();
        long used = insert ? context.usedCanonicalInsert(id, what) : context.usedCanonicalExtract(id, what);
        long probe = amount > Long.MAX_VALUE - used ? Long.MAX_VALUE : amount + used;
        long available = original.call(what, probe, mode, source);
        long got = available <= used ? 0L : Math.min(amount, available - used);
        if (got > 0L) {
            if (insert) {
                context.recordUsedCanonicalInsert(id, what, got);
            } else {
                context.recordUsedCanonicalExtract(id, what, got);
            }
        }
        return got;
    }
}
