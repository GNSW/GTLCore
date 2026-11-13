package org.gtlcore.gtlcore.integration.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class MachineMonitorBlockEntity extends BlockEntity {

    private BlockPos master;
    private CompoundTag target = new CompoundTag();
    private String structureStatus = "incomplete";

    public MachineMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(MachineMonitorContent.ENTITY.get(), pos, state);
    }

    public BlockPos master() {
        return master;
    }

    public void assign(BlockPos pos, String status) {
        // Only the elected owner keeps a binding; stale migrated targets must not reappear after unlinking.
        boolean clearTarget = status.equals("formed") && !worldPosition.equals(pos) && !target.isEmpty();
        if (!java.util.Objects.equals(master, pos) || !structureStatus.equals(status) || clearTarget) {
            master = pos;
            structureStatus = status;
            if (clearTarget) target = new CompoundTag();
            setChanged();
        }
    }

    public Component structureMessage() {
        return Component.translatable("monitor.gtlcore.machine.structure." + structureStatus);
    }

    public CompoundTag target() {
        return target;
    }

    public void bind(CompoundTag binding) {
        target = binding.copy();
        setChanged();
        MachineMonitorRuntime.get((ServerLevel) level).refresh(worldPosition);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) MachineMonitorRuntime.get(serverLevel).changed(worldPosition);
    }

    @Override
    public void onChunkUnloaded() {
        if (level instanceof ServerLevel serverLevel) MachineMonitorRuntime.get(serverLevel).unloaded(worldPosition, master());
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (master != null) tag.putLong("Master", master.asLong());
        tag.put("Target", target);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        master = tag.contains("Master") ? BlockPos.of(tag.getLong("Master")) : null;
        target = tag.getCompound("Target");
    }
}
