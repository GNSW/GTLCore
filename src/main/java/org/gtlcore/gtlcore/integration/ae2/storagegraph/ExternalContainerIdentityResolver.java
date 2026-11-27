package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import appeng.me.storage.ExternalStorageFacade;

public final class ExternalContainerIdentityResolver {

    private ExternalContainerIdentityResolver() {}

    public static void resolve(ServerLevel level, BlockPos pos, Direction side, Object handler,
                               ExternalStorageFacade facade) {
        if (level == null || pos == null || handler == null || facade == null) {
            return;
        }
        try {
            if (GtmStorageIdentity.register(level, pos, side, handler, facade)) {
                return;
            }
        } catch (LinkageError ignored) {
            // GTCEu is a required dependency, but keep the optional-adapter isolation explicit.
        }
        try {
            SophisticatedStorageIdentity.register(level, pos, handler, facade);
        } catch (LinkageError ignored) {
            // Sophisticated Storage is optional; skip when absent.
        }
    }
}
