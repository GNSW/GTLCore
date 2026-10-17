package org.gtlcore.gtlcore.config;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Moves legacy YAML keys before registration, preserving existing category values. */
final class ConfigCategoryMigration {

    private static final String GRAPH_PLANNER_OPTIONS = "ae2GraphPlannerTimeoutMs ae2GraphPlannerThreads ae2GraphPlannerMaxRequests ae2GraphPlannerMaxSteps ae2GraphCompilerMaxSteps ae2GraphCachePreparationMaxSteps ae2GraphPlannerParallelWorkBudget ae2GraphFallback ae2GraphFallbackTimeoutMs ae2GraphFallbackMaxSteps ae2GraphFallbackMemoryMiB ae2GraphPlannerMemoryMiB ae2GraphGlobalMemoryMiB ae2GraphCacheMemoryMiB ae2GraphSnapshotMicros ae2GraphSliceSteps ae2GraphSliceMicros";
    private static final Map<String, String> CATEGORIES = Map.ofEntries(
            Map.entry("gameplay", "disableDrift travelStaffCD ftbUltimineRange minimumBlockMiningTicks sendUpdateMessages"),
            Map.entry("content", "enableSkyBlokeMode enablePrimitiveVoidOre oreMultiplier durationMultiplier"),
            Map.entry("machines", "cellType spacetimePip exPatternProvider patternBoxPages MEPatternOutputMin MEPatternOutputMax enableUltimateMEStocking filterHatch batchProcessingTimeLimitTicks batchProcessingEnabledByDefault mobList1 mobList2"),
            Map.entry("ae", "enableAe2ManualCraftingInventoryLock ae2GraphInventoryLockBehavior enableAe2MissingCrafting ae2CalculationMode ae2CraftingEngine ae2GraphSeedPolicy ae2GraphByteCostMode ae2GraphDiscoverByproducts ae2GraphMaxExtraCatalystCopies ae2PatternProviderAutoExpandDefault " + GRAPH_PLANNER_OPTIONS),
            Map.entry("performance", "ae2CraftingServiceUpdateInterval ae2StorageServiceUpdateInterval enableMachineStartupTickBudget machineStartupTickBudgetPerLevel machineStartupAeTickBudgetPerLevel machineStartupTickTimeBudgetMillis optimizeGtceuJeiRegistration"),
            Map.entry("graphics", "enableSmoothAnimations multiblockPreview bloom"),
            Map.entry("diagnostics", "debugLogging ae2GraphDiagnosticLogging"),
            Map.entry("interaction", "ae2GraphPlannerNoticeAfterMs blackBlockList"));
    private static final Pattern KEY = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9_]*):");

    static void migrate() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("gtlcore.yaml");
        if (!Files.isRegularFile(file)) return;
        try {
            String original = Files.readString(file, Charset.defaultCharset());
            String migrated = migrateContent(original);
            if (original.equals(migrated)) return;
            Path backup = Files.createTempFile(file.getParent(), "gtlcore-pre-categories-", ".yaml.bak");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Path temporary = Files.createTempFile(file.getParent(), "gtlcore-categories-", ".tmp");
            try {
                Files.writeString(temporary, migrated, Charset.defaultCharset());
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not migrate GTLCore configuration categories", exception);
        }
    }

    static String migrateContent(String original) {
        String normalized = original.replace("\r\n", "\n");
        Map<String, String> root = blocks(normalized);
        Map<String, Map<String, String>> categories = new LinkedHashMap<>();
        CATEGORIES.keySet().forEach(category -> categories.put(category, children(root.get(category))));
        boolean changed = false;
        // New AE settings take precedence, then the former performance category,
        // then the oldest flat keys. Never silently replace a user's budget.
        for (String field : GRAPH_PLANNER_OPTIONS.split(" ")) {
            String legacy = categories.get("performance").remove(field);
            if (legacy != null) {
                categories.get("ae").putIfAbsent(field, legacy);
                changed = true;
            }
        }
        for (var entry : CATEGORIES.entrySet()) {
            Map<String, String> target = categories.get(entry.getKey());
            for (String field : entry.getValue().split(" ")) {
                String legacy = root.remove(field);
                if (legacy != null) {
                    target.putIfAbsent(field, legacy);
                    changed = true;
                }
            }
        }
        String smooth = categories.get("gameplay").remove("enableSmoothAnimations");
        if (smooth != null) {
            categories.get("graphics").putIfAbsent("enableSmoothAnimations", smooth);
            changed = true;
        }
        if (!changed) return original;
        var first = KEY.matcher(normalized);
        StringBuilder result = new StringBuilder(first.find() ? normalized.substring(0, first.start()) : "");
        for (var entry : categories.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                root.put(entry.getKey(), entry.getKey() + ":\n" + indent(String.join("", entry.getValue().values())));
            } else root.remove(entry.getKey());
        }
        root.values().forEach(result::append);
        return result.toString();
    }

    private static Map<String, String> children(String block) {
        if (block == null) return new LinkedHashMap<>();
        int newline = block.indexOf('\n');
        if (newline < 0) return new LinkedHashMap<>();
        StringBuilder unindented = new StringBuilder();
        for (String line : block.substring(newline + 1).split("\n", -1)) {
            unindented.append(line.startsWith("  ") ? line.substring(2) : line).append('\n');
        }
        return blocks(unindented.toString());
    }

    private static Map<String, String> blocks(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        var matcher = KEY.matcher(text);
        String key = null;
        int start = 0;
        while (matcher.find()) {
            if (key != null) result.put(key, text.substring(start, matcher.start()));
            key = matcher.group(1);
            start = matcher.start();
        }
        if (key != null) result.put(key, text.substring(start) + (text.endsWith("\n") ? "" : "\n"));
        return result;
    }

    private static String indent(String text) {
        return text.replaceAll("(?m)^", "  ");
    }

    private ConfigCategoryMigration() {}
}
