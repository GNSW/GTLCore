package org.gtlcore.gtlcore.integration.terminal.host;

import org.gtlcore.gtlcore.common.item.UltimateTerminalBehavior;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.Locale;

public final class PatternPreservingDismantleSetting extends UltimateTerminalBehavior.AutoBuildSetting {

    public PatternPreservingDismantleSetting() {
        setDismantleMode(true);
        setNoHatchMode(false);
        setRepeatCount(0);
        setReplaceMode(false);
        setFlipped(false);
        setStrictAeOutput(true);
    }

    @Override
    public boolean shouldPreserveDuringDismantle(Block block) {
        return isPatternAssembly(block) || super.shouldPreserveDuringDismantle(block);
    }

    public static boolean isPatternAssembly(Block block) {
        if (block == null) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath().toLowerCase(Locale.ROOT);
        if (path.contains("pattern")) {
            return true;
        }
        String localized = block.getName().getString().toLowerCase(Locale.ROOT);
        return localized.contains("pattern") || localized.contains("样板");
    }
}
