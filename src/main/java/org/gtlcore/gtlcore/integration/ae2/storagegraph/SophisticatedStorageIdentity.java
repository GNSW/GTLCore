package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.block.ChestBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;

import appeng.me.storage.ExternalStorageFacade;

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
                    ExternalIdentityKeyRegistry.internValue(contents);
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
        ChestBlockEntity main = chest.getMainChestBlockEntity();
        return main == null ? 0L : ExternalIdentityKeyRegistry.intern(main);
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
