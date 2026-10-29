package org.gtlcore.voxelregion.verification;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.gametest.framework.TestCommand;

/** Dedicated verification artifact only; never included in the GTLCore distribution. */
@Mod("gtlcore_vre_verification")
public final class CoreVreVerificationMod {
    public CoreVreVerificationMod() {
        MinecraftForge.EVENT_BUS.addListener(CoreVreVerificationMod::commands);
        MinecraftForge.EVENT_BUS.addListener(CoreVreVerificationMod::tick);
    }

    @SuppressWarnings("deprecation")
    private static void commands(RegisterCommandsEvent event) {
        // Forge's automatic discovery/ticker is development-only. This separate
        // probe drives the unchanged native test framework in a standard server.
        if (GameTestRegistry.findTestFunction("hostbatchpreservesprotectionandnotifiesonlychangedwatchedpositions").isEmpty())
            GameTestRegistry.register(GtlRegionIntegrationGameTests.class);
        if (GameTestRegistry.findTestFunction("cachedindicatorsusetheircallingregion").isEmpty())
            GameTestRegistry.register(SurfaceIndicatorPlacementGameTests.class);
        if (GameTestRegistry.findTestFunction("preservesnativeoregrouporder").isEmpty())
            GameTestRegistry.register(OrderedOreGroupingGameTests.class);
        TestCommand.register(event.getDispatcher());
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !ForgeGameTestHooks.isGametestEnabled())
            GameTestTicker.SINGLETON.tick();
    }
}
