package org.gtlcore.gtlcore.config;

import org.gtlcore.gtlcore.GTLCore;

import dev.toma.configuration.Configuration;
import dev.toma.configuration.config.Config;
import dev.toma.configuration.config.Configurable;
import dev.toma.configuration.config.format.ConfigFormats;

@Config(id = GTLCore.MOD_ID)
public class ConfigHolder {

    public static ConfigHolder INSTANCE;
    public static final int DEFAULT_MACHINE_STARTUP_TICK_BUDGET_PER_LEVEL = 32;
    public static final int DEFAULT_MACHINE_STARTUP_AE_TICK_BUDGET_PER_LEVEL = 32;
    public static final int DEFAULT_MACHINE_STARTUP_TICK_TIME_BUDGET_MILLIS = 10;
    public static final int DEFAULT_GTCEU_JEI_SLOW_RECIPE_TYPE_WARNING_MILLIS = 100;
    private static final Object LOCK = new Object();

    public static void init() {
        synchronized (LOCK) {
            if (INSTANCE == null) {
                INSTANCE = Configuration.registerConfig(ConfigHolder.class, ConfigFormats.yaml()).getConfigInstance();
            }
        }
    }

    @Configurable
    public boolean disableDrift = true;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.enableSkyBlokeMode.comment")
    public boolean enableSkyBlokeMode = false;
    @Configurable
    @Configurable.Range(min = 1)
    public int oreMultiplier = 4;
    @Configurable
    @Configurable.Range(min = 1)
    public int cellType = 4;
    @Configurable
    @Configurable.Range(min = 1)
    public int spacetimePip = Integer.MAX_VALUE;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.durationMultiplier.comment")
    @Configurable.Range(min = 0)
    public double durationMultiplier = 1;
    @Configurable
    @Configurable.Range(min = 1)
    public int travelStaffCD = 2;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.exPatternProvider.comment")
    @Configurable.Range(min = 36, max = 360)
    public int exPatternProvider = 36;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.patternBoxPages.comment")
    @Configurable.Range(min = 1, max = 10)
    @Configurable.Synchronized
    public int patternBoxPages = 2;
    @Configurable
    public boolean enablePrimitiveVoidOre = false;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.blackBlockList.comment")
    @Configurable.Synchronized
    public String[] blackBlockList = { "ae2:cable_bus", "minecraft:grass_block" };
    @Configurable
    @Configurable.Comment("config.gtlcore.option.enableSmoothAnimations.comment")
    public boolean enableSmoothAnimations = true;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.MEPatternOutputMin.comment")
    @Configurable.Range(min = 1, max = 100)
    public int MEPatternOutputMin = 5;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.MEPatternOutputMax.comment")
    @Configurable.Range(min = 1, max = 200)
    public int MEPatternOutputMax = 80;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.enableUltimateMEStocking.comment")
    public boolean enableUltimateMEStocking = false;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.ae2CraftingServiceUpdateInterval.comment")
    @Configurable.Range(min = 1, max = 16)
    public int ae2CraftingServiceUpdateInterval = 4;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.ae2StorageServiceUpdateInterval.comment")
    @Configurable.Range(min = 1, max = 16)
    public int ae2StorageServiceUpdateInterval = 8;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.enableAe2ManualCraftingInventoryLock.comment")
    public boolean enableAe2ManualCraftingInventoryLock = false;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.enableAe2MissingCrafting.comment")
    public boolean enableAe2MissingCrafting = true;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.ae2CalculationMode.comment")
    public AE2CalculationMode ae2CalculationMode = AE2CalculationMode.MAX_FAST;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.ae2CraftingEngine.comment")
    public AECraftingEngine ae2CraftingEngine = AECraftingEngine.LEGACY;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.ae2GraphSeedPolicy.comment")
    public AEGraphSeedPolicy ae2GraphSeedPolicy = AEGraphSeedPolicy.PRESERVE;
    @Configurable
    @Configurable.Range(min = 0, max = 4096)
    @Configurable.Comment("config.gtlcore.option.ae2GraphMaxExtraCatalystCopies.comment")
    public int ae2GraphMaxExtraCatalystCopies = 64;
    @Configurable
    @Configurable.Range(min = 1, max = 600000)
    @Configurable.Comment("config.gtlcore.option.ae2GraphPlannerNoticeAfterMs.comment")
    public int ae2GraphPlannerNoticeAfterMs = 2000;
    @Configurable
    @Configurable.Range(min = 0, max = 86400000)
    @Configurable.Comment("config.gtlcore.option.ae2GraphPlannerTimeoutMs.comment")
    public int ae2GraphPlannerTimeoutMs = 0;
    @Configurable
    @Configurable.Range(min = 1, max = 16)
    @Configurable.Comment("config.gtlcore.option.ae2GraphPlannerThreads.comment")
    public int ae2GraphPlannerThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
    @Configurable
    @Configurable.Range(min = 1, max = 128)
    @Configurable.Comment("config.gtlcore.option.ae2GraphPlannerMaxRequests.comment")
    public int ae2GraphPlannerMaxRequests = 16;
    @Configurable
    @Configurable.Range(min = 10000, max = Integer.MAX_VALUE)
    @Configurable.Comment("config.gtlcore.option.ae2GraphPlannerMaxSteps.comment")
    public int ae2GraphPlannerMaxSteps = 10000000;
    @Configurable
    @Configurable.Range(min = 16, max = 1024)
    @Configurable.Comment("config.gtlcore.option.ae2GraphPlannerMemoryMiB.comment")
    public int ae2GraphPlannerMemoryMiB = 128;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.ae2GraphDiagnosticLogging.comment")
    public boolean ae2GraphDiagnosticLogging = false;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.enableMachineStartupTickBudget.comment")
    public boolean enableMachineStartupTickBudget = true;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.machineStartupTickBudgetPerLevel.comment")
    @Configurable.Range(min = 1, max = 4096)
    public int machineStartupTickBudgetPerLevel = DEFAULT_MACHINE_STARTUP_TICK_BUDGET_PER_LEVEL;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.machineStartupAeTickBudgetPerLevel.comment")
    @Configurable.Range(min = 1, max = 4096)
    public int machineStartupAeTickBudgetPerLevel = DEFAULT_MACHINE_STARTUP_AE_TICK_BUDGET_PER_LEVEL;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.machineStartupTickTimeBudgetMillis.comment")
    @Configurable.Range(min = 1, max = 50)
    public int machineStartupTickTimeBudgetMillis = DEFAULT_MACHINE_STARTUP_TICK_TIME_BUDGET_MILLIS;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.optimizeGtceuJeiRegistration.comment")
    public boolean optimizeGtceuJeiRegistration = true;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.ae2PatternProviderAutoExpandDefault.comment")
    public boolean ae2PatternProviderAutoExpandDefault = false;
    @Configurable
    @Configurable.Comment("config.gtlcore.option.filterHatch.comment")
    public String[] filterHatch = new String[] { "input_bus", "output_bus", "item_import_bus", "item_export_bus", "input_hatch", "output_hatch", "energy_input_hatch", "energy_output_hatch", "laser_target_hatch", "laser_source_hatch", "computation_transmitter_hatch", "computation_receiver_hatch", "data_transmitter_hatch", "data_receiver_hatch", "maintenance", "muffler", "rotor_holder" };
    @Configurable
    @Configurable.Comment("config.gtlcore.option.ftbUltimineRange.comment")
    @Configurable.Range(min = 1, max = 20)
    @Configurable.Synchronized
    public int ftbUltimineRange = 4;
    @Configurable
    public boolean sendUpdateMessages = true;

