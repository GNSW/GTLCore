package org.gtlcore.gtlcore.client.terminal;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists the two route choices without rewriting unrelated GTLCore config.
 */
public final class TerminalConfigWriter {

    private static final String SKYBLOCK_KEY = "enableSkyBlokeMode";
    private static final String VOID_MINER_KEY = "enablePrimitiveVoidOre";

    private TerminalConfigWriter() {}

    public static boolean isSkyblockMode() {
        return ConfigHolder.INSTANCE != null && ConfigHolder.INSTANCE.content.enableSkyBlokeMode;
    }

    public static Result apply(Minecraft minecraft, boolean skyblock, boolean primitiveVoidMiner) {
        if (ConfigHolder.INSTANCE == null) {
            return Result.failure(Component.translatable("gtlcore.terminal.error.config_missing"));
        }
        Path configPath = minecraft.gameDirectory.toPath().resolve("config").resolve("gtlcore.yaml");
        if (!Files.isRegularFile(configPath)) {
            return Result.failure(Component.translatable("gtlcore.terminal.error.config_missing"));
        }

        try {
            String original = Files.readString(configPath, StandardCharsets.UTF_8);
            Replacement skyblockResult = replaceBoolean(original, SKYBLOCK_KEY, skyblock);
            Replacement combined = replaceBoolean(skyblockResult.text(), VOID_MINER_KEY, primitiveVoidMiner);
            if (skyblockResult.replacements() == 0 || combined.replacements() == 0) {
                return Result.failure(Component.translatable("gtlcore.terminal.error.config_incomplete"));
            }

            Path backup = configPath.resolveSibling("gtlcore.yaml.galactic-terminal.bak");
            Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);

            Path temporary = Files.createTempFile(configPath.getParent(), "gtlcore", ".yaml.tmp");
            try {
                Files.writeString(temporary, combined.text(), StandardCharsets.UTF_8);
                moveIntoPlace(temporary, configPath);
            } finally {
                Files.deleteIfExists(temporary);
            }

            ConfigHolder.INSTANCE.content.enableSkyBlokeMode = skyblock;
            ConfigHolder.INSTANCE.content.enablePrimitiveVoidOre = primitiveVoidMiner;
            GTLCore.LOGGER.info("GTLCore route configured: skyblock={}, primitiveVoidMiner={}", skyblock,
                    primitiveVoidMiner);
            return Result.success(Component.translatable("gtlcore.terminal.status.saved"));
        } catch (IOException exception) {
            GTLCore.LOGGER.error("Unable to update GTLCore route configuration at {}", configPath, exception);
            return Result.failure(Component.translatable("gtlcore.terminal.error.config_write_failed"));
        }
    }

    private static Replacement replaceBoolean(String text, String key, boolean value) {
        Pattern pattern = Pattern.compile(
                "(?m)^(\\s*)" + Pattern.quote(key) + "(\\s*:\\s*)(true|false)(\\s*(?:#.*)?)$",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        int replacements = 0;
        StringBuilder output = new StringBuilder(text.length());
        while (matcher.find()) {
            replacements++;
            matcher.appendReplacement(output, Matcher.quoteReplacement(
                    matcher.group(1) + key + matcher.group(2) + value + matcher.group(4)));
        }
        matcher.appendTail(output);
        return new Replacement(output.toString(), replacements);
    }

    private static void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Replacement(String text, int replacements) {}

    public record Result(boolean success, Component message) {

        public static Result success(Component message) {
            return new Result(true, message);
        }

        public static Result failure(Component message) {
            return new Result(false, message);
        }
    }
}
