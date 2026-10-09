package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.ResourceManager;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class BloomMetadata {

    private static volatile Set<ResourceLocation> sprites = Set.of();
    private static final MetadataSectionSerializer<Boolean> SECTION = new MetadataSectionSerializer<>() {

        public String getMetadataSectionName() {
            return "shimmer";
        }

        public Boolean fromJson(JsonObject json) {
            return json.has("bloom") && json.get("bloom").getAsBoolean();
        }
    };

    public static Set<ResourceLocation> read(ResourceManager resources) {
        Set<ResourceLocation> result = new HashSet<>();
        resources.listResources("textures", id -> id.getPath().endsWith(".png")).forEach((id, resource) -> {
            try {
                if (resource.metadata().getSection(SECTION).orElse(false)) {
                    String path = id.getPath();
                    result.add(new ResourceLocation(id.getNamespace(), path.substring(9, path.length() - 4)));
                }
            } catch (IOException | RuntimeException e) {
                GTLCore.LOGGER.warn("Ignoring invalid bloom metadata for {}: {}", id, e.toString());
            }
        });
        return Set.copyOf(result);
    }

    public static void install(Set<ResourceLocation> value) {
        sprites = value;
        GTLCore.LOGGER.info("Loaded {} Shimmer bloom texture markers", value.size());
    }

    public static boolean matches(BakedQuad quad) {
        if (quad.getTintIndex() < -100) return true;
        var sprite = quad.getSprite().contents().name();
        return matchesTexture(sprite);
    }

    static boolean matchesTexture(ResourceLocation sprite) {
        return sprites.contains(sprite) || BloomRules.texture(sprite);
    }

    public static int count() {
        return sprites.size();
    }

    private BloomMetadata() {}
}
