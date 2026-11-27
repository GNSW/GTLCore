package org.gtlcore.gtlcore.integration.ae2.storagegraph;

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
        if (!(blockEntity instanceof StorageBlockEntity) && !(blockEntity instanceof BackpackBlockEntity)) {
            return;
        }
        long identity = gtlcore$chestIdentity(level, pos, blockEntity);
        if (identity == 0L) {
            UUID contents = gtlcore$contentsUuid(blockEntity);
            identity = contents == null ? ExternalIdentityKeyRegistry.intern(blockEntity) :
                    contents.getMostSignificantBits() * 31L + contents.getLeastSignificantBits();
        }
        if (identity == 0L) {
            return;
        }
        long key = identity * 31L * 31L + facade.getKeyType().getId().hashCode();
        ExternalInventoryIdentity.put(facade, key);
    }

    private static long gtlcore$chestIdentity(ServerLevel level, BlockPos pos, Object blockEntity) {
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            return 0L;
        }
        BlockPos mainPos = gtlcore$doubleMainPos(chest);
        if (mainPos != null) {
            Object main = level.getBlockEntity(mainPos);
            if (main instanceof ChestBlockEntity) {
                return ExternalIdentityKeyRegistry.intern(main);
            }
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

    private static BlockPos gtlcore$doubleMainPos(ChestBlockEntity chest) {
        Class<?> type = chest.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("doubleMainPos");
                field.setAccessible(true);
                return field.get(chest) instanceof BlockPos mainPos ? mainPos : null;
            } catch (ReflectiveOperationException e) {
                type = type.getSuperclass();
            }
        }
        return null;
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
        try {
            return backpack.getBackpackWrapper();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
