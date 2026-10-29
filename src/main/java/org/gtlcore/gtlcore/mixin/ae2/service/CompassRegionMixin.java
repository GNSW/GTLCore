package org.gtlcore.gtlcore.mixin.ae2.service;

import net.minecraft.nbt.CompoundTag;

import appeng.core.worlddata.AESavedData;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import java.io.File;

/** A compass region covers 1024 squared chunks, far beyond a generation task's terrain lease. */
@Mixin(targets = "appeng.server.services.compass.CompassRegion")
public abstract class CompassRegionMixin extends AESavedData {

    @WrapMethod(method = "hasSkyStone(II)Z", remap = false)
    private boolean gtlcore$readColumns(int x, int z, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(x, z);
        }
    }

    @WrapMethod(method = "hasSkyStone(III)Z", remap = false)
    private boolean gtlcore$readSection(int x, int z, int section, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(x, z, section);
        }
    }

    @WrapMethod(method = "setHasSkyStone", remap = false)
    private void gtlcore$writeSection(int x, int z, int section, boolean present, Operation<Void> original) {
        synchronized (this) {
            original.call(x, z, section, present);
        }
    }

    @WrapMethod(method = "save(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")
    private CompoundTag gtlcore$snapshotSections(CompoundTag tag, Operation<CompoundTag> original) {
        synchronized (this) {
            return original.call(tag);
        }
    }

    @Override
    public synchronized void save(File file) {
        // Lock the complete AE override, including its final dirty reset, not only save(NBT).
        // A concurrent meteorite update after serialization must survive for the next save.
        super.save(file);
    }
}
