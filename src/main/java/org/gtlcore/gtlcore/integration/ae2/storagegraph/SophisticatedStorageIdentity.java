package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.transfer.fluid.InfiniteFluidTransferProxy;
import com.gregtechceu.gtceu.api.transfer.item.InfiniteItemTransferProxy;
import com.gregtechceu.gtceu.common.machine.storage.CreativeChestMachine;
import com.gregtechceu.gtceu.common.machine.storage.CreativeTankMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.block.ChestBlock;
import net.p3pp3rf1y.sophisticatedstorage.block.ChestBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;

import appeng.me.storage.ExternalStorageFacade;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

/** Registers the block behind a storage bus as the physical identity of a Sophisticated Storage container. */
public final class SophisticatedStorageIdentity {

    private SophisticatedStorageIdentity() {}

    public static void register(ServerLevel level, BlockPos pos, Object handler, ExternalStorageFacade facade) {
        if (level == null || pos == null || facade == null || handler == null) {
            return;
        }
        if (!(handler instanceof IItemHandler) && !(handler instanceof IFluidHandler)) {
            return;
        }
        Object blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof StorageBlockEntity) && !(blockEntity instanceof BackpackBlockEntity) &&
                !(blockEntity instanceof MetaMachineBlockEntity)) {
            return;
        }
        long identity = gtlcore$chestIdentity(level, pos, blockEntity);
        if (identity == 0L) {
            UUID contents = gtlcore$contentsUuid(blockEntity);
            identity = contents == null ? pos.asLong() : contents.getMostSignificantBits() * 31L + contents.getLeastSignificantBits();
        }
        boolean infinite = handler instanceof InfiniteItemTransferProxy || handler instanceof InfiniteFluidTransferProxy;
        if (blockEntity instanceof MetaMachineBlockEntity machineBlockEntity) {
            var machine = machineBlockEntity.getMetaMachine();
            infinite = infinite || machine instanceof CreativeChestMachine || machine instanceof CreativeTankMachine;
        }
        long key = identity * 31L * 31L + facade.getKeyType().getId().hashCode();
        if (infinite) {
            ExternalInventoryIdentity.putInfinite(facade, key);
        } else {
            ExternalInventoryIdentity.put(facade, key);
        }
    }

    private static long gtlcore$chestIdentity(ServerLevel level, BlockPos pos, Object blockEntity) {
        if (!(blockEntity instanceof ChestBlockEntity)) {
            return 0L;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return 0L;
        }
        Direction connected = ChestBlock.getConnectedDirection(state);
        BlockPos other = pos.relative(connected);
        long first = pos.asLong();
        long second = other.asLong();
        return Math.min(first, second) * 31L + Math.max(first, second);
    }

    private static UUID gtlcore$contentsUuid(Object blockEntity) {
        IStorageWrapper wrapper = null;
        if (blockEntity instanceof StorageBlockEntity storage) {
            wrapper = storage.getStorageWrapper();
        } else if (blockEntity instanceof BackpackBlockEntity backpack) {
            wrapper = gtlcore$backpackWrapper(backpack);
        }
        if (wrapper == null) {
            return null;
        }
        Optional<UUID> uuid = wrapper.getContentsUuid();
        return uuid == null ? null : uuid.orElse(null);
    }

    private static IStorageWrapper gtlcore$backpackWrapper(BackpackBlockEntity backpack) {
        Class<?> type = backpack.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("backpackWrapper");
                field.setAccessible(true);
                Object value = field.get(backpack);
                return value instanceof IStorageWrapper wrapper ? wrapper : null;
            } catch (ReflectiveOperationException e) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}
