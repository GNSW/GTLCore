package org.gtlcore.gtlcore.config;

import dev.toma.configuration.config.Configurable;

/** Client-local rendering settings; never synchronized from a server. */
public class BloomOptions {

    public enum Mode {

        OFF,
        AUTO,
        FORCE_LOCAL,
        FORCE_PBR;

        public boolean local(boolean shaders) {
            return this == FORCE_LOCAL || this == AUTO && !shaders;
        }

        public boolean pbr() {
            return this == AUTO || this == FORCE_PBR;
        }
    }

    @Configurable
    @Configurable.Comment("config.gtlcore.option.bloomMode.comment")
    public Mode bloomMode = Mode.OFF;

    @Configurable
    public Local local = new Local();

    @Configurable
    public Pbr pbr = new Pbr();

    public static class Local {

        @Configurable
        @Configurable.DecimalRange(min = 0, max = 6)
        @Configurable.Comment("config.gtlcore.option.bloomStrength.comment")
        public double bloomStrength = 1.7;

        @Configurable
        @Configurable.DecimalRange(min = 0, max = 1)
        @Configurable.Comment("config.gtlcore.option.bloomRadius.comment")
        public double bloomRadius = 1.0;

        @Configurable
        @Configurable.DecimalRange(min = 0.1, max = 16)
        @Configurable.Comment("config.gtlcore.option.bloomBuildBudgetMs.comment")
        public double bloomBuildBudgetMs = 1.5;
    }

    public static class Pbr {

        @Configurable
        @Configurable.DecimalRange(min = 0, max = 1)
        @Configurable.Comment("config.gtlcore.option.indicatorEmission.comment")
        public double indicatorEmission = 0.9;

        @Configurable
        @Configurable.DecimalRange(min = 0, max = 1)
        @Configurable.Comment("config.gtlcore.option.screenEmission.comment")
        public double screenEmission = 1.0;

        @Configurable
        @Configurable.DecimalRange(min = 0, max = 1)
        @Configurable.Comment("config.gtlcore.option.coreEmission.comment")
        public double coreEmission = 1.0;

        @Configurable
        @Configurable.DecimalRange(min = 0, max = 1)
        @Configurable.Comment("config.gtlcore.option.defaultEmission.comment")
        public double defaultEmission = 1.0;

        @Configurable
        @Configurable.Comment("config.gtlcore.option.generateSurfaceMaterials.comment")
        public boolean generateSurfaceMaterials = true;

        @Configurable
        @Configurable.Comment("config.gtlcore.option.generateSurfaceNormals.comment")
        public boolean generateSurfaceNormals = true;

        @Configurable
        @Configurable.DecimalRange(min = 0, max = 1)
        @Configurable.Comment("config.gtlcore.option.surfaceVariation.comment")
        public double surfaceVariation = 0.65;

        @Configurable
        @Configurable.DecimalRange(min = 0, max = 1)
        @Configurable.Comment("config.gtlcore.option.surfaceNormalStrength.comment")
        public double surfaceNormalStrength = 0.6;
    }
}
