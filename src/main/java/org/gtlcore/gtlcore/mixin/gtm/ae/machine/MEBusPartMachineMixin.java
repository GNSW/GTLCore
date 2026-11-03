package org.gtlcore.gtlcore.mixin.gtm.ae.machine;

import org.gtlcore.gtlcore.api.machine.trait.MEPart.IModifiableSyncOffset;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.EnumSet;

@Implements(@Interface(
                       iface = IModifiableSyncOffset.class,
                       prefix = "gTLCore$"))
@Mixin(MEBusPartMachine.class)
public abstract class MEBusPartMachineMixin extends ItemBusPartMachine implements IGridConnectedMachine {

    @Unique
    @Persisted
    protected int gTLCore$syncOffset = 0;

    public MEBusPartMachineMixin(IMachineBlockEntity holder, int tier, IO io, Object... args) {
        super(holder, tier, io, args);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.getCompound("ForgeData").getBoolean("isAllFacing")) {
            getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
        }
    }

    @Override
    public boolean shouldSyncME() {
        return this.self().getOffsetTimer() % (gTLCore$syncOffset == 0 ? ME_UPDATE_INTERVAL : gTLCore$syncOffset) == 0L;
    }

    @Unique
    public int gTLCore$getOffset() {
        return gTLCore$syncOffset;
    }

    @Unique
    public void gTLCore$setOffset(int offset) {
        this.gTLCore$syncOffset = offset;
    }
}
