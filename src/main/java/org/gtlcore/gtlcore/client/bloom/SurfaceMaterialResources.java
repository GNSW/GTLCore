package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import com.google.gson.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One instance per atlas load: bounded, reload-scoped cache; no per-frame image processing. */
final class SurfaceMaterialResources {

    private static final ResourceLocation PROFILES = new ResourceLocation("gtlbloom", "surface_profiles.json");

    private record Profile(String hash, String category, int width, int height, int frameWidth, int frameHeight,
                           String structure, ResourceLocation mask, String maskHash) {}

    private record Pair(Resource specular, Resource normal) {}

    private record Key(ResourceLocation sprite, double strength, double normalStrength, boolean normals) {}

    private Map<ResourceLocation, Profile> profiles;
    private final Map<Key, Optional<Pair>> cache = new HashMap<>();

    Optional<Resource> resource(ResourceManager manager, ResourceLocation sprite, boolean normal) {
        if (!BloomConfig.surfaces() || (normal && !BloomConfig.normals())) return Optional.empty();
        if (profiles == null) profiles = readProfiles(manager);
        Profile profile = profiles.get(sprite);
        if (profile == null) return Optional.empty();
        Key key = new Key(sprite, BloomConfig.surfaceStrength(), BloomConfig.normalStrength(), BloomConfig.normals());
        Optional<Pair> pair = cache.computeIfAbsent(key, k -> generate(manager, k, profile));
        return pair.map(p -> normal ? p.normal() : p.specular());
    }

    private static Map<ResourceLocation, Profile> readProfiles(ResourceManager manager) {
        Optional<Resource> resource = manager.getResource(PROFILES);
        if (resource.isEmpty()) return Map.of();
        try {
            byte[] bytes = read(resource.get(), 1024 * 1024);
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unknown surface schema");
            JsonArray rows = root.getAsJsonArray("profiles");
            if (rows.size() > 1024) throw new IllegalArgumentException("Too many surface profiles");
            Map<ResourceLocation, Profile> parsed = new HashMap<>();
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                ResourceLocation sprite = new ResourceLocation(row.get("sprite").getAsString());
                String category = row.get("category").getAsString(), structure = row.get("structure").getAsString();
                if (!Set.of("metal", "paint", "glass").contains(category) || !Set.of("none", "height_mask").contains(structure)) throw new IllegalArgumentException("Unknown surface policy");
                int w = row.get("width").getAsInt(), h = row.get("height").getAsInt(), fw = row.get("frame_width").getAsInt(), fh = row.get("frame_height").getAsInt();
                if (w < 1 || h < 1 || (long) w * h > 1048576 || fw < 1 || fh < 1 || w % fw != 0 || h % fh != 0) throw new IllegalArgumentException("Invalid surface dimensions");
                ResourceLocation mask = null;
                String mh = null;
                if (structure.equals("height_mask")) {
                    if (w != fw || h != fh || sprite.getPath().endsWith("_ctm") || category.equals("glass")) throw new IllegalArgumentException("Unsafe height-mask layout");
                    mask = new ResourceLocation(row.get("mask").getAsString());
                    mh = hashValue(row, "mask_sha256");
                }
                Profile profile = new Profile(hashValue(row, "source_sha256"), category, w, h, fw, fh, structure, mask, mh);
                if (parsed.put(sprite, profile) != null) throw new IllegalArgumentException("Duplicate surface profile");
            }
            return Map.copyOf(parsed);
        } catch (Exception failure) {
            GTLCore.LOGGER.warn("Surface profiles ignored: {}", failure.toString());
            return Map.of();
        }
    }

    private static Optional<Pair> generate(ResourceManager manager, Key key, Profile p) {
        try {
            Optional<Resource> source = manager.getResource(new ResourceLocation(key.sprite().getNamespace(), "textures/" + key.sprite().getPath() + ".png"));
            if (source.isEmpty()) return Optional.empty();
            byte[] bytes = read(source.get(), 4 * 1024 * 1024);
            if (!sha256(bytes).equals(p.hash())) return Optional.empty();
            BufferedImage base = LabPbrPixels.decodePng(bytes);
            if (base.getWidth() != p.width() || base.getHeight() != p.height()) return Optional.empty();
            var animation = source.get().metadata().getSection(AnimationMetadataSection.SERIALIZER).orElse(AnimationMetadataSection.EMPTY);
            var frame = animation.calculateFrameSize(p.width(), p.height());
            if (frame.width() != p.frameWidth() || frame.height() != p.frameHeight()) return Optional.empty();
            BufferedImage mask = null;
            if (key.normals() && p.mask() != null) {
                Optional<Resource> mr = manager.getResource(p.mask());
                if (mr.isPresent()) {
                    byte[] mb = read(mr.get(), 4 * 1024 * 1024);
                    if (sha256(mb).equals(p.maskHash())) {
                        BufferedImage candidate = LabPbrPixels.decodePng(mb);
                        if (candidate.getWidth() == p.width() && candidate.getHeight() == p.height()) mask = candidate;
                    }
                }
            }
            // Connected-texture layouts can rearrange subtiles; without the actual topology,
            // color-neighborhood derivatives across their atlas boundaries are not meaningful.
            // Keep the semantic uniform finish and no normals for these exact CTM profiles.
            double variation = key.sprite().getPath().endsWith("_ctm") ? 0.0 : key.strength();
            SurfaceMaterialPixels.Result result = SurfaceMaterialPixels.generate(base, p.category(), p.frameWidth(), p.frameHeight(), mask, variation, key.normalStrength());
            return Optional.of(new Pair(resource(source.get(), result.specular()), result.normal() == null ? null : resource(source.get(), result.normal())));
        } catch (Exception failure) {
            GTLCore.LOGGER.warn("Surface material ignored for {}: {}", key.sprite(), failure.toString());
            return Optional.empty();
        }
    }

    private static Resource resource(Resource source, BufferedImage image) throws Exception {
        byte[] bytes = LabPbrPixels.encodePng(image);
        return new Resource(source.source(), () -> new ByteArrayInputStream(bytes), source::metadata);
    }

    private static byte[] read(Resource resource, int max) throws Exception {
        try (var input = resource.open()) {
            byte[] bytes = input.readNBytes(max + 1);
            if (bytes.length > max) throw new IllegalArgumentException("Surface resource too large");
            return bytes;
        }
    }

    private static String hashValue(JsonObject row, String key) {
        String value = row.get(key).getAsString();
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source hash");
        return value;
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