    @Configurable
    @Configurable.Comment("config.gtlcore.option.batchProcessingTimeLimitTicks.comment")
    @Configurable.Range(min = 1)
    public int batchProcessingTimeLimitTicks = 100;

    @Configurable
    @Configurable.Comment("config.gtlcore.option.batchProcessingEnabledByDefault.comment")
    public boolean batchProcessingEnabledByDefault = false;

    @Configurable
    public DebugLoggingOptions debugLogging = new DebugLoggingOptions();

    @Configurable
    public String[] mobList1 = new String[] { "chicken", "rabbit", "sheep", "cow", "horse", "pig", "donkey", "skeleton_horse", "iron_golem", "wolf", "goat", "parrot", "camel", "cat", "fox", "llama", "panda", "polar_bear" };
    @Configurable
    public String[] mobList2 = new String[] { "ghast", "zombie", "pillager", "zombie_villager", "skeleton", "drowned", "witch", "spider", "creeper", "husk", "wither_skeleton", "blaze", "zombified_piglin", "slime", "vindicator", "enderman" };

    public static class DebugLoggingOptions {

        @Configurable
        @Configurable.Range(min = 1, max = 60000)
        public int gtceuJeiSlowRecipeTypeWarningMillis = DEFAULT_GTCEU_JEI_SLOW_RECIPE_TYPE_WARNING_MILLIS;

        @Configurable
        @Configurable.Comment("config.gtlcore.option.enableAe2ManualCraftingInventoryLockLogging.comment")
        public boolean enableAe2ManualCraftingInventoryLockLogging = false;
        @Configurable
        @Configurable.Comment("config.gtlcore.option.enableMaxFastCalculationLogging.comment")
        public boolean enableMaxFastCalculationLogging = false;
        @Configurable
        @Configurable.Comment("config.gtlcore.option.enableBatchProcessingLogging.comment")
        public boolean enableBatchProcessingLogging = false;
    }
}
