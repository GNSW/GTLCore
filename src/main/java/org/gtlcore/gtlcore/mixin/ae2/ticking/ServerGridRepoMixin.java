package org.gtlcore.gtlcore.mixin.ae2.ticking;

import appeng.me.Grid;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

@Mixin(targets = "appeng.hooks.ticking.ServerGridRepo", remap = false)
public abstract class ServerGridRepoMixin {

    @Shadow
    @Final
    private ObjectSet<Grid> networks;

    @Unique
    private List<Grid> gtlcore$networkSnapshot = List.of();

    // Resolve the snapshot when iteration starts, even if a caller retains this view.
    @Unique
    private final Iterable<Grid> gtlcore$networkView = this::gtlcore$networkIterator;

    @WrapOperation(method = "updateNetworks",
                   at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectSet;removeAll(Ljava/util/Collection;)Z"))
    private boolean gtlcore$skipEmptyRemovals(ObjectSet<Grid> networks, Collection<?> removals, Operation<Boolean> original) {
        boolean changed = !removals.isEmpty() && original.call(networks, removals);
        if (changed) {
            gtlcore$networkSnapshot = null;
        }
        return changed;
    }

    @WrapOperation(method = "updateNetworks",
                   at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectSet;addAll(Ljava/util/Collection;)Z"))
    private boolean gtlcore$skipEmptyAdditions(ObjectSet<Grid> networks, Collection<? extends Grid> additions, Operation<Boolean> original) {
        if (additions.isEmpty()) {
            return false;
        }
        boolean changed = original.call(networks, additions);
        // Fastutil can rehash before adding duplicates, changing iteration order even without new members.
        gtlcore$networkSnapshot = null;
        return changed;
    }

    @Inject(method = "getNetworks", at = @At("HEAD"), cancellable = true)
    private void gtlcore$getNetworkView(CallbackInfoReturnable<Iterable<Grid>> cir) {
        cir.setReturnValue(gtlcore$networkView);
    }

    @Unique
    private Iterator<Grid> gtlcore$networkIterator() {
        // As with the original set traversal, this runs on the server thread and does not apply pending changes.
        if (gtlcore$networkSnapshot == null) {
            gtlcore$networkSnapshot = List.copyOf(networks);
        }
        return gtlcore$networkSnapshot.iterator();
    }

    @Inject(method = "clear", at = @At("RETURN"))
    private void gtlcore$clearNetworkSnapshot(CallbackInfo ci) {
        gtlcore$networkSnapshot = List.of();
    }
}
