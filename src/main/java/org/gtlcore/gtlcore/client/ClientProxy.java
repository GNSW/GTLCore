package org.gtlcore.gtlcore.client;

import org.gtlcore.gtlcore.api.event.SourceTooltipRegistrationEvent;
import org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClient;
import org.gtlcore.gtlcore.client.bloom.BloomClient;
import org.gtlcore.gtlcore.client.renderer.machine.HarmonyAnimationRenderer;
import org.gtlcore.gtlcore.common.CommonProxy;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@OnlyIn(Dist.CLIENT)
public class ClientProxy extends CommonProxy {

    public ClientProxy() {
        super();
        init();
    }

    public static void init() {
        CraftingUnitModelProvider.initCraftingUnitModels();
        BloomClient.init();
        WirelessAeClient.register(FMLJavaModLoadingContext.get().getModEventBus());
        HarmonyAnimationRenderer.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    @Override
    protected void clientSetup(final FMLClientSetupEvent event) {
        super.clientSetup(event);
        event.enqueueWork(() -> {
            // Fire event to allow mods to register their own source tooltips
            FMLJavaModLoadingContext.get().getModEventBus().post(new SourceTooltipRegistrationEvent());
        });
    }
}
