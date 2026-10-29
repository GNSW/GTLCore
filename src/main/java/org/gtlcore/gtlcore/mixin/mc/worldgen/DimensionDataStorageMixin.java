package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Protect metadata publication used by native features such as AE meteorites. */
@Mixin(DimensionDataStorage.class)
public abstract class DimensionDataStorageMixin {

    @WrapMethod(method = "computeIfAbsent")
    private <T extends SavedData> T gtlcore$publishOnce(Function<CompoundTag, T> reader, Supplier<T> factory,
                                                        String name, Operation<T> original) {
        synchronized (this) {
            return original.call(reader, factory, name);
        }
    }

    @WrapMethod(method = "get")
    private <T extends SavedData> T gtlcore$readPublished(Function<CompoundTag, T> reader, String name,
                                                          Operation<T> original) {
        synchronized (this) {
            return original.call(reader, name);
        }
    }

    @WrapMethod(method = "set")
    private void gtlcore$writePublished(String name, SavedData value, Operation<Void> original) {
        synchronized (this) {
            original.call(name, value);
        }
    }

    @WrapOperation(method = "save",
                   at = @At(value = "INVOKE",
                            target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private void gtlcore$savePublishedSnapshot(Map<String, SavedData> cache,
                                               BiConsumer<String, SavedData> writer, Operation<Void> original) {
        Map<String, SavedData> snapshot;
        synchronized (this) {
            // Preserve the current map's iteration order, but release its publication lock before
            // invoking mod serialization callbacks or doing disk I/O. New entries stay dirty.
            snapshot = new LinkedHashMap<>(cache);
        }
        original.call(snapshot, writer);
    }
}
