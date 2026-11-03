package org.gtlcore.gtlcore.mixin.gtm;

import com.gregtechceu.gtceu.common.item.ColorSprayBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ColorSprayBehaviour.class, remap = false)
public interface ColorSprayBehaviourAccessor {

    @Invoker(value = "tryPaintBlock", remap = false)
    boolean gtlcore$tryPaintBlock(Player player, Level level, BlockPos pos, Direction face);
}
