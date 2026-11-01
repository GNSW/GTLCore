package org.gtlcore.gtlcore.integration.terminal.host;

import org.gtlcore.gtlcore.common.item.UltimateTerminalBehavior;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.List;

public final class HostBuildSettings {

    private HostBuildSettings() {}

    public static final class Ultimate extends UltimateTerminalBehavior.AutoBuildSetting {

        private boolean hostNoHatch;

        public void setHostNoHatch(boolean hostNoHatch) {
            this.hostNoHatch = hostNoHatch;
        }

        @Override
        public boolean isPlaceHatch(BlockInfo[] blockInfos) {
            return !hostNoHatch || !HatchDetector.containsHatch(blockInfos);
        }

        @Override
        public List<ItemStack> apply(BlockInfo[] blockInfos) {
            return hostNoHatch ? super.apply(filterHatches(blockInfos)) : super.apply(blockInfos);
        }
    }

    private static BlockInfo[] filterHatches(BlockInfo[] blockInfos) {
        if (blockInfos == null) {
            return new BlockInfo[0];
        }
        return Arrays.stream(blockInfos)
                .filter(info -> info != null && !HatchDetector.isHatch(info.getBlockState().getBlock()))
                .toArray(BlockInfo[]::new);
    }
}
