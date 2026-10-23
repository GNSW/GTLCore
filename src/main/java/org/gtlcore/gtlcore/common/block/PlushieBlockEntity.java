package org.gtlcore.gtlcore.common.block;

import org.gtlcore.gtlcore.common.data.GTLPlushies;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Stores the wearable's appearance without giving the placed decoration an inventory. */
public final class PlushieBlockEntity extends BlockEntity {

    private ItemStack plushie = ItemStack.EMPTY;

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

    public void setPlushie(ItemStack stack) {
        plushie = matchingStack(stack);
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
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
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        plushie = matchingStack(ItemStack.of(tag.getCompound("Plushie")));
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
}
