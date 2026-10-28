package org.gtlcore.gtlcore.integration.ae2.manipulator;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.api.crafting.IPatternDetails;
import appeng.api.parts.IPartHost;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class ManipulatorPatternSharing {

    private static final List<WeakReference<PatternProviderLogic>> LOADED = new ArrayList<>();

    private ManipulatorPatternSharing() {}

    public static PatternProviderLogicHost host(BlockEntity block, Direction side) {
        Object object = side == null ? block : block instanceof IPartHost parts ? parts.getPart(side) : null;
        return object instanceof PatternProviderLogicHost provider ? provider : null;
    }

    public static List<IPatternDetails> patterns(PatternProviderLogicHost target, SharedManipulatorPatterns binding) {
        var level = target.getBlockEntity().getLevel();
        BlockPos source = binding.gtlcore$manipulatorSource();
        if (source == null || level == null || !level.hasChunkAt(source)) return List.of();
        var provider = host(level.getBlockEntity(source), binding.gtlcore$manipulatorSourceSide());
        if (provider == null || provider == target || provider.getGrid() == null || provider.getGrid() != target.getGrid()) return List.of();
        if (!(provider instanceof appeng.api.networking.security.IActionHost sourceHost) || sourceHost.getActionableNode() == null || !sourceHost.getActionableNode().isActive()) return List.of();
        // Chained references are forbidden, so recursive provider lookups cannot form loops.
        if (((SharedManipulatorPatterns) provider.getLogic()).gtlcore$manipulatorSource() != null) return List.of();
        return provider.getLogic().getAvailablePatterns();
    }

    public static void track(PatternProviderLogic logic) {
        if (LOADED.stream().noneMatch(ref -> ref.get() == logic)) LOADED.add(new WeakReference<>(logic));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % SharedConstants.TICKS_PER_SECOND != 0) return;
        LOADED.removeIf(ref -> ref.get() == null);
        for (var ref : LOADED) {
            var logic = ref.get();
            if (logic != null && ((SharedManipulatorPatterns) logic).gtlcore$manipulatorSource() != null && logic.getGrid() != null) logic.updatePatterns();
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        LOADED.clear();
    }
}
