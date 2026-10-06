package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.config.BloomOptions;
import org.gtlcore.gtlcore.config.ConfigHolder;

/** Reads the existing GTLCore configuration; there is no second config or hotkey state. */
public final class BloomConfig {

    private static final BloomOptions DEFAULTS = new BloomOptions();

    private static BloomOptions options() {
        var holder = ConfigHolder.INSTANCE;
        return holder == null || holder.bloom == null ? DEFAULTS : holder.bloom;
    }

    public static BloomOptions.Mode mode() {
        var mode = options().bloomMode;
        return mode == null ? BloomOptions.Mode.OFF : mode;
    }

    public static boolean generatedMaterialsEnabled() {
        return mode().pbr();
    }

    public static double strength() {
        return finite(options().local.bloomStrength, 1.7, 0, 6);
    }

    public static double radius() {
        return finite(options().local.bloomRadius, 1, 0, 1);
    }

    public static double buildBudgetMs() {
        return finite(options().local.bloomBuildBudgetMs, 1.5, 0.1, 16);
    }

    public static double indicator() {
        return finite(options().pbr.indicatorEmission, 0.9, 0, 1);
    }

    public static double screen() {
        return finite(options().pbr.screenEmission, 1, 0, 1);
    }

    public static double core() {
        return finite(options().pbr.coreEmission, 1, 0, 1);
    }

    public static double defaultEmission() {
        return finite(options().pbr.defaultEmission, 1, 0, 1);
    }

    public static boolean surfaces() {
        return options().pbr.generateSurfaceMaterials;
    }

    public static boolean normals() {
        return options().pbr.generateSurfaceNormals;
    }

    public static double surfaceStrength() {
        return finite(options().pbr.surfaceVariation, 0.65, 0, 1);
    }

    public static double normalStrength() {
        return finite(options().pbr.surfaceNormalStrength, 0.6, 0, 1);
    }

    public record Materials(double indicator, double screen, double core, double defaultEmission,
                            boolean surfaces, boolean normals, double variation, double normalStrength) {}

    static Materials materials() {
        return new Materials(indicator(), screen(), core(), defaultEmission(), surfaces(), normals(),
                surfaceStrength(), normalStrength());
    }

    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private BloomConfig() {}
}
