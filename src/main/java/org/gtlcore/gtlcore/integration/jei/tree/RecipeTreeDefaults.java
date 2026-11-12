package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraftforge.fml.loading.FMLPaths;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent default recipe choices, keyed by the tree ingredient key.
 *
 * <p>
 * This plays the role of EMI's {@code BoM.addedRecipes}: the tree only expands a material when the
 * player marked one of its recipes as the default.
 * </p>
 */
final class RecipeTreeDefaults {

    private final Path file;
    private final Map<String, String> defaults = new TreeMap<>();

    RecipeTreeDefaults() {
        this.file = FMLPaths.CONFIGDIR.get().resolve("gtlcore-recipe-tree.json");
        load();
    }

    @Nullable
    String get(String ingredientKey) {
        return defaults.get(ingredientKey);
    }

    void put(String ingredientKey, String recipeIdentity) {
        defaults.put(ingredientKey, recipeIdentity);
    }

    void remove(String ingredientKey) {
        defaults.remove(ingredientKey);
    }

    void save() {
        try {
            JsonObject root = new JsonObject();
            defaults.forEach(root::addProperty);
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            GTLCore.LOGGER.warn("Could not save JEI recipe tree defaults to {}", file, exception);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (String key : root.keySet()) {
                if (root.get(key).isJsonPrimitive()) {
                    defaults.put(key, root.get(key).getAsString());
                }
            }
        } catch (Exception exception) {
            GTLCore.LOGGER.warn("Could not load JEI recipe tree defaults from {}", file, exception);
        }
    }
}
