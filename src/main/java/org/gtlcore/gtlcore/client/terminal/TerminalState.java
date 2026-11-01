package org.gtlcore.gtlcore.client.terminal;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Remembers terminal completion per local launcher profile.
 */
public final class TerminalState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TerminalState() {}

    public static boolean hasCompleted(Minecraft minecraft) {
        return read(minecraft).completedProfiles.containsKey(profileKey(minecraft.getUser()));
    }

    public static boolean markCompleted(Minecraft minecraft) {
        Path path = statePath(minecraft);
        StateData data = read(minecraft);
        data.completedProfiles.put(profileKey(minecraft.getUser()), Instant.now().toString());
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "gtlcore_terminal", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(data), StandardCharsets.UTF_8);
                moveIntoPlace(temporary, path);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return true;
        } catch (IOException exception) {
            GTLCore.LOGGER.error("Unable to save Galactic Terminal completion state at {}", path, exception);
            return false;
        }
    }

    public static Path statePath(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("config").resolve("gtlcore_terminal.json");
    }

    private static StateData read(Minecraft minecraft) {
        Path path = statePath(minecraft);
        if (!Files.isRegularFile(path)) {
            return new StateData();
        }
        try {
            StateData data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), StateData.class);
            if (data == null) {
                return new StateData();
            }
            if (data.completedProfiles == null) {
                data.completedProfiles = new LinkedHashMap<>();
            }
            return data;
        } catch (IOException | JsonSyntaxException exception) {
            GTLCore.LOGGER.warn("Could not read Galactic Terminal state from {}; treating it as unconfigured.",
                    path, exception);
            return new StateData();
        }
    }

    private static String profileKey(User user) {
        if (user.getProfileId() != null) {
            return user.getProfileId().toString();
        }
        return "name:" + user.getName().toLowerCase(Locale.ROOT);
    }

    private static void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class StateData {

        private Map<String, String> completedProfiles = new LinkedHashMap<>();
    }
}
