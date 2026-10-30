// SPDX-License-Identifier: LGPL-3.0-only
package ca.spottedleaf.starlight.common.config;

/** Bootstrap-only settings; switching providers after mixins load is not supported. */
public final class GlobalLightConfig {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("vre.globalLighting", "true"));
    private static final int PARALLELISM = Math.max(1, Math.min(64,
            Integer.getInteger("vre.globalLighting.threads", Math.max(1,
                    Math.min(8, Runtime.getRuntime().availableProcessors() - 1)))));
    public static boolean enabled() { return ENABLED; }
    public static int parallelism() { return PARALLELISM; }
    public static int maxOutstanding() { return Math.max(PARALLELISM, 4096); }
    private GlobalLightConfig() { }
}
