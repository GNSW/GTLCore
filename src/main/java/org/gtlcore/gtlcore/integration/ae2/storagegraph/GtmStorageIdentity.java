package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.common.machine.storage.BufferMachine;
import com.gregtechceu.gtceu.common.machine.storage.CrateMachine;
import com.gregtechceu.gtceu.common.machine.storage.CreativeChestMachine;
import com.gregtechceu.gtceu.common.machine.storage.CreativeTankMachine;
import com.gregtechceu.gtceu.common.machine.storage.DrumMachine;
import com.gregtechceu.gtceu.common.machine.storage.QuantumChestMachine;
import com.gregtechceu.gtceu.common.machine.storage.QuantumTankMachine;

import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import appeng.api.stacks.AEKeyType;
import appeng.me.storage.ExternalStorageFacade;

import java.util.ArrayList;
import java.util.List;

public final class GtmStorageIdentity {

    private GtmStorageIdentity() {}

    public static boolean register(ServerLevel level, BlockPos pos, Direction side, Object handler,
                                   ExternalStorageFacade facade) {
        if (level == null || pos == null || handler == null || facade == null) {
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof MetaMachineBlockEntity blockEntity)) {
            return false;
        }
        MetaMachine machine = blockEntity.getMetaMachine();
        if (machine == null) {
            return false;
        }
        AEKeyType keyType = facade.getKeyType();
        boolean infinite = machine instanceof CreativeChestMachine || machine instanceof CreativeTankMachine;
        boolean dedicated = machine instanceof QuantumChestMachine || machine instanceof QuantumTankMachine ||
                machine instanceof CrateMachine || machine instanceof DrumMachine || machine instanceof BufferMachine;
        long ownerId = ExternalIdentityKeyRegistry.intern(machine);
        if (ownerId == 0L) {
            return false;
        }
        if (machine.getCoverContainer().getCoverAtSide(side) != null) {
            return false;
        }
        long identity;
        if (dedicated) {
            identity = ExternalIdentityKeyRegistry.internView(ownerId, keyType, List.of());
        } else {
            identity = gtlcore$fullTraitViewIdentity(machine, side, keyType, ownerId);
        }
        if (identity == 0L) {
            return false;
        }
        long key = identity * 31L * 31L + keyType.getId().hashCode();
        if (infinite) {
            ExternalInventoryIdentity.putInfinite(facade, key);
        } else {
            ExternalInventoryIdentity.put(facade, key);
        }
        return true;
    }

    private static long gtlcore$fullTraitViewIdentity(MetaMachine machine, Direction side, AEKeyType keyType,
                                                      long ownerId) {
        Class<?> traitClass;
        if (keyType == AEKeyType.items()) {
            traitClass = IItemTransfer.class;
        } else if (keyType == AEKeyType.fluids()) {
            traitClass = IFluidTransfer.class;
        } else {
            return 0L;
        }
        List<MachineTrait> all = new ArrayList<>();
        for (MachineTrait trait : machine.getTraits()) {
            if (traitClass.isInstance(trait)) {
                all.add(trait);
            }
        }
        if (all.isEmpty()) {
            return 0L;
        }
        for (MachineTrait trait : all) {
            if (!trait.hasCapability(side)) {
                return 0L;
            }
        }
        return ExternalIdentityKeyRegistry.internView(ownerId, keyType, all);
    }
}
