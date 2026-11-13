package org.gtlcore.gtlcore.client;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.event.SourceTooltipRegistrationEvent;
import org.gtlcore.gtlcore.client.compat.aef.AefFavoriteKeyCompat;
import org.gtlcore.gtlcore.client.socketpreview.SocketPreview;
import org.gtlcore.gtlcore.common.data.source_tooltip.SourceTooltip;
import org.gtlcore.gtlcore.common.data.source_tooltip.register.*;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class ModClientEventListener {

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        NoClipClient.registerKeyMapping(event);
        AefFavoriteKeyCompat.registerKeyMapping(event);
        SocketPreview.registerKeyMapping(event);
    }

    @SubscribeEvent
    public static void onSourceTooltipRegistration(SourceTooltipRegistrationEvent event) {
        var time = System.currentTimeMillis();
        SourceTooltipRegister$FragmentWorldCollection.register(event);
        SourceTooltipRegister$SpaceElevator.register(event);
        SourceTooltipRegister$EarlyStage.register(event);
        SourceTooltipRegister$OreProcessing.register(event);
        SourceTooltipRegister$AlloySmelter.register(event);
        SourceTooltipRegister$VacuumFreezer.register(event);
        SourceTooltipRegister$SuperParticleCollider.register(event);
        SourceTooltipRegister$FusionReactor.register(event);
        SourceTooltipRegister$ElementCopying.register(event);
        SourceTooltipRegister$CosmosSimulation.register(event);
        SourceTooltip.register_time = System.currentTimeMillis() - time;
    }
}
