package org.gtlcore.gtlcore.utils;

import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.common.block.LampBlock;
import com.gregtechceu.gtceu.common.data.GTBlocks;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class LampPatternHelper {

    private LampPatternHelper() {}

    public static LampBlock lamp(DyeColor color, boolean borderless) {
        return (borderless ? GTBlocks.BORDERLESS_LAMPS : GTBlocks.LAMPS).get(color).get();
    }

    public static BlockState state(DyeColor color, boolean borderless,
                                   boolean lit, boolean inverted, boolean bloom) {
        return lamp(color, borderless).defaultBlockState()
                .setValue(LampBlock.LIGHT, lit)
                .setValue(LampBlock.INVERTED, inverted)
                .setValue(LampBlock.BLOOM, bloom);
    }

    public static ItemStack stack(DyeColor color, boolean borderless,
                                  boolean lit, boolean inverted, boolean bloom) {
        ItemStack stack = new ItemStack(lamp(color, borderless));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(LampBlock.TAG_LIT, lit);
        tag.putBoolean(LampBlock.TAG_INVERTED, inverted);
        tag.putBoolean(LampBlock.TAG_BLOOM, bloom);
        stack.setTag(tag);
        return stack;
    }

    public static BlockInfo info(DyeColor color, boolean borderless,
                                 boolean lit, boolean inverted, boolean bloom) {
        return new BlockInfo(
                state(color, borderless, lit, inverted, bloom),
                false,
                stack(color, borderless, lit, inverted, bloom),
                null);
    }

    public static BlockInfo allTooltips(DyeColor color, boolean borderless) {
        return info(color, borderless, false, true, false);
    }

    public static TraceabilityPredicate predicate(DyeColor color, boolean borderless,
                                                  boolean lit, boolean inverted, boolean bloom) {
        BlockInfo info = info(color, borderless, lit, inverted, bloom);
        return Predicates.custom(
                state -> state.getBlockState() == info.getBlockState(),
                () -> new BlockInfo[] { info });
    }

    public static TraceabilityPredicate allTooltipsPredicate(DyeColor color, boolean borderless) {
        return predicate(color, borderless, false, true, false);
    }
}
