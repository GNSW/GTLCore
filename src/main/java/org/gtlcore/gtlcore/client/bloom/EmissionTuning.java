package org.gtlcore.gtlcore.client.bloom;

import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.Resource;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Locale;

/** Explicit resource metadata, not filename guesses or shader-pack-specific rules. */
public final class EmissionTuning {

    public enum Category {

        INDICATOR,
        SCREEN,
        CORE,
        DEFAULT;

        static Category parse(String value) {
            if (value == null) return DEFAULT;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return DEFAULT;
            }
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static final MetadataSectionSerializer<Category> METADATA = new MetadataSectionSerializer<>() {

        public String getMetadataSectionName() {
            return "gtlbloom";
        }

        public Category fromJson(JsonObject json) {
            var value = json.get("emission_category");
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Category.parse(value.getAsString()) : Category.DEFAULT;
        }
    };

    static Category category(Resource resource) throws IOException {
        return resource.metadata().getSection(METADATA).orElse(Category.DEFAULT);
    }

    static double setting(Category category) {
        return switch (category) {
            case INDICATOR -> BloomConfig.indicator();
            case SCREEN -> BloomConfig.screen();
            case CORE -> BloomConfig.core();
            case DEFAULT -> BloomConfig.defaultEmission();
        };
    }

    static double strength(Resource resource) throws IOException {
        return setting(category(resource));
    }

    public static String status() {
        return "indicator=" + BloomConfig.indicator() + ", screen=" + BloomConfig.screen() + ", core=" + BloomConfig.core() + ", default=" + BloomConfig.defaultEmission();
    }

    private EmissionTuning() {}
}
