package com.wcwen.gtmbsockets.client;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Client-only registration of the config screen factory.  The factory is what makes the "Config"
 * button appear in Forge's mod list for this mod.
 *
 * <p>Implements {@link DistExecutor.SafeRunnable} so the mod constructor can use
 * {@code DistExecutor.safeRunWhenOn(Dist.CLIENT, ...)} without loading client GUI classes on a
 * dedicated server.
 */
public final class ClientConfigScreen implements DistExecutor.SafeRunnable {

    public ClientConfigScreen() {
    }

    @Override
    public void run() {
        register();
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new LimitConfigScreen(parent)));
    }
}
