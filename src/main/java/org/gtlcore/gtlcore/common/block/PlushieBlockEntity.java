package org.gtlcore.gtlcore.common.block;

import org.gtlcore.gtlcore.common.data.GTLPlushies;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Stores the wearable's appearance and the synchronized music playback state. */
public final class PlushieBlockEntity extends BlockEntity {

    private static final Map<Level, Set<BlockPos>> LOADED = new WeakHashMap<>();
    private ItemStack plushie = ItemStack.EMPTY;
    private long startTick = -1;

    public PlushieBlockEntity(BlockPos pos, BlockState state) {
        super(GTLPlushies.BLOCK_ENTITY.get(), pos, state);
    }

    public ItemStack getPlushie() {
        return matchingStack(plushie);
    }

    public int getColor() {
        ItemStack stack = getPlushie();
        return stack.getItem() instanceof DyeableLeatherItem dyeable ? dyeable.getColor(stack) : 0xFFFFFF;
    }

    public boolean isPlaying() {
        return startTick >= 0 && level != null && !isRemoved() && level.getGameTime() >= startTick;
    }

    public long getStartTick() {
        return startTick;
    }

    public long getElapsedTicks(long gameTime) {
        return startTick < 0 ? 0 : Math.max(0, gameTime - startTick);
    }

    public void togglePlaying() {
        if (level == null || level.isClientSide || isRemoved()) return;
        startTick = isPlaying() ? -1 : level.getGameTime();
        sync();
    }

    public void setPlushie(ItemStack stack) {
        plushie = matchingStack(stack);
        sync();
    }

    private ItemStack matchingStack(ItemStack stack) {
        // Never trust a saved item ID to determine what this block can drop.
        if (stack.isEmpty() || !stack.is(getBlockState().getBlock().asItem())) {
            return new ItemStack(getBlockState().getBlock());
        }
        return stack.copyWithCount(1);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Plushie", getPlushie().save(new CompoundTag()));
        tag.putLong("StartTick", startTick);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        plushie = matchingStack(ItemStack.of(tag.getCompound("Plushie")));
        startTick = tag.contains("StartTick") ? tag.getLong("StartTick") : -1;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) handleUpdateTag(tag);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
        if (level != null && level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        registerLoaded();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        registerLoaded();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        unregisterLoaded();
    }

    @Override
    public void onChunkUnloaded() {
        unregisterLoaded();
        super.onChunkUnloaded();
    }

    private void sync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private void registerLoaded() {
        if (level == null || isRemoved()) return;
        synchronized (LOADED) {
            LOADED.computeIfAbsent(level, ignored -> new HashSet<>()).add(worldPosition.immutable());
        }
    }

    private void unregisterLoaded() {
        if (level == null) return;
        synchronized (LOADED) {
            Set<BlockPos> positions = LOADED.get(level);
            if (positions == null) return;
            positions.remove(worldPosition);
            if (positions.isEmpty()) LOADED.remove(level);
        }
    }

    public static List<PlushieBlockEntity> getActivePlushies(Level level) {
        List<BlockPos> positions;
        synchronized (LOADED) {
            positions = List.copyOf(LOADED.getOrDefault(level, Set.of()));
        }
        List<PlushieBlockEntity> active = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof PlushieBlockEntity plushie &&
                    plushie.isPlaying()) {
                active.add(plushie);
            }
        }
        return active;
    }
}
