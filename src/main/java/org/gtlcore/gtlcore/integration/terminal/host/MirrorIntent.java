package org.gtlcore.gtlcore.integration.terminal.host;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

public final class MirrorIntent {

    private static final String DATA_NAME = "gtlcore_terminal_host_mirror_intents";
    private static final String MIRRORED_POSITIONS_TAG = "MirroredPositions";

    private MirrorIntent() {}

    public static void remember(Level level, BlockPos controllerPos, boolean mirrored) {
        Data data = getData(level);
        if (data == null) {
            return;
        }
        GlobalPos pos = GlobalPos.of(level.dimension(), controllerPos.immutable());
        if (mirrored) {
            data.mirroredPositions.add(pos);
        } else {
            data.mirroredPositions.remove(pos);
        }
        data.setDirty();
    }

    public static void clear(Level level, BlockPos controllerPos) {
        Data data = getData(level);
        if (data != null && data.mirroredPositions.remove(
                GlobalPos.of(level.dimension(), controllerPos.immutable()))) {
            data.setDirty();
        }
    }

    public static boolean resolve(Level level, BlockPos controllerPos) {
        if (!(MetaMachine.getMachine(level, controllerPos) instanceof IMultiController controller)) {
            return false;
        }
        if (controller.isFormed()) {
            var state = controller.getMultiblockState();
            boolean mirrored = state != null && state.isNeededFlip();
            clear(level, controllerPos);
            return mirrored;
        }
        Data data = getData(level);
        return data != null && data.mirroredPositions.contains(
                GlobalPos.of(level.dimension(), controllerPos.immutable()));
    }

    private static Data getData(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return serverLevel.getServer().overworld().getDataStorage().computeIfAbsent(
                Data::load, Data::new, DATA_NAME);
    }

    private static final class Data extends SavedData {

        private final Set<GlobalPos> mirroredPositions = new HashSet<>();

        private Data() {}

        private Data(Set<GlobalPos> mirroredPositions) {
            this.mirroredPositions.addAll(mirroredPositions);
        }

        private static Data load(CompoundTag tag) {
            Set<GlobalPos> positions = new HashSet<>();
            ListTag entries = tag.getList(MIRRORED_POSITIONS_TAG, Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString("dim"));
                if (dimensionId == null) {
                    continue;
                }
                var dimension = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dimensionId);
                positions.add(GlobalPos.of(dimension, new BlockPos(
                        entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"))));
            }
            return new Data(positions);
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag entries = new ListTag();
            for (GlobalPos pos : mirroredPositions) {
                CompoundTag entry = new CompoundTag();
                entry.putString("dim", pos.dimension().location().toString());
                entry.putInt("X", pos.pos().getX());
                entry.putInt("Y", pos.pos().getY());
                entry.putInt("Z", pos.pos().getZ());
                entries.add(entry);
            }
            tag.put(MIRRORED_POSITIONS_TAG, entries);
            return tag;
        }
    }
}
