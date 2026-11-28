package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StorageGraphLifecycle {

    private StorageGraphLifecycle() {}

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        StorageGraphManager.reset();
        ExternalIdentityKeyRegistry.clear();
    }
}
