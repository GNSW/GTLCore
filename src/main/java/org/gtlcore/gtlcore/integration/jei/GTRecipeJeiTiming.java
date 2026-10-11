package org.gtlcore.gtlcore.integration.jei;

import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import java.util.IdentityHashMap;
import java.util.Map;

public final class GTRecipeJeiTiming {

    private static final ThreadLocal<Map<GTRecipeType, MutableTiming>> TIMINGS = ThreadLocal.withInitial(IdentityHashMap::new);

    private GTRecipeJeiTiming() {}

    public static boolean isEnabled() {
        return ConfigHolder.INSTANCE == null || ConfigHolder.INSTANCE.performance.optimizeGtceuJeiRegistration;
    }

    public static void reset() {
        TIMINGS.get().clear();
    }

    public static void record(GTRecipeType recipeType, Phase phase, long elapsedNanos) {
        if (!isEnabled()) {
            return;
        }
        MutableTiming timing = TIMINGS.get().computeIfAbsent(recipeType, ignored -> new MutableTiming());
        switch (phase) {
            case WRAPPER -> timing.wrapperNanos += elapsedNanos;
            case REGISTRATION -> timing.registrationNanos += elapsedNanos;
        }
    }

    public static Timing get(GTRecipeType recipeType) {
        MutableTiming timing = TIMINGS.get().get(recipeType);
        return timing == null ? Timing.EMPTY : timing.snapshot();
    }

    public enum Phase {
        WRAPPER,
        REGISTRATION
    }

    public record Timing(long wrapperNanos, long registrationNanos) {

        private static final Timing EMPTY = new Timing(0, 0);
    }

    private static final class MutableTiming {

        private long wrapperNanos;
        private long registrationNanos;

        private Timing snapshot() {
            return new Timing(wrapperNanos, registrationNanos);
        }
    }
}
