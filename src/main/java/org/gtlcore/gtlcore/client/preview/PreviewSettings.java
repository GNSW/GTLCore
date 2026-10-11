package org.gtlcore.gtlcore.client.preview;

import org.gtlcore.gtlcore.config.ConfigHolder;

/** Client-local options; never synchronized from a server. */
public final class PreviewSettings {

    private static final ConfigHolder.PreviewOptions DEFAULTS = new ConfigHolder.PreviewOptions();

    private PreviewSettings() {}

    private static ConfigHolder.PreviewOptions options() {
        return ConfigHolder.INSTANCE == null ? DEFAULTS : ConfigHolder.INSTANCE.graphics.multiblockPreview;
    }

    public static boolean enabled() {
        return options().enabled;
    }

    public static int minPositions() {
        return options().minPositions;
    }

    public static int workers() {
        return options().workers;
    }

    public static int frameBudgetMs() {
        return options().frameBudgetMs;
    }

    public static int cacheMb() {
        return options().cacheMb;
    }
}
