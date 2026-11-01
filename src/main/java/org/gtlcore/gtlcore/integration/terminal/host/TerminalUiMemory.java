package org.gtlcore.gtlcore.integration.terminal.host;

import org.gtlcore.gtlcore.common.block.BlockMap;

import net.minecraftforge.fml.loading.FMLPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TerminalUiMemory {

    private static final Logger LOGGER = LoggerFactory.getLogger("gtlcore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data;

    private TerminalUiMemory() {}

    public record Data(boolean buildNoHatch, boolean buildPreferAe, int repeatCount, String blockType,
                       int blockTier, boolean dismantleToAe, String upgradeBlockType,
                       int upgradeBlockTier) {}

    public static Data get() {
        if (data == null) {
            data = load();
        }
        return data;
    }

    public static void rememberBuild(boolean noHatch, boolean preferAe, int repeatCount,
                                     String blockType, int blockTier) {
        Data old = get();
        data = new Data(noHatch, preferAe, Math.max(0, Math.min(648, repeatCount)),
                normalizeBlockType(blockType), Math.max(0, blockTier), old.dismantleToAe(),
                old.upgradeBlockType(), old.upgradeBlockTier());
        save();
    }

    public static void rememberDismantle(boolean returnToAe) {
        Data old = get();
        data = new Data(old.buildNoHatch(), old.buildPreferAe(), old.repeatCount(), old.blockType(),
                old.blockTier(), returnToAe, old.upgradeBlockType(), old.upgradeBlockTier());
        save();
    }

    public static void rememberUpgrade(String blockType, int blockTier) {
        Data old = get();
        data = new Data(old.buildNoHatch(), old.buildPreferAe(), old.repeatCount(), old.blockType(),
                old.blockTier(), old.dismantleToAe(),
                normalizeBlockType(blockType), Math.max(0, blockTier));
        save();
    }

    public static String normalizeBlockType(String type) {
        if (type != null && BlockMap.tierBlockMap.containsKey(type)) {
            return type;
        }
        return "coil";
    }

    private static Data load() {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return defaults();
        }
        try {
            Data loaded = GSON.fromJson(Files.readString(path), Data.class);
            if (loaded == null) {
                return defaults();
            }
            return new Data(
                    loaded.buildNoHatch(),
                    loaded.buildPreferAe(),
                    Math.max(0, Math.min(648, loaded.repeatCount())),
                    normalizeBlockType(loaded.blockType()),
                    Math.max(0, loaded.blockTier()),
                    loaded.dismantleToAe(),
                    normalizeBlockType(loaded.upgradeBlockType()),
                    Math.max(0, loaded.upgradeBlockTier()));
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Could not read terminal UI memory; using defaults", error);
            return defaults();
        }
    }

    private static Data defaults() {
        return new Data(true, false, 0, "coil", 0, true, "coil", 0);
    }

    private static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data));
        } catch (IOException error) {
            LOGGER.warn("Could not save terminal UI memory", error);
        }
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("gtlcore-terminal-host-client.json");
    }
}
