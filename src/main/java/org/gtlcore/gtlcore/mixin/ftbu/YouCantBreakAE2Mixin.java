package org.gtlcore.gtlcore.mixin.ftbu;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.utils.Registries;
import org.gtlcore.gtlcore.utils.TextUtil;

import dev.ftb.mods.ftbultimine.FTBUltiminePlayerData;
import dev.ftb.mods.ftbultimine.shape.BlockMatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * @author EasterFG on 2024/10/10
 */
@Mixin(FTBUltiminePlayerData.class)
public abstract class YouCantBreakAE2Mixin {

    @ModifyVariable(method = "updateBlocks", at = @At(value = "STORE", ordinal = 2), remap = false, name = "matcher")
    public BlockMatcher modifyBlockMatcher(BlockMatcher matcher) {
        if (ConfigHolder.INSTANCE.interaction.blackBlockList == null || ConfigHolder.INSTANCE.interaction.blackBlockList.length < 1) {
            return matcher;
        }
        return (original, state) -> {
            boolean flag = !TextUtil.containsWithWildcard(ConfigHolder.INSTANCE.interaction.blackBlockList, Registries.getBlockId(state.getBlock()));
            return flag && state.getBlock() == original.getBlock();
        };
    }
}
