package org.gtlcore.gtlcore.common.data.machines;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.machine.multiblock.CoilWorkableElectricMultipleRecipesMachine;
import org.gtlcore.gtlcore.api.machine.multiblock.NoEnergyMultiblockMachine;
import org.gtlcore.gtlcore.api.pattern.GTLPredicates;
import org.gtlcore.gtlcore.common.block.BlockMap;
import org.gtlcore.gtlcore.common.data.*;
import org.gtlcore.gtlcore.common.data.machines.structure.MultiBlockMachineAStructureA;
import org.gtlcore.gtlcore.common.data.machines.structure.MultiBlockMachineAStructureB;
import org.gtlcore.gtlcore.common.data.machines.structure.MultiBlockMachineAStructureC;
import org.gtlcore.gtlcore.common.data.machines.structure.MultiBlockMachineAStructureD;
import org.gtlcore.gtlcore.common.data.machines.structure.MultiBlockMachineAStructureE;
import org.gtlcore.gtlcore.common.data.machines.structure.MultiBlockMachineAStructureF;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.*;
import org.gtlcore.gtlcore.common.machine.multiblock.steam.LargeSteamParallelMultiblockMachine;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;
import org.gtlcore.gtlcore.utils.NumberUtils;
import org.gtlcore.gtlcore.utils.Registries;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.CoilWorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.OverclockingLogic;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifier;
import com.gregtechceu.gtceu.common.data.*;
import com.gregtechceu.gtceu.common.machine.multiblock.steam.LargeBoilerMachine;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.material.Fluids;

import com.hepdd.gtmthings.data.CustomMachines;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.gregtechceu.gtceu.api.pattern.Predicates.*;
import static com.gregtechceu.gtceu.common.data.GTRecipeModifiers.ELECTRIC_OVERCLOCK;
import static com.gregtechceu.gtceu.common.registry.GTRegistration.REGISTRATE;

@SuppressWarnings("unused")
public class MultiBlockMachineA {

    public static void init() {
        MultiBlockMachineB.init();
    }

    public final static MultiblockMachineDefinition PLASMA_CONDENSER = REGISTRATE.multiblock("plasma_condenser", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.PLASMA_CONDENSER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.plasma_condenser")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.ANTIFREEZE_HEATPROOF_MACHINE_CASING)
            .pattern(definition -> MultiBlockMachineAStructureA.PLASMA_CONDENSER
                    .where("a", Predicates.blocks(GTLBlocks.ANTIFREEZE_HEATPROOF_MACHINE_CASING.get())
                            .setMinGlobalLimited(120)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("b", Predicates.blocks(Registries.getBlock("kubejs:neutronium_pipe_casing")))
                    .where("c", Predicates.blocks(Registries.getBlock("kubejs:laser_cooling_casing")))
                    .where("d", Predicates.controller(Predicates.blocks(definition.get())))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/antifreeze_heatproof_machine_casing"), GTCEu.id("block/multiblock/vacuum_freezer"))
            .register();

    public final static MultiblockMachineDefinition RARE_EARTH_CENTRIFUGAL = REGISTRATE.multiblock("rare_earth_centrifugal", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.RARE_EARTH_CENTRIFUGAL_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.rare_earth_centrifugal")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.CASING_HSSE_STURDY)
            .pattern((definition) -> MultiBlockMachineAStructureA.RARE_EARTH_CENTRIFUGAL
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.CASING_HSSE_STURDY.get())
                            .setMinGlobalLimited(80)
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_GEARBOX.get()))
                    .where("d", Predicates.blocks(Registries.getBlock("kubejs:neutronium_pipe_casing")))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_sturdy_hsse"), GTCEu.id("block/multiblock/gcym/large_centrifuge"))
            .register();

    public final static MultiblockMachineDefinition SLAUGHTERHOUSE = REGISTRATE.multiblock("magic_manufacturer", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.MAGIC_MANUFACTURER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.magic_manufacturer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.MACHINE_CASING_UIV)
            .pattern((definition) -> MultiBlockMachineAStructureA.SLAUGHTERHOUSE
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.MACHINE_CASING_UIV.get())
                            .setMinGlobalLimited(70)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(Blocks.PURPLE_CANDLE))
                    .where("d", Predicates.blocks(Blocks.CRYING_OBSIDIAN))
                    .where("e", Predicates.blocks(GTLBlocks.HYPER_CORE.get()))
                    .where("f", Predicates.blocks(GTBlocks.FUSION_COIL.get()))
                    .where("g", Predicates.blocks(Blocks.NETHERITE_BLOCK))
                    .where("h", Predicates.blocks(Blocks.BEACON))
                    .where("i", Predicates.blocks(Registries.getBlock("kubejs:magic_core")))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/voltage/uiv/side"), GTCEu.id("block/multiblock/implosion_compressor"))
            .register();

    public final static MultiblockMachineDefinition SPS_CRAFTING = REGISTRATE.multiblock("sps_crafting", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.SPS_CRAFTING_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.sps_crafting")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.FUSION_CASING_MK2)
            .pattern((definition) -> MultiBlockMachineAStructureA.SPS_CRAFTING
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.SPS_CASING.get()))
                    .where("c", Predicates.blocks(GTBlocks.FUSION_CASING_MK2.get())
                            .setMinGlobalLimited(100)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("d", Predicates.blocks(Blocks.CRYING_OBSIDIAN))
                    .where("e", Predicates.blocks(Blocks.REINFORCED_DEEPSLATE))
                    .where("f", Predicates.blocks(GTBlocks.FUSION_GLASS.get()))
                    .where("g", Predicates.blocks(Registries.getBlock("kubejs:magic_core")))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/fusion/fusion_casing_mk2"), GTCEu.id("block/multiblock/assembly_line"))
            .register();

    public final static MultiblockMachineDefinition ADVANCED_SPS_CRAFTING = REGISTRATE.multiblock("advanced_sps_crafting", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.SPS_CRAFTING_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.sps_crafting")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.FUSION_CASING_MK2)
            .pattern((definition) -> MultiBlockMachineAStructureA.ADVANCED_SPS_CRAFTING
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.FUSION_CASING.get()))
                    .where("c", Predicates.blocks(GTBlocks.FUSION_CASING_MK2.get())
                            .setMinGlobalLimited(400)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("e", Predicates.blocks(Registries.getBlock("kubejs:magic_core")))
                    .where("g", Predicates.blocks(GTBlocks.FUSION_COIL.get()))
                    .where("i", Predicates.blocks(GTLBlocks.SPS_CASING.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/fusion/fusion_casing_mk2"), GTCEu.id("block/multiblock/assembly_line"))
            .register();

    public final static MultiblockMachineDefinition MATTER_FABRICATOR = REGISTRATE.multiblock("matter_fabricator", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.MATTER_FABRICATOR_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.matter_fabricator")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.HIGH_POWER_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureA.MATTER_FABRICATOR
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get())
                            .setMinGlobalLimited(90)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.SUPERCONDUCTING_COIL.get()))
                    .where("d", Predicates.blocks(GCyMBlocks.ELECTROLYTIC_CELL.get()))
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/hpca/high_power_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition VOID_FLUID_DRILLING_RIG = REGISTRATE.multiblock("void_fluid_drilling_rig", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.VOID_FLUID_DRILLING_RIG_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.void_fluid_drilling_rig")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.CASING_HSSE_STURDY)
            .pattern((definition) -> MultiBlockMachineAStructureA.VOID_FLUID_DRILLING_RIG
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("X", Predicates.blocks(GTBlocks.CASING_HSSE_STURDY.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("C", Predicates.blocks(GTBlocks.CASING_HSSE_STURDY.get()))
                    .where("F", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.HSSG)))
                    .where("#", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_sturdy_hsse"), GTCEu.id("block/multiblock/fluid_drilling_rig"))
            .register();

    public final static MultiblockMachineDefinition VOID_MINER = REGISTRATE.multiblock("void_miner", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.VOID_MINER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.void_miner")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.CASING_TITANIUM_STABLE)
            .pattern((definition) -> MultiBlockMachineAStructureA.VOID_MINER
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("X", Predicates.blocks(GTBlocks.CASING_TITANIUM_STABLE.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("C", Predicates.blocks(GTBlocks.CASING_TITANIUM_STABLE.get()))
                    .where("F", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Titanium)))
                    .where("#", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_stable_titanium"), GTCEu.id("block/multiblock/large_miner"))
            .register();

    public final static MultiblockMachineDefinition LARGE_VOID_MINER = REGISTRATE.multiblock("large_void_miner", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.LARGE_VOID_MINER_RECIPES)
            .recipeType(GTLRecipeTypes.RANDOM_ORE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.large_void_miner.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.large_void_miner.tooltip.1"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GCY"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.large_void_miner"), Component.translatable("gtceu.random_ore")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST)
            .pattern((definition) -> MultiBlockMachineAStructureA.LARGE_VOID_MINER
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST.get())
                            .setMinGlobalLimited(110)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Ultimet)))
                    .where("d", Predicates.blocks(GTBlocks.CASING_TITANIUM_STABLE.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_robust_tungstensteel"), GTCEu.id("block/multiblock/large_miner"))
            .register();

    public final static MultiblockMachineDefinition CHEMICAL_PLANT = REGISTRATE.multiblock("chemical_plant", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.LARGE_CHEMICAL_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.eut_multiplier.tooltip", 0.8))
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.6))
            .tooltips(Component.translatable("gtceu.machine.chemical_plant.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GCY"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.large_chemical_reactor")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers::chemicalPlantOverclock)
            .appearanceBlock(GTBlocks.CASING_PTFE_INERT)
            .pattern((definition) -> MultiBlockMachineAStructureA.CHEMICAL_PLANT
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.CASING_PTFE_INERT.get())
                            .setMinGlobalLimited(60)
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.heatingCoils())
                    .where("d", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.CHEMICAL_PLANT_DISPLAY)
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_inert_ptfe"), GTCEu.id("block/machines/chemical_reactor"))
            .register();

    public final static MultiblockMachineDefinition DECAY_HASTENER = REGISTRATE.multiblock("decay_hastener", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.DECAY_HASTENER_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.decay_hastener")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.HYPER_MECHANICAL_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureA.DECAY_HASTENER
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.HYPER_MECHANICAL_CASING.get())
                            .setMinGlobalLimited(80)
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.FUSION_CASING.get()))
                    .where("d", Predicates.blocks(GTBlocks.HERMETIC_CASING_UV.get()))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/hyper_mechanical_casing"), GTCEu.id("block/multiblock/data_bank"))
            .register();

    public final static MultiblockMachineDefinition LARGE_RECYCLER = REGISTRATE.multiblock("large_recycler", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.LARGE_RECYCLER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.large_recycler.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.large_recycler")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, (int) Math.pow(4, (((WorkableElectricMultiblockMachine) machine).getTier() - 4)), false).getFirst(), GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.STEEL_HULL)
            .pattern((definition) -> MultiBlockMachineAStructureA.LARGE_RECYCLER
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.STEEL_HULL.get())
                            .setMinGlobalLimited(14)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.CASING_BRONZE_GEARBOX.get()))
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal(FormattingUtil.formatNumbers(Math.pow(4, (((WorkableElectricMultiblockMachine) controller).getTier() - 4)))).withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                }
            })
            .workableCasingRenderer(GTCEu.id("block/casings/steam/steel/side"), GTCEu.id("block/multiblock/gcym/large_maceration_tower"))
            .register();

    public final static MultiblockMachineDefinition MASS_FABRICATOR = REGISTRATE.multiblock("mass_fabricator", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.MASS_FABRICATOR_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GCY"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.mass_fabricator")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.MACHINE_CASING_UHV)
            .pattern((definition) -> MultiBlockMachineAStructureA.MASS_FABRICATOR
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.MACHINE_CASING_UHV.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(16))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where("d", Predicates.blocks(GTLBlocks.HYPER_CORE.get()))
                    .where("e", Predicates.blocks(GTBlocks.HERMETIC_CASING_UHV.get()))
                    .where("f", Predicates.blocks(GCyMBlocks.HEAT_VENT.get()))
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/voltage/uhv/side"), GTCEu.id("block/multiblock/gcym/large_electrolyzer"))
            .register();

    public final static MultiblockMachineDefinition A_MASS_FABRICATOR = REGISTRATE.multiblock("a_mass_fabricator", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.MASS_FABRICATOR_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.eut_multiplier.tooltip", 0.8))
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.5))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.mass_fabricator")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTLRecipeModifiers.reduction(machine, recipe, 0.8, 0.5), GTRecipeModifiers.PARALLEL_HATCH, ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.MACHINE_CASING_UXV)
            .pattern((definition) -> MultiBlockMachineAStructureA.A_MASS_FABRICATOR
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.MACHINE_CASING_UXV.get()))
                    .where("I", Predicates.blocks(GTBlocks.MACHINE_CASING_UXV.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("B", Predicates.blocks(GTLBlocks.RHENIUM_REINFORCED_ENERGY_GLASS.get()))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:titansteel_coil_block")))
                    .where("D", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Neutronium)))
                    .where("E", Predicates.blocks(Registries.getBlock("kubejs:hollow_casing")))
                    .where("F", Predicates.blocks(GTLBlocks.HYPER_CORE.get()))
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/voltage/uxv/side"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition PRECISION_ASSEMBLER = REGISTRATE.multiblock("precision_assembler", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.PRECISION_ASSEMBLER_RECIPES)
            .recipeType(GTRecipeTypes.ASSEMBLER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.precision_assembler"), Component.translatable("gtceu.assembler")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.OXIDATION_RESISTANT_HASTELLOY_N_MECHANICAL_CASING)
            .pattern(definition -> MultiBlockMachineAStructureA.PRECISION_ASSEMBLER
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.OXIDATION_RESISTANT_HASTELLOY_N_MECHANICAL_CASING.get())
                            .setMinGlobalLimited(90)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.CASING_LAMINATED_GLASS.get()))
                    .where("d", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTLMaterials.HastelloyN)))
                    .where("-", Predicates.air())
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/oxidation_resistant_hastelloy_n_mechanical_casing"), GTCEu.id("block/multiblock/gcym/large_assembler"))
            .register();

    public final static MultiblockMachineDefinition FISHING_GROUND = REGISTRATE.multiblock("fishing_ground", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.FISHING_GROUND_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.fishing_ground")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.ALUMINIUM_BRONZE_CASING)
            .pattern(definition -> MultiBlockMachineAStructureA.FISHING_GROUND
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.ALUMINIUM_BRONZE_CASING.get())
                            .setMinGlobalLimited(60)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.fluids(Fluids.WATER))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/aluminium_bronze_casing"), GTCEu.id("block/multiblock/gcym/large_assembler"))
            .register();

    public final static MultiblockMachineDefinition INCUBATOR = REGISTRATE.multiblock("incubator", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.INCUBATOR_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.incubator")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.PLASTCRETE)
            .pattern((definition) -> MultiBlockMachineAStructureA.INCUBATOR
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.PLASTCRETE.get()).setMinGlobalLimited(40)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(Blocks.SPONGE))
                    .where("d", Predicates.blocks(GTBlocks.CLEANROOM_GLASS.get()))
                    .where("e", Predicates.cleanroomFilters())
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/cleanroom/plascrete"), GTCEu.id("block/multiblock/gcym/large_maceration_tower"))
            .register();

    public final static MultiblockMachineDefinition LARGE_INCUBATOR = REGISTRATE.multiblock("large_incubator", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.INCUBATOR_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.incubator")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.PLASTCRETE)
            .pattern((definition) -> MultiBlockMachineAStructureA.LARGE_INCUBATOR
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.PLASTCRETE.get()).setMinGlobalLimited(240)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(Blocks.SPONGE))
                    .where("d", Predicates.blocks(GTBlocks.CLEANROOM_GLASS.get()))
                    .where("e", Predicates.cleanroomFilters())
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/cleanroom/plascrete"), GTCEu.id("block/multiblock/gcym/large_maceration_tower"))
            .register();

    public final static MultiblockMachineDefinition LAVA_FURNACE = REGISTRATE.multiblock("lava_furnace", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .recipeType(GTLRecipeTypes.LAVA_FURNACE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.lava_furnace")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .pattern(definition -> MultiBlockMachineAStructureA.LAVA_FURNACE
                    .where("A", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("D", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes())))
                    .where("M", Predicates.abilities(PartAbility.MUFFLER))
                    .where("C", Predicates.blocks(GTBlocks.FIREBOX_BRONZE.get()))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_oven"))
            .register();

    public final static MultiblockMachineDefinition LARGE_GAS_COLLECTOR = REGISTRATE.multiblock("large_gas_collector", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NONE)
            .allowExtendedFacing(false)
            .allowFlip(false)
            .recipeType(GTLRecipeTypes.LARGE_GAS_COLLECTOR_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.large_gas_collector.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.large_gas_collector")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, 100000, false).getFirst(), GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
            .pattern(definition -> MultiBlockMachineAStructureA.LARGE_GAS_COLLECTOR
                    .where("d", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("a", Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get())
                            .setMinGlobalLimited(40)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where("b", Predicates.blocks(GTBlocks.CASING_GRATE.get()))
                    .where("e", Predicates.blocks(GTBlocks.HERMETIC_CASING_IV.get()))
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_solid_steel"), GTCEu.id("block/machines/gas_collector"))
            .register();

    public final static MultiblockMachineDefinition AGGREGATION_DEVICE = REGISTRATE.multiblock("aggregation_device", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.AGGREGATION_DEVICE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.aggregation_device.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.aggregation_device")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, (int) Math.pow(2, (((WorkableElectricMultiblockMachine) machine).getTier() - GTValues.UEV)), false).getFirst(), GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.FUSION_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureA.AGGREGATION_DEVICE
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("c", Predicates.blocks(GTBlocks.FUSION_CASING.get())
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(32)))
                    .where("d", Predicates.blocks(GTBlocks.FUSION_CASING_MK3.get()))
                    .where("b", Predicates.blocks(GTBlocks.FUSION_COIL.get()))
                    .where("e", Predicates.blocks(Registries.getBlock("kubejs:aggregatione_core")))
                    .where("a", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.NaquadahEnriched)))
                    .where("i", Predicates.blocks(GTMachines.ITEM_IMPORT_BUS[0].get()).or(Predicates.blocks(CustomMachines.HUGE_ITEM_IMPORT_BUS[0].get())))
                    .where("g", GTLPredicates.diffAbilities(List.of(PartAbility.EXPORT_ITEMS), List.of(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS)))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal(FormattingUtil.formatNumbers(Math.pow(2, (((WorkableElectricMultiblockMachine) controller).getTier() - GTValues.UEV)))).withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                }
            })
            .workableCasingRenderer(GTCEu.id("block/casings/fusion/fusion_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition SUPER_PARTICLE_COLLIDER = REGISTRATE.multiblock("super_particle_collider", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.SUPER_PARTICLE_COLLIDER_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.super_particle_collider")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.LAFIUM_MECHANICAL_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureA.SUPER_PARTICLE_COLLIDER
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.LAFIUM_MECHANICAL_CASING.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTLBlocks.LAFIUM_MECHANICAL_CASING.get()))
                    .where("d", Predicates.blocks(Registries.getBlock("kubejs:aggregatione_core")))
                    .where("d", Predicates.blocks(Registries.getBlock("kubejs:accelerated_pipeline")))
                    .where("e", Predicates.blocks(GTBlocks.SUPERCONDUCTING_COIL.get()))
                    .where("f", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.NaquadahEnriched)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/lafium_mechanical_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition ENGRAVING_LASER_PLANT = REGISTRATE.multiblock("engraving_laser_plant", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.PRECISION_LASER_ENGRAVER_RECIPES)
            .recipeType(GTRecipeTypes.LASER_ENGRAVER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.engraving_laser_plant.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.precision_laser_engraver"), Component.translatable("gtceu.laser_engraver")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> {
                OverclockingLogic logic = OverclockingLogic.PERFECT_OVERCLOCK;
                if (machine instanceof WorkableElectricMultiblockMachine workable &&
                        workable.getRecipeType() == GTRecipeTypes.LASER_ENGRAVER_RECIPES) {
                    logic = OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK;
                    recipe = GTRecipeModifiers.hatchParallel(workable, recipe, false, params, result);
                }
                RecipeModifier overclock = GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(logic);
                return overclock.apply(machine, recipe, params, result);
            })
            .appearanceBlock(GTLBlocks.IRIDIUM_CASING)
            .pattern(definition -> MultiBlockMachineAStructureA.ENGRAVING_LASER_PLANT
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(8))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_RECEPTION).setMaxGlobalLimited(1)))
                    .where("B", Predicates.cleanroomFilters())
                    .where("C", Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get()))
                    .where("D", Predicates.blocks(GTBlocks.CASING_ASSEMBLY_LINE.get()))
                    .where("E", Predicates.blocks(GCyMBlocks.ELECTROLYTIC_CELL.get()))
                    .where("F", Predicates.blocks(GTBlocks.CASING_ASSEMBLY_CONTROL.get()))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/iridium_casing"), GTCEu.id("block/multiblock/gcym/large_engraving_laser"))
            .register();

    public final static MultiblockMachineDefinition MEGA_ALLOY_BLAST_SMELTER = REGISTRATE.multiblock("mega_alloy_blast_smelter", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .appearanceBlock(GCyMBlocks.CASING_HIGH_TEMPERATURE_SMELTING)
            .recipeModifiers(GTLRecipeModifiers.GCYM_REDUCTION, GTRecipeModifiers.PARALLEL_HATCH, (machine, recipe, params, result) -> {
                if (machine instanceof CoilWorkableElectricMultiblockMachine coilMachine && coilMachine.getRecipeType() == GCyMRecipeTypes.ALLOY_BLAST_RECIPES) {
                    int requiredTemp = recipe.data.getInt("ebf_temp");
                    int currentTemp = coilMachine.getCoilType().getCoilTemperature() + 100 * Math.max(0, coilMachine.getTier() - GTValues.MV);
                    if (currentTemp >= requiredTemp) {
                        return GTRecipeModifiers.ebfOverclock(machine, recipe, params, result);
                    } else {
                        return null;
                    }
                }
                return recipe;
            })
            .recipeType(GCyMRecipeTypes.ALLOY_BLAST_RECIPES)
            .recipeType(GTRecipeTypes.ALLOY_SMELTER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.mega_alloy_blast_smelter.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.eut_multiplier.tooltip", 0.8))
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.6))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.a"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.2"))
            .tooltips(Component.translatable("gtceu.machine.mega_alloy_blast_smelter.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GT++"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.alloy_blast_smelter"), Component.translatable("gtceu.alloy_smelter")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .pattern(definition -> MultiBlockMachineAStructureA.MEGA_ALLOY_BLAST_SMELTER
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GCyMBlocks.CASING_HIGH_TEMPERATURE_SMELTING.get()).setMinGlobalLimited(280)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1)))
                    .where("a", Predicates.heatingCoils())
                    .where("g", Predicates.abilities(PartAbility.MUFFLER))
                    .where("e", Predicates.blocks(GCyMBlocks.HEAT_VENT.get()))
                    .where("c", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get()))
                    .where("f", Predicates.blocks(GTBlocks.CASING_EXTREME_ENGINE_INTAKE.get()))
                    .where("h", Predicates.blocks(GTBlocks.FIREBOX_STEEL.get()))
                    .where("i", Predicates.blocks(GTBlocks.FIREBOX_TITANIUM.get()))
                    .where("j", Predicates.blocks(GTBlocks.FIREBOX_TUNGSTENSTEEL.get()))
                    .where("k", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.MAX_TEMPERATURE)
            .workableCasingRenderer(GTCEu.id("block/casings/gcym/high_temperature_smelting_casing"), GTCEu.id("block/multiblock/gcym/blast_alloy_smelter"))
            .register();

    public final static MultiblockMachineDefinition DIMENSIONALLY_TRANSCENDENT_MIXER = REGISTRATE.multiblock("dimensionally_transcendent_mixer", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.DIMENSIONALLY_TRANSCENDENT_MIXER_RECIPES)
            .recipeType(GTRecipeTypes.MIXER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.dimensionally_transcendent_mixer.tooltip.0"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.dimensionally_transcendent_mixer"), Component.translatable("gtceu.mixer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> {
                if (machine instanceof WorkableElectricMultiblockMachine workableElectricMultiblockMachine && workableElectricMultiblockMachine.getRecipeType() == GTRecipeTypes.MIXER_RECIPES) {
                    return GTLRecipeModifiers.reduction(machine, recipe, 1, 0.2);
                }
                return recipe;
            }, GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING)
            .pattern(definition -> MultiBlockMachineAStructureA.DIMENSIONALLY_TRANSCENDENT_MIXER
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("a", Predicates.blocks(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get())
                            .setMinGlobalLimited(440)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTLBlocks.DIMENSION_INJECTION_CASING.get()))
                    .where("d", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.block, GTLMaterials.HeavyQuarkDegenerateMatter)))
                    .where("e", Predicates.blocks(Registries.getBlock("kubejs:neutronium_gearbox")))
                    .where("f", Predicates.blocks(Registries.getBlock("kubejs:neutronium_pipe_casing")))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/dimensionally_transcendent_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition QFT = REGISTRATE.multiblock("qft", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.QFT_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GT++"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.qft")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.MANIPULATOR)
            .pattern(definition -> MultiBlockMachineAStructureA.QFT
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.MANIPULATOR.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("a", Predicates.blocks(GTLBlocks.SPACETIMECONTINUUMRIPPER.get()))
                    .where("c", Predicates.blocks(GTLBlocks.SPACETIMEBENDINGCORE.get()))
                    .where("d", Predicates.blocks(GTLBlocks.MANIPULATOR.get()))
                    .where("e", Predicates.blocks(Registries.getBlock("kubejs:force_field_glass")))
                    .where("f", Predicates.blocks(GTLBlocks.QFT_COIL.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/manipulator"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition SUPER_BLAST_SMELTER = REGISTRATE.multiblock("super_blast_smelter", holder -> new CoilWorkableElectricMultipleRecipesMachine(holder, 1, 0.2) {

        @Override
        protected @NotNull RecipeLogic createRecipeLogic(Object @NotNull... args) {
            return new MultipleRecipesLogic(this, EBF_CHECK) {

                @Override
                protected double getTotalEuOfRecipe(GTRecipe recipe) {
                    double eu = super.getTotalEuOfRecipe(recipe);

                    if (recipe.data.contains("ebf_temp")) {
                        final var coilMachine = (CoilWorkableElectricMultiblockMachine) getMachine();
                        int requiredTemp = recipe.data.getInt("ebf_temp");
                        int blastFurnaceTemperature = coilMachine.getCoilType().getCoilTemperature() + 100 * Math.max(0, coilMachine.getTier() - 2);
                        eu *= Math.max(0.5, (double) requiredTemp / blastFurnaceTemperature) * Math.min(1, NumberUtils.pow95(Math.max(0, (blastFurnaceTemperature - requiredTemp) / 900)));
                    }

                    return eu;
                }
            };
        }
    })
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .appearanceBlock(GCyMBlocks.CASING_HIGH_TEMPERATURE_SMELTING)
            .recipeType(GTRecipeTypes.BLAST_RECIPES)
            .recipeType(GTRecipeTypes.ALLOY_SMELTER_RECIPES)
            .recipeType(GCyMRecipeTypes.ALLOY_BLAST_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.super_alloy_blast_smelter.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.2))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.a"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.2"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_3.tooltip",
                    Component.translatable("gtceu.electric_blast_furnace"), Component.translatable("gtceu.alloy_blast_smelter"), Component.translatable("gtceu.alloy_smelter")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .pattern(definition -> MultiBlockMachineAStructureA.SUPER_BLAST_SMELTER
                    .where("b", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("a", Predicates.blocks(GCyMBlocks.CASING_HIGH_TEMPERATURE_SMELTING.get()).setMinGlobalLimited(300)
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1).setPreviewCount(0))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(0))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(0))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(0))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(0))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1).setPreviewCount(0)))
                    .where("c", Predicates.blocks(GCyMBlocks.CASING_HIGH_TEMPERATURE_SMELTING.get()))
                    .where("d", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Tungsten)))
                    .where("e", Predicates.blocks(GCyMBlocks.HEAT_VENT.get()))
                    .where("f", Predicates.blocks(GTBlocks.CASING_ENGINE_INTAKE.get()))
                    .where("g", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.NaquadahAlloy)))
                    .where("h", Predicates.blocks(GTBlocks.FIREBOX_BRONZE.get()))
                    .where("i", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where("j", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where("k", Predicates.blocks(GTBlocks.FIREBOX_STEEL.get()))
                    .where("l", Predicates.blocks(GTBlocks.CASING_EXTREME_ENGINE_INTAKE.get()))
                    .where("m", Predicates.blocks(GTBlocks.CASING_STEEL_PIPE.get()))
                    .where("n", Predicates.blocks(GTBlocks.FIREBOX_TITANIUM.get()))
                    .where("o", Predicates.blocks(GTBlocks.CASING_TITANIUM_PIPE.get()))
                    .where("p", Predicates.blocks(Registries.getBlock("kubejs:neutronium_pipe_casing")))
                    .where("q", Predicates.blocks(GTBlocks.FIREBOX_TUNGSTENSTEEL.get()))
                    .where("r", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where("s", Predicates.heatingCoils())
                    .where("t", Predicates.abilities(PartAbility.MUFFLER))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.MAX_TEMPERATURE)
            .workableCasingRenderer(GTCEu.id("block/casings/gcym/high_temperature_smelting_casing"), GTCEu.id("block/multiblock/gcym/blast_alloy_smelter"))
            .register();

    public final static MultiblockMachineDefinition LARGE_CHEMICAL_PLANT = REGISTRATE.multiblock("large_chemical_plant", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.LARGE_CHEMICAL_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.eut_multiplier.tooltip", 0.8))
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.6))
            .tooltips(Component.translatable("gtceu.machine.chemical_plant.tooltip.0"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.large_chemical_reactor")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers::chemicalPlantOverclock)
            .appearanceBlock(GTBlocks.CASING_PTFE_INERT)
            .pattern((definition) -> MultiBlockMachineAStructureA.LARGE_CHEMICAL_PLANT
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("a", Predicates.blocks(GTBlocks.CASING_PTFE_INERT.get())
                            .setMinGlobalLimited(60)
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("c", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get()))
                    .where("d", Predicates.heatingCoils())
                    .where("b", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.CHEMICAL_PLANT_DISPLAY)
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_inert_ptfe"), GTCEu.id("block/machines/chemical_reactor"))
            .register();

    public final static MultiblockMachineDefinition INTEGRATED_ORE_PROCESSOR = REGISTRATE.multiblock("integrated_ore_processor", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.INTEGRATED_ORE_PROCESSOR)
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.2"))
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.3"))
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.4"))
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.5"))
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.6"))
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.7"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.integrated_ore_processor")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTBlocks.CASING_STAINLESS_CLEAN)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .pattern((definition) -> MultiBlockMachineAStructureA.INTEGRATED_ORE_PROCESSOR
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("a", Predicates.blocks(GTBlocks.CASING_HSSE_STURDY.get()))
                    .where("c", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get())
                            .setMinGlobalLimited(60)
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("b", Predicates.blocks(GTBlocks.CASING_LAMINATED_GLASS.get()))
                    .where("d", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.BlueSteel)))
                    .where("e", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_GEARBOX.get()))
                    .where("f", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where("g", Predicates.blocks(GTMachines.MUFFLER_HATCH[GTValues.ZPM].get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_clean_stainless_steel"), GTCEu.id("block/multiblock/gcym/large_maceration_tower"))
            .register();

    public final static MultiblockMachineDefinition DRAGON_EGG_COPIER = REGISTRATE.multiblock("dragon_egg_copier", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.DRAGON_EGG_COPIER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.dragon_egg_copier")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.DRAGON_STRENGTH_TRITANIUM_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureA.DRAGON_EGG_COPIER
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.DRAGON_STRENGTH_TRITANIUM_CASING.get())
                            .setMinGlobalLimited(10)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(Registries.getBlock("kubejs:magic_core")))
                    .where("d", Predicates.blocks(GTMachines.MUFFLER_HATCH[GTValues.UEV].get()))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/extreme_strength_tritanium_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition LARGE_CRACKER = REGISTRATE.multiblock("large_cracker", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.CRACKING_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.cracker.tooltip.1"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.cracker")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers::crackerOverclock)
            .appearanceBlock(GTBlocks.CASING_STAINLESS_CLEAN)
            .pattern((definition) -> MultiBlockMachineAStructureA.LARGE_CRACKER
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("a", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get())
                            .setMinGlobalLimited(200)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("b", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get()))
                    .where("c", Predicates.heatingCoils())
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed() && controller instanceof CoilWorkableElectricMultiblockMachine machine) {
                    components.add(Component.translatable("gtceu.multiblock.cracking_unit.energy", 100 - 10 * machine.getCoilTier()));
                }
            })
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_clean_stainless_steel"), GTCEu.id("block/multiblock/cracking_unit"))
            .register();

    public final static MultiblockMachineDefinition MAGE_ASSEMBLER = REGISTRATE.multiblock("mage_assembler", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.ASSEMBLER_RECIPES)
            .recipeType(GTLRecipeTypes.PRECISION_ASSEMBLER_RECIPES)
            .recipeType(GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_3.tooltip",
                    Component.translatable("gtceu.assembler"), Component.translatable("gtceu.precision_assembler"), Component.translatable("gtceu.circuit_assembler")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.IRIDIUM_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureA.MAGE_ASSEMBLER
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("a", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .setMinGlobalLimited(666)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("c", Predicates.blocks(GTBlocks.CASING_LAMINATED_GLASS.get()))
                    .where("d", Predicates.blocks(GTBlocks.FILTER_CASING.get()))
                    .where("e", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTLMaterials.HastelloyN)))
                    .where("f", Predicates.blocks(GTLBlocks.ADVANCED_ASSEMBLY_LINE_UNIT.get()))
                    .where("g", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.TungstenSteel)))
                    .where("h", Predicates.blocks(Registries.getBlock("kubejs:spacetime_assembly_line_unit")))
                    .where("i", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where("-", Predicates.abilities(PartAbility.MUFFLER))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/iridium_casing"), GTCEu.id("block/multiblock/gcym/large_assembler"))
            .register();

    public final static MultiblockMachineDefinition LARGE_GREENHOUSE = REGISTRATE.multiblock("large_greenhouse", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.GREENHOUSE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.large_greenhouse.tooltip.0"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.greenhouse")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTBlocks.CASING_STAINLESS_CLEAN)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .pattern((definition) -> MultiBlockMachineAStructureA.LARGE_GREENHOUSE
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get()))
                    .where("c", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.StainlessSteel)))
                    .where("d", Predicates.blocks(Blocks.PACKED_MUD))
                    .where("e", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where("f", Predicates.blocks(GTBlocks.CASING_GRATE.get()))
                    .where("a", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get())
                            .setMinGlobalLimited(180)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_clean_stainless_steel"), GTCEu.id("block/multiblock/implosion_compressor"))
            .register();

    public final static MultiblockMachineDefinition COOLING_TOWER = REGISTRATE.multiblock("cooling_tower", WorkableElectricParallelHatchMultipleRecipesMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTRecipeTypes.VACUUM_RECIPES)
            .recipeType(GTLRecipeTypes.PLASMA_CONDENSER_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.vacuum_freezer"), Component.translatable("gtceu.plasma_condenser")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTBlocks.CASING_ALUMINIUM_FROSTPROOF)
            .pattern(definition -> MultiBlockMachineAStructureB.COOLING_TOWER
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("C", Predicates.blocks(GTBlocks.CASING_ALUMINIUM_FROSTPROOF.get()))
                    .where("W", Predicates.blocks(GTBlocks.CASING_ALUMINIUM_FROSTPROOF.get())
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("D", Predicates.blocks(GTBlocks.MACHINE_CASING_ZPM.get()))
                    .where("E", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_GEARBOX.get()))
                    .where("F", Predicates.blocks(GTBlocks.CASING_INVAR_HEATPROOF.get()))
                    .where("G", Predicates.blocks(GTBlocks.CASING_LAMINATED_GLASS.get()))
                    .where("H", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get()))
                    .where("I", Predicates.fluids(Fluids.WATER))
                    .where("J", Predicates.blocks(GCyMBlocks.CASING_WATERTIGHT.get()))
                    .where("K", Predicates.blocks(GTBlocks.CASING_EXTREME_ENGINE_INTAKE.get()))
                    .where("L", Predicates.blocks(GCyMBlocks.HEAT_VENT.get()))
                    .where("M", Predicates.blocks(GTBlocks.MACHINE_CASING_LuV.get()))
                    .where("N", Predicates.blocks(GTBlocks.CASING_TITANIUM_GEARBOX.get()))
                    .where("O", Predicates.blocks(GTBlocks.MACHINE_CASING_IV.get()))
                    .where("P", Predicates.blocks(GTBlocks.CASING_STEEL_GEARBOX.get()))
                    .where("Q", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where("R", Predicates.blocks(GTBlocks.MACHINE_CASING_UV.get()))
                    .where("S", Predicates.blocks(Registries.getBlock("kubejs:laser_cooling_casing")))
                    .where("T", Predicates.blocks(Registries.getBlock("kubejs:neutronium_pipe_casing")))
                    .where("U", Predicates.blocks(GTBlocks.HERMETIC_CASING_UV.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_frost_proof"), GTCEu.id("block/multiblock/vacuum_freezer"))
            .register();

    public final static MultiblockMachineDefinition MEGA_DISTILLERY = REGISTRATE.multiblock("mega_distillery", WorkableElectricParallelHatchMultipleRecipesMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTRecipeTypes.DISTILLERY_RECIPES)
            .recipeType(GTRecipeTypes.DISTILLATION_RECIPES)
            .recipeType(GTRecipeTypes.EVAPORATION_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_3.tooltip",
                    Component.translatable("gtceu.distillation_tower"), Component.translatable("gtceu.evaporation"), Component.translatable("gtceu.distillery")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTBlocks.CASING_STAINLESS_CLEAN)
            .pattern(definition -> MultiBlockMachineAStructureC.MEGA_DISTILLERY
                    .where("b", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get())
                            .setMinGlobalLimited(600)
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("a", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Trinium)))
                    .where("c", Predicates.blocks(GCyMBlocks.CASING_WATERTIGHT.get()))
                    .where("d", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get()))
                    .where("e", Predicates.blocks(GTBlocks.COIL_TRITANIUM.get()))
                    .where("f", Predicates.blocks(GTLBlocks.HERMETIC_CASING_UEV.get()))
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_clean_stainless_steel"), GTCEu.id("block/multiblock/gcym/large_distillery"))
            .register();

    public final static MultiblockMachineDefinition SUPERCONDUCTING_ELECTROMAGNETISM = REGISTRATE.multiblock("superconducting_electromagnetism", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.POLARIZER_RECIPES)
            .recipeType(GTRecipeTypes.ELECTROMAGNETIC_SEPARATOR_RECIPES)
            .recipeType(GTRecipeTypes.ELECTROLYZER_RECIPES)
            .recipeType(GTLRecipeTypes.LIGHTNING_PROCESSOR_RECIPES)
            .recipeType(GTRecipeTypes.ARC_FURNACE_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_5.tooltip",
                    Component.translatable("gtceu.polarizer"), Component.translatable("gtceu.electromagnetic_separator"), Component.translatable("gtceu.electrolyzer"), Component.translatable("gtceu.lightning_processor"), Component.translatable("gtceu.arc_furnace")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.LAFIUM_MECHANICAL_CASING)
            .pattern(definition -> MultiBlockMachineAStructureC.SUPERCONDUCTING_ELECTROMAGNETISM
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.LAFIUM_MECHANICAL_CASING.get()))
                    .where("B", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get()))
                    .where("C", Predicates.blocks(GTLBlocks.IMPROVED_SUPERCONDUCTOR_COIL.get()))
                    .where("D", Predicates.blocks(GCyMBlocks.MOLYBDENUM_DISILICIDE_COIL_BLOCK.get()))
                    .where("E", Predicates.blocks(GCyMBlocks.ELECTROLYTIC_CELL.get()))
                    .where("F", Predicates.blocks(GTLBlocks.LAFIUM_MECHANICAL_CASING.get())
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("H", Predicates.blocks(Registries.getBlock("kubejs:accelerated_pipeline")))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/lafium_mechanical_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition CRYSTALLINE_INFINITY = REGISTRATE.multiblock("crystalline_infinity", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTRecipeTypes.AUTOCLAVE_RECIPES)
            .recipeType(GTRecipeTypes.CHEMICAL_BATH_RECIPES)
            .recipeType(GTRecipeTypes.ORE_WASHER_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_3.tooltip",
                    Component.translatable("gtceu.autoclave"), Component.translatable("gtceu.chemical_bath"), Component.translatable("gtceu.ore_washer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.EXTREME_STRENGTH_TRITANIUM_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureD.CRYSTALLINE_INFINITY
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.FUSION_GLASS.get()))
                    .where("B", Predicates.blocks(GTLBlocks.EXTREME_STRENGTH_TRITANIUM_CASING.get())
                            .setMinGlobalLimited(1200)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("C", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("D", Predicates.blocks(Registries.getBlock("kubejs:molecular_coil")))
                    .where("E", Predicates.blocks(Registries.getBlock("kubejs:hollow_casing")))
                    .where("F", Predicates.blocks(GTLBlocks.HYPER_CORE.get()))
                    .where("G", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Tritanium)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/extreme_strength_tritanium_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition STAR_ULTIMATE_MATERIAL_FORGE_FACTORY = REGISTRATE.multiblock("star_ultimate_material_forge_factory", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.ULTIMATE_MATERIAL_FORGE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.star_ultimate_material_forge_factory.tooltip.0"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.ultimate_material_forge")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, 10000, false).getFirst(), GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.MOLECULAR_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureC.STAR_ULTIMATE_MATERIAL_FORGE_FACTORY
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("I", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("B", Predicates.blocks(Registries.getBlock("kubejs:molecular_coil")))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:containment_field_generator")))
                    .where("D", Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get()))
                    .where("E", Predicates.blocks(Registries.getBlock("kubejs:hollow_casing")))
                    .where("F", Predicates.blocks(Registries.getBlock("kubejs:force_field_glass")))
                    .where("G", Predicates.blocks(GTLBlocks.ULTIMATE_STELLAR_CONTAINMENT_CASING.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal("10000").withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                }
            })
            .workableCasingRenderer(GTLCore.id("block/molecular_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition STEAM_PISTON_HAMMER = REGISTRATE.multiblock("steam_piston_hammer", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 8))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.FORGE_HAMMER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.forge_hammer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.STEAM_OC)
            .addOutputLimit(ItemRecipeCapability.CAP, 1)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.STEAM_PISTON_HAMMER
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where("B", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.block, GTMaterials.WroughtIron)))
                    .where("C", Predicates.blocks(Blocks.STICKY_PISTON))
                    .where("D", Predicates.abilities(PartAbility.STEAM))
                    .where("E", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get()))
                    .where("#", Predicates.air())
                    .where(" ", Predicates.any())
                    .build())
            .shapeInfo(definition -> MultiblockShapeInfo.builder()
                    .aisle("IAO", " S ", "   ", "   ", "   ")
                    .aisle("ABA", "E E", "EBE", "ECE", "EDE")
                    .aisle("AAA", " E ", "   ", "   ", "   ")
                    .where('S', definition, Direction.NORTH)
                    .where('A', GTBlocks.CASING_BRONZE_BRICKS.get())
                    .where('E', GTBlocks.CASING_BRONZE_BRICKS.get())
                    .where('I', GTMachines.STEAM_IMPORT_BUS, Direction.NORTH)
                    .where('O', GTMachines.STEAM_EXPORT_BUS, Direction.NORTH)
                    .where('D', GTMachines.STEAM_HATCH, Direction.NORTH)
                    .where('B', ChemicalHelper.getBlock(TagPrefix.block, GTMaterials.WroughtIron))
                    .where('C', Blocks.STICKY_PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.DOWN))
                    .where(' ', Blocks.AIR)
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/machines/forge_hammer"))
            .register();

    public final static MultiblockMachineDefinition STEAM_PRESSOR = REGISTRATE.multiblock("steam_pressor", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 8))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.COMPRESSOR_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.compressor")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.STEAM_OC)
            .addOutputLimit(ItemRecipeCapability.CAP, 1)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.STEAM_PRESSOR
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("X", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1)))
                    .where("#", Predicates.air())
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_pressor"))
            .register();

    public final static MultiblockMachineDefinition STEAM_FOUNDRY = REGISTRATE.multiblock("steam_foundry", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 8))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.ALLOY_SMELTER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.alloy_smelter")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.STEAM_OC)
            .addOutputLimit(ItemRecipeCapability.CAP, 1)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.STEAM_FOUNDRY
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("F", Predicates.blocks(GTBlocks.FIREBOX_BRONZE.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1)))
                    .where("X", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where("#", Predicates.air())
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/machines/alloy_smelter"))
            .register();

    public final static MultiblockMachineDefinition LARGE_STEAM_MACERATOR = REGISTRATE.multiblock("large_steam_macerator", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 64))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.MACERATOR_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.macerator")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.LARGE_STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .addOutputLimit(ItemRecipeCapability.CAP, 1)
            .pattern(definition -> MultiBlockMachineAStructureC.LARGE_STEAM_MACERATOR
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("B", Predicates.blocks(GTBlocks.CASING_BRONZE_GEARBOX.get()))
                    .where("C", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where("D", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.block, GTMaterials.Steel)))
                    .where("E", Predicates.abilities(PartAbility.MUFFLER))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(3)))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_grinder"))
            .register();

    public final static MultiblockMachineDefinition LARGE_STEAM_CIRCUIT_ASSEMBLER = REGISTRATE.multiblock("large_steam_circuit_assembler", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 64))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.circuit_assembler")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.LARGE_STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.LARGE_STEAM_CIRCUIT_ASSEMBLER
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1)))
                    .where("B", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:steam_assembly_block")))
                    .where("D", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_circuit_assembler"))
            .register();

    public final static MultiblockMachineDefinition LARGE_STEAM_MIXER = REGISTRATE.multiblock("large_steam_mixer", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 64))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.MIXER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.mixer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.LARGE_STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.LARGE_STEAM_MIXER
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("B", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Bronze)))
                    .where("C", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where("D", Predicates.blocks(GTBlocks.CASING_BRONZE_GEARBOX.get()))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_mixer"))
            .register();

    public final static MultiblockMachineDefinition STEAM_MIXER = REGISTRATE.multiblock("steam_mixer", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 8))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.MIXER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.mixer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.STEAM_MIXER
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where("B", Predicates.air())
                    .where("C", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where("D", Predicates.blocks(GTBlocks.CASING_BRONZE_GEARBOX.get()))
                    .where("E", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.blocks(Blocks.GLASS))
                            .or(Predicates.air()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_mixer"))
            .register();

    public final static MultiblockMachineDefinition LARGE_STEAM_CENTRIFUGE = REGISTRATE.multiblock("large_steam_centrifuge", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 64))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.CENTRIFUGE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.centrifuge")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.LARGE_STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.LARGE_STEAM_CENTRIFUGE
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("B", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Bronze)))
                    .where("C", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where("E", Predicates.blocks(GTBlocks.CASING_BRONZE_GEARBOX.get()))
                    .where("F", Predicates.abilities(PartAbility.MUFFLER))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(4)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_centrifuge"))
            .register();

    public final static MultiblockMachineDefinition LARGE_STEAM_THERMAL_CENTRIFUGE = REGISTRATE.multiblock("large_steam_thermal_centrifuge", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 64))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.THERMAL_CENTRIFUGE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.thermal_centrifuge")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.LARGE_STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.LARGE_STEAM_THERMAL_CENTRIFUGE
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("C", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Bronze)))
                    .where("D", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where("A", Predicates.blocks(GTBlocks.FIREBOX_BRONZE.get()))
                    .where("E", Predicates.abilities(PartAbility.MUFFLER))
                    .where("B", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(3)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_thermal_centrifuge"))
            .register();

    public final static MultiblockMachineDefinition LARGE_STEAM_BATH = REGISTRATE.multiblock("large_steam_bath", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 64))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.CHEMICAL_BATH_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.chemical_bath")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.LARGE_STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.LARGE_STEAM_BATH
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("B", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Bronze)))
                    .where("C", Predicates.blocks(Blocks.GLASS))
                    .where("D", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.block, GTMaterials.Potin)))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(3)))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/machines/chemical_bath"))
            .register();

    public final static MultiblockMachineDefinition STEAM_BATH = REGISTRATE.multiblock("steam_bath", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 8))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.CHEMICAL_BATH_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.chemical_bath")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.STEAM_BATH
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where("B", Predicates.air())
                    .where("C", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.block, GTMaterials.Potin)))
                    .where("D", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.blocks(Blocks.GLASS))
                            .or(Predicates.air()))
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/machines/chemical_bath"))
            .register();

    public final static MultiblockMachineDefinition LARGE_STEAM_ORE_WASHER = REGISTRATE.multiblock("large_steam_ore_washer", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 64))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.ORE_WASHER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.ore_washer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.LARGE_STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.LARGE_STEAM_ORE_WASHER
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("B", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where("C", Predicates.blocks(Blocks.GLASS))
                    .where("D", Predicates.blocks(GTBlocks.CASING_BRONZE_GEARBOX.get()))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(3)))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_ore_washer"))
            .register();

    public final static MultiblockMachineDefinition steam_ore_washer = REGISTRATE.multiblock("steam_ore_washer", (holder) -> new LargeSteamParallelMultiblockMachine(holder, 8))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.ORE_WASHER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.ore_washer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTLRecipeModifiers.STEAM_OC)
            .appearanceBlock(GTBlocks.CASING_BRONZE_BRICKS)
            .pattern(definition -> MultiBlockMachineAStructureC.steam_ore_washer
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where("C", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Bronze)))
                    .where("D", Predicates.blocks(GTBlocks.CASING_BRONZE_GEARBOX.get()))
                    .where("E", Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_ore_washer"))
            .register();

    public final static MultiblockMachineDefinition DIMENSIONALLY_TRANSCENDENT_DIRT_FORGE = REGISTRATE.multiblock("dimensionally_transcendent_dirt_forge", NoEnergyMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.PRIMITIVE_BLAST_FURNACE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.dimensionally_transcendent_dirt_forge.tooltip.0"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.primitive_blast_furnace")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier((machine, recipe, params, result) -> {
                GTRecipe recipe1 = recipe.copy();
                recipe1.duration = 0;
                recipe1 = GTRecipeModifiers.fastParallel(machine, recipe1, 524288, false).getFirst();
                return recipe1;
            })
            .appearanceBlock(GTBlocks.CASING_PRIMITIVE_BRICKS)
            .pattern(definition -> GTLMachines.DTPF.where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("e", Predicates.blocks(GTBlocks.CASING_PRIMITIVE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(2)))
                    .where("b", Predicates.blocks(Blocks.BRICKS))
                    .where("C", Predicates.blocks(Blocks.DIRT))
                    .where("d", Predicates.blocks(Blocks.STONE_BRICKS))
                    .where("s", Predicates.blocks(GTBlocks.CASING_PRIMITIVE_BRICKS.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal("524288").withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                }
            })
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_primitive_bricks"), GTCEu.id("block/multiblock/primitive_blast_furnace"))
            .register();

    public final static MultiblockMachineDefinition HOLY_SEPARATOR = REGISTRATE.multiblock("holy_separator", WorkableElectricParallelHatchMultipleRecipesMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .recipeType(GTRecipeTypes.CUTTER_RECIPES)
            .recipeType(GTRecipeTypes.LATHE_RECIPES)
            .recipeType(GTRecipeTypes.MACERATOR_RECIPES)
            .recipeType(GTRecipeTypes.CENTRIFUGE_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_4.tooltip",
                    Component.translatable("gtceu.cutter"), Component.translatable("gtceu.lathe"), Component.translatable("gtceu.macerator"), Component.translatable("gtceu.centrifuge")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTLBlocks.IRIDIUM_CASING)
            .pattern(definition -> MultiBlockMachineAStructureC.HOLY_SEPARATOR
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .setMinGlobalLimited(800)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("c", Predicates.blocks(GTLBlocks.HYPER_MECHANICAL_CASING.get()))
                    .where("d", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("e", Predicates.blocks(GTBlocks.FUSION_GLASS.get()))
                    .where("f", Predicates.blocks(Registries.getBlock("kubejs:hollow_casing")))
                    .where("g", Predicates.blocks(Registries.getBlock("kubejs:containment_field_generator")))
                    .where("h", Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/iridium_casing"), GTCEu.id("block/multiblock/gcym/large_cutter"))
            .register();

    public final static MultiblockMachineDefinition PETROCHEMICAL_PLANT = REGISTRATE.multiblock("petrochemical_plant", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.PETROCHEMICAL_PLANT_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.cracker.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.petrochemical_plant")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers::crackerOverclock)
            .appearanceBlock(GTBlocks.CASING_STAINLESS_CLEAN)
            .pattern((definition) -> MultiBlockMachineAStructureC.PETROCHEMICAL_PLANT
                    .where("E", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("B", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get()))
                    .where("C", Predicates.blocks(GCyMBlocks.HEAT_VENT.get()))
                    .where("D", Predicates.heatingCoils())
                    .where("F", Predicates.blocks(GTBlocks.HERMETIC_CASING_HV.get()))
                    .where("G", Predicates.blocks(GTBlocks.CASING_STEEL_PIPE.get()))
                    .where("H", Predicates.abilities(PartAbility.MUFFLER))
                    .where("I", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where("K", Predicates.blocks(GTBlocks.CASING_PTFE_INERT.get()))
                    .where("M", Predicates.blocks(GTBlocks.HERMETIC_CASING_EV.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed() && controller instanceof CoilWorkableElectricMultiblockMachine machine) {
                    components.add(Component.translatable("gtceu.multiblock.cracking_unit.energy", 100 - 10 * machine.getCoilTier()));
                }
            })
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_clean_stainless_steel"), GTCEu.id("block/multiblock/implosion_compressor"))
            .register();

    public final static MultiblockMachineDefinition LARGE_PYROLYSE_OVEN = REGISTRATE.multiblock("large_pyrolyse_oven", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.PYROLYSE_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.pyrolyse_oven.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.pyrolyse_oven")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers::pyrolyseOvenOverclock)
            .appearanceBlock(GTBlocks.CASING_STAINLESS_CLEAN)
            .pattern((definition) -> MultiBlockMachineAStructureC.LARGE_PYROLYSE_OVEN
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get())
                            .setMinGlobalLimited(60)
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("B", Predicates.heatingCoils())
                    .where("C", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.TungstenCarbide)))
                    .where(" ", Predicates.air())
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed() && controller instanceof CoilWorkableElectricMultiblockMachine machine) {
                    components.add(Component.translatable("gtceu.multiblock.pyrolyse_oven.speed", machine.getCoilTier() == 0 ? 75 : 50 * (machine.getCoilTier() + 1)));
                }
            })
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_clean_stainless_steel"), GTCEu.id("block/multiblock/pyrolyse_oven"))
            .register();

    public final static MultiblockMachineDefinition LARGE_ROCK_CRUSHER = REGISTRATE.multiblock("large_rock_crusher", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.ROCK_BREAKER_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.eut_multiplier.tooltip", 0.8))
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.6))
            .tooltips(Component.translatable("gtceu.machine.large_rock_crusher.tooltip.0"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.rock_breaker")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTLRecipeModifiers.GCYM_REDUCTION, GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GCyMBlocks.CASING_SECURE_MACERATION)
            .pattern((definition) -> MultiBlockMachineAStructureC.LARGE_ROCK_CRUSHER
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GCyMBlocks.CASING_SECURE_MACERATION.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS_1X).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("B", Predicates.blocks(GCyMBlocks.CRUSHING_WHEELS.get()))
                    .where("C", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.MaragingSteel300)))
                    .where(" ", Predicates.air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/gcym/secure_maceration_casing"), GTCEu.id("block/machines/rock_crusher"))
            .register();

    public final static MultiblockMachineDefinition FIELD_EXTRUDER_FACTORY = REGISTRATE.multiblock("field_extruder_factory", (holder) -> new WorkableElectricParallelHatchMultipleRecipesMachine(holder))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.EXTRUDER_RECIPES)
            .recipeType(GTRecipeTypes.COMPRESSOR_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.extruder"), Component.translatable("gtceu.compressor")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTLBlocks.IRIDIUM_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureC.FIELD_EXTRUDER_FACTORY
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(24))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(24))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("B", Predicates.blocks(GTLBlocks.HYPER_MECHANICAL_CASING.get()))
                    .where("C", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Naquadria)))
                    .where("G", Predicates.blocks(GTBlocks.CLEANROOM_GLASS.get()))
                    .where("D", Predicates.blocks(Registries.getBlock("kubejs:hollow_casing")))
                    .where("E", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("F", Predicates.blocks(Registries.getBlock("kubejs:containment_field_generator")))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/iridium_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition CHEMICAL_DISTORT = REGISTRATE.multiblock("chemical_distort", (holder) -> new CoilWorkableElectricMultiblockMachine(holder))
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.DISTORT_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.chemical_distort.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.chemical_distort.tooltip.1"))
            .tooltips(Component.translatable("gtceu.multiblock.only.laser.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.distort")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, Math.max(1, (((CoilWorkableElectricMultiblockMachine) machine).getCoilType().getCoilTemperature() - recipe.data.getInt("ebf_temp")) / 100 * 4), false).getFirst(), GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.CASING_PTFE_INERT)
            .pattern((definition) -> MultiBlockMachineAStructureC.CHEMICAL_DISTORT
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_PTFE_INERT.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("B", Predicates.blocks(GTBlocks.CASING_PTFE_INERT.get()))
                    .where("C", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where("D", Predicates.blocks(GTBlocks.CASING_PTFE_INERT.get())
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setPreviewCount(1)))
                    .where("E", Predicates.heatingCoils())
                    .where(" ", Predicates.any())
                    .build())
            .beforeWorking((machine, recipe) -> {
                if (recipe.data.getInt("ebf_temp") <= ((CoilWorkableElectricMultiblockMachine) machine).getCoilType().getCoilTemperature()) {
                    return true;
                }
                machine.getRecipeLogic().interruptRecipe();
                return false;
            })
            .additionalDisplay(GTLMachines.TEMPERATURE)
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_inert_ptfe"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition DIMENSIONAL_FOCUS_ENGRAVING_ARRAY = REGISTRATE.multiblock("dimensional_focus_engraving_array", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.DIMENSIONAL_FOCUS_ENGRAVING_ARRAY_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.coil_parallel"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.dimensional_focus_engraving_array")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTLRecipeModifiers.COIL_PARALLEL, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GCyMBlocks.CASING_LASER_SAFE_ENGRAVING)
            .pattern((definition) -> MultiBlockMachineAStructureE.DIMENSIONAL_FOCUS_ENGRAVING_ARRAY
                    .where("I", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get()))
                    .where("B", Predicates.blocks(GTBlocks.FUSION_GLASS.get()))
                    .where("C", Predicates.blocks(GTLBlocks.DIMENSION_INJECTION_CASING.get()))
                    .where("D", Predicates.blocks(Registries.getBlock("kubejs:molecular_coil")))
                    .where("E", Predicates.blocks(Registries.getBlock("kubejs:containment_field_generator")))
                    .where("F", Predicates.blocks(GTLBlocks.IMPROVED_SUPERCONDUCTOR_COIL.get()))
                    .where("G", Predicates.blocks(GCyMBlocks.CASING_LASER_SAFE_ENGRAVING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(24))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.OPTICAL_DATA_RECEPTION).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_RECEPTION).setExactLimit(1)))
                    .where("H", Predicates.heatingCoils())
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.COIL_PARALLEL)
            .workableCasingRenderer(GTCEu.id("block/casings/gcym/laser_safe_engraving_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition MEGA_WIREMILL = REGISTRATE.multiblock("mega_wiremill", CoilWorkableElectricMultipleRecipesMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.WIREMILL_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.coil_parallel"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.wiremill")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTLBlocks.OXIDATION_RESISTANT_HASTELLOY_N_MECHANICAL_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureC.MEGA_WIREMILL
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.OXIDATION_RESISTANT_HASTELLOY_N_MECHANICAL_CASING.get())
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1)))
                    .where("B", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get()))
                    .where("C", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get()))
                    .where("D", Predicates.blocks(GTLBlocks.HYPER_MECHANICAL_CASING.get()))
                    .where("E", Predicates.blocks(Registries.getBlock("kubejs:hollow_casing")))
                    .where("F", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Naquadria)))
                    .where("G", Predicates.blocks(GTBlocks.CASING_INVAR_HEATPROOF.get()))
                    .where("H", Predicates.heatingCoils())
                    .where("I", Predicates.blocks(GTBlocks.CASING_INVAR_HEATPROOF.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.MULTIPLERECIPES_COIL_PARALLEL)
            .workableCasingRenderer(GTLCore.id("block/casings/oxidation_resistant_hastelloy_n_mechanical_casing"), GTCEu.id("block/multiblock/gcym/large_wiremill"))
            .register();

    public final static MultiblockMachineDefinition MEGA_PRESSER = REGISTRATE.multiblock("mega_presser", CoilWorkableElectricMultipleRecipesMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.BENDER_RECIPES)
            .recipeType(GTRecipeTypes.FORGE_HAMMER_RECIPES)
            .recipeType(GTRecipeTypes.FORMING_PRESS_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.coil_parallel"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_3.tooltip",
                    Component.translatable("gtceu.bender"), Component.translatable("gtceu.forge_hammer"), Component.translatable("gtceu.forming_press")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTLBlocks.MOLECULAR_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureC.MEGA_PRESSER
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("B", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get()))
                    .where("C", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get())
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(16))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(16)))
                    .where("D", Predicates.blocks(GTBlocks.SUPERCONDUCTING_COIL.get()))
                    .where("E", Predicates.blocks(GTBlocks.CASING_ASSEMBLY_LINE.get()))
                    .where("F", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get()))
                    .where("G", Predicates.heatingCoils())
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.MULTIPLERECIPES_COIL_PARALLEL)
            .workableCasingRenderer(GTLCore.id("block/molecular_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition MEGA_EXTRACTOR = REGISTRATE.multiblock("mega_extractor", CoilWorkableElectricMultipleRecipesMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.EXTRACTOR_RECIPES)
            .recipeType(GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.coil_parallel"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.extractor"), Component.translatable("gtceu.fluid_solidifier")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTLBlocks.HYPER_MECHANICAL_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureC.MEGA_EXTRACTOR
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.HYPER_MECHANICAL_CASING.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("B", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.NaquadahAlloy)))
                    .where("C", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get()))
                    .where("D", Predicates.heatingCoils())
                    .where("E", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("F", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.MULTIPLERECIPES_COIL_PARALLEL)
            .workableCasingRenderer(GTLCore.id("block/casings/hyper_mechanical_casing"), GTCEu.id("block/multiblock/gcym/large_extractor"))
            .register();

    public final static MultiblockMachineDefinition MEGA_CANNER = REGISTRATE.multiblock("mega_canner", (holder) -> new WorkableElectricParallelHatchMultipleRecipesMachine(holder))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.CANNER_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.canner")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTLBlocks.LAFIUM_MECHANICAL_CASING)
            .pattern(definition -> MultiBlockMachineAStructureD.MEGA_CANNER
                    .where("A", Predicates.blocks(GTLBlocks.LAFIUM_MECHANICAL_CASING.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1)))
                    .where("B", Predicates.blocks(GCyMBlocks.CASING_STRESS_PROOF.get()))
                    .where("C", Predicates.blocks(GTBlocks.SUPERCONDUCTING_COIL.get()))
                    .where("D", Predicates.blocks(GTLBlocks.ENHANCE_HYPER_MECHANICAL_CASING.get()))
                    .where("E", Predicates.blocks(GTBlocks.MACHINE_CASING_UV.get()))
                    .where("F", Predicates.blocks(GTBlocks.CASING_LAMINATED_GLASS.get()))
                    .where("G", Predicates.controller(Predicates.blocks(definition.get())))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/lafium_mechanical_casing"), GTCEu.id("block/multiblock/gcym/large_extractor"))
            .register();

    public final static MultiblockMachineDefinition DISASSEMBLY = REGISTRATE.multiblock("disassembly", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.DISASSEMBLY_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.disassembly")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.PROCESS_MACHINE_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureD.DISASSEMBLY
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.PROCESS_MACHINE_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(1)))
                    .where("B", Predicates.blocks(GTBlocks.CASING_ASSEMBLY_LINE.get()))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/process_machine_casing"), GTCEu.id("block/multiblock/assembly_line"))
            .register();

    public final static MultiblockMachineDefinition ELEMENT_COPYING = REGISTRATE.multiblock("element_copying", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.ELEMENT_COPYING_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GCY"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.element_copying")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.MOLECULAR_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureD.ELEMENT_COPYING
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(5))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2)))
                    .where("B", Predicates.blocks(GTLBlocks.HYPER_CORE.get()))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:molecular_coil")))
                    .where("D", Predicates.blocks(Registries.getBlock("kubejs:hollow_casing")))
                    .where("E", Predicates.blocks(Registries.getBlock("kubejs:containment_field_generator")))
                    .where("F", Predicates.blocks(GCyMBlocks.ELECTROLYTIC_CELL.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/molecular_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition ATOMIC_ENERGY_EXCITATION_PLANT = REGISTRATE.multiblock("atomic_energy_excitation_plant", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .recipeType(GTLRecipeTypes.FUEL_REFINING_RECIPES)
            .recipeType(GTLRecipeTypes.ATOMIC_ENERGY_EXCITATION_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.atomic_energy_excitation"), Component.translatable("gtceu.fuel_refining")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureD.ATOMIC_ENERGY_EXCITATION_PLANT
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.DEGENERATE_RHENIUM_CONSTRAINED_CASING.get()))
                    .where("B", Predicates.blocks(GTLBlocks.RHENIUM_REINFORCED_ENERGY_GLASS.get()))
                    .where("C", Predicates.heatingCoils())
                    .where("D", Predicates.blocks(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get())
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1)))
                    .where("E", Predicates.blocks(GTLBlocks.DIMENSION_INJECTION_CASING.get()))
                    .where("F", Predicates.blocks(Registries.getBlock("kubejs:accelerated_pipeline")))
                    .where("G", Predicates.blocks(Registries.getBlock("kubejs:restraint_device")))
                    .where("H", Predicates.blocks(Registries.getBlock("kubejs:neutronium_pipe_casing")))
                    .where("I", Predicates.blocks(Registries.getBlock("kubejs:containment_field_generator")))
                    .where("J", Predicates.blocks(Registries.getBlock("kubejs:aggregatione_core")))
                    .where(" ", Predicates.any())
                    .where("#", Predicates.air())
                    .build())
            .beforeWorking((machine, recipe) -> {
                if (recipe.data.getInt("ebf_temp") <= ((CoilWorkableElectricMultiblockMachine) machine).getCoilType().getCoilTemperature()) {
                    return true;
                }
                machine.getRecipeLogic().interruptRecipe();
                return false;
            })
            .additionalDisplay(GTLMachines.TEMPERATURE)
            .workableCasingRenderer(GTLCore.id("block/casings/dimensionally_transcendent_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition FLOTATION_CELL_REGULATOR = REGISTRATE.multiblock("flotation_cell_regulator", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.FLOTATING_BENEFICIATION_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.flotation_cell_regulator.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GT++"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.flotating_beneficiation")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(() -> Registries.getBlock("kubejs:hastelloy_n_75_casing"))
            .pattern((definition) -> MultiBlockMachineAStructureD.FLOTATION_CELL_REGULATOR
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("D", Predicates.blocks(Registries.getBlock("kubejs:hastelloy_n_75_gearbox")))
                    .where("B", Predicates.blocks(Registries.getBlock("kubejs:flotation_cell")))
                    .where("A", Predicates.blocks(Registries.getBlock("kubejs:hastelloy_n_75_casing"))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:hastelloy_n_75_pipe")))
                    .where("E", Predicates.blocks(Registries.getBlock("kubejs:hastelloy_n_75_casing")))
                    .where(" ", Predicates.any())
                    .where("#", Predicates.air())
                    .build())
            .workableCasingRenderer(new ResourceLocation("kubejs:block/hastelloy_n_75_casing"), GTCEu.id("block/multiblock/gcym/large_chemical_bath"))
            .register();

    public final static MultiblockMachineDefinition VACUUM_DRYING_FURNACE = REGISTRATE.multiblock("vacuum_drying_furnace", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.VACUUM_DRYING_RECIPES)
            .recipeType(GTLRecipeTypes.DEHYDRATOR_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.vacuum_drying_furnace.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.a"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.2"))
            .tooltips(Component.translatable("gtceu.machine.vacuum_drying_furnace.tooltip.1"))
            .tooltips(Component.translatable("gtceu.multiblock.coil_parallel"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GT++"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.vacuum_drying"), Component.translatable("gtceu.dehydrator")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier((machine, recipe, params, result) -> {
                if (machine instanceof CoilWorkableElectricMultiblockMachine coilMachine && coilMachine.getRecipeType() == GTRecipeTypes.get("dehydrator")) {
                    GTRecipe recipe1 = GTRecipeModifiers.accurateParallel(coilMachine, recipe, (int) Math.min(2147483647, Math.pow(2, (coilMachine.getCoilType().getCoilTemperature() / 900))), false).getFirst();
                    if (recipe1 != null) {
                        return RecipeHelper.applyOverclock(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK, recipe1, coilMachine.getOverclockVoltage(), params, result);
                    }
                } else {
                    return GTRecipeModifiers.ebfOverclock(machine, recipe, params, result);
                }
                return null;
            })
            .appearanceBlock(() -> Registries.getBlock("kubejs:red_steel_casing"))
            .pattern((definition) -> MultiBlockMachineAStructureD.VACUUM_DRYING_FURNACE
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("C", Predicates.abilities(PartAbility.MUFFLER).setExactLimit(1))
                    .where("A", Predicates.blocks(Registries.getBlock("kubejs:red_steel_casing"))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where("B", Predicates.heatingCoils())
                    .where(" ", Predicates.air())
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller instanceof CoilWorkableElectricMultiblockMachine coilMachine && controller.isFormed()) {
                    if (coilMachine.getRecipeType() == GTLRecipeTypes.DEHYDRATOR_RECIPES) {
                        components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal(FormattingUtil.formatNumbers(Math.min(2147483647, (int) Math.pow(2, ((double) coilMachine.getCoilType().getCoilTemperature() / 900))))).withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                    }
                    components.add(Component.translatable("gtceu.multiblock.blast_furnace.max_temperature",
                            Component.translatable(FormattingUtil.formatNumbers(coilMachine.getCoilType().getCoilTemperature() + 100L * Math.max(0, coilMachine.getTier() - GTValues.MV)) + "K")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED))));
                }
            })
            .workableCasingRenderer(new ResourceLocation("kubejs:block/red_steel_casing_top"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition MEGA_FLUID_HEATER = REGISTRATE.multiblock("mega_fluid_heater", CoilWorkableElectricMultipleRecipesMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.FLUID_HEATER_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.mega_fluid_heater"))
            .tooltips(Component.translatable("gtceu.multiblock.coil_parallel"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.fluid_heater")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTLBlocks.IRIDIUM_CASING)
            .pattern(definition -> MultiBlockMachineAStructureD.MEGA_FLUID_HEATER
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("G", Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get()))
                    .where("A", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("H", Predicates.heatingCoils())
                    .where("D", Predicates.blocks(GTLBlocks.ENHANCE_HYPER_MECHANICAL_CASING.get()))
                    .where("B", Predicates.blocks(GTLBlocks.RHENIUM_REINFORCED_ENERGY_GLASS.get()))
                    .where("C", Predicates.blocks(GTLBlocks.ANTIFREEZE_HEATPROOF_MACHINE_CASING.get()))
                    .where("F", Predicates.blocks(GTBlocks.HERMETIC_CASING_UHV.get()))
                    .where("E", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay(GTLMachines.MULTIPLERECIPES_COIL_PARALLEL)
            .workableCasingRenderer(GTLCore.id("block/casings/iridium_casing"), GTCEu.id("block/machines/fluid_heater"))
            .register();

    public final static MultiblockMachineDefinition ELECTRIC_IMPLOSION_COMPRESSOR = REGISTRATE
            .multiblock("electric_implosion_compressor", WorkableElectricMultiblockMachine::new)
            .langValue("Electric Implosion Compressor")
            .tooltips(Component.translatable("gtceu.machine.eut_multiplier.tooltip", 0.8))
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.6))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GCY"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.electric_implosion_compressor")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.ELECTRIC_IMPLOSION_COMPRESSOR_RECIPES)
            .recipeModifiers(GTLRecipeModifiers.GCYM_REDUCTION,
                    GTRecipeModifiers.PARALLEL_HATCH,
                    GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST)
            .pattern(definition -> MultiBlockMachineAStructureD.ELECTRIC_IMPLOSION_COMPRESSOR
                    .where('S', controller(blocks(definition.get())))
                    .where('X',
                            blocks(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST.get()).setMinGlobalLimited(40)
                                    .or(autoAbilities(definition.getRecipeTypes()))
                                    .or(Predicates.autoAbilities(true, false, true)))
                    .where('P', blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where('G', blocks(GTBlocks.FUSION_GLASS.get()))
                    .where('F', blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.TungstenSteel)))
                    .where('A', air())
                    .where('#', any())
                    .where('M', blocks(GTMachines.MUFFLER_HATCH[GTValues.LuV].get()))
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_robust_tungstensteel"),
                    GTCEu.id("block/multiblock/implosion_compressor"))
            .register();

    public final static MultiblockMachineDefinition STELLAR_FORGE = REGISTRATE.multiblock("stellar_forge", (holder) -> new TierCasingMachine(holder, "SCTier"))
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.STELLAR_FORGE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GCY"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.stellar_forge")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GCyMBlocks.CASING_ATOMIC)
            .pattern(definition -> MultiBlockMachineAStructureD.STELLAR_FORGE
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GCyMBlocks.CASING_ATOMIC.get())
                            .setMinGlobalLimited(150)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.FUSION_COIL.get()))
                    .where("d", GTLPredicates.tierCasings(BlockMap.scMap, "SCTier"))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/gcym/atomic_casing"), GTCEu.id("block/multiblock/electric_blast_furnace"))
            .register();

    public final static MultiblockMachineDefinition COMPONENT_ASSEMBLY_LINE = REGISTRATE.multiblock("component_assembly_line", (holder) -> new TierCasingMachine(holder, "CATier"))
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.COMPONENT_ASSEMBLY_LINE_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.component_assembly_line")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.IRIDIUM_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureD.COMPONENT_ASSEMBLY_LINE
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get()))
                    .where("B", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.TungstenSteel))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS)))
                    .where("C", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS)))
                    .where("D", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("F", Predicates.blocks(GTLBlocks.HSSS_REINFORCED_BOROSILICATE_GLASS.get()))
                    .where("G", Predicates.blocks(GTBlocks.FILTER_CASING.get()))
                    .where("H", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1).setPreviewCount(1)))
                    .where("I", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTLMaterials.HastelloyN)))
                    .where("J", Predicates.blocks(GTLBlocks.ADVANCED_ASSEMBLY_LINE_UNIT.get()))
                    .where("K", GTLPredicates.tierCasings(BlockMap.calMap, "CATier"))
                    .where("L", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where("M", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.TungstenSteel)))
                    .where("N", Predicates.blocks(GTLBlocks.IRIDIUM_CASING.get())
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/iridium_casing"), GTCEu.id("block/multiblock/assembly_line"))
            .register();

    public final static MultiblockMachineDefinition ADVANCED_INTEGRATED_ORE_PROCESSOR = REGISTRATE.multiblock("advanced_integrated_ore_processor", WorkableElectricMultipleRecipesMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.INTEGRATED_ORE_PROCESSOR)
            .tooltips(Component.translatable("gtceu.machine.integrated_ore_processor.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.advanced_integrated_ore_processor.tooltip.0"))
            .tooltips(Component.translatable("gtceu.multiblock.only.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.integrated_ore_processor")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST)
            .pattern((definition) -> MultiBlockMachineAStructureD.ADVANCED_INTEGRATED_ORE_PROCESSOR
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST.get()))
                    .where("B", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.HSSS)))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:restraint_device")))
                    .where("D", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where("E", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_GEARBOX.get()))
                    .where("F", Predicates.blocks(GTBlocks.CASING_GRATE.get()))
                    .where("G", Predicates.blocks(GTBlocks.CASING_HSSE_STURDY.get()))
                    .where("H", Predicates.blocks(GTLBlocks.HSSS_REINFORCED_BOROSILICATE_GLASS.get()))
                    .where("I", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST.get())
                            .or(Predicates.abilities(PartAbility.INPUT_LASER))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_robust_tungstensteel"), GTCEu.id("block/multiblock/gcym/large_maceration_tower"))
            .register();

    public final static MultiblockMachineDefinition DIMENSIONALLY_TRANSCENDENT_STEAM_BOILER = REGISTRATE.multiblock("dimensionally_transcendent_steam_boiler", holder -> new LargeBoilerMachine(holder, 4096000, 32))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.LARGE_BOILER_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.large_boiler.max_temperature", 4096000 + 274.15, 4096000))
            .tooltips(Component.translatable("gtceu.multiblock.large_boiler.heat_time_tooltip", 4096000 / 32 / 20))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.large_boiler")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(LargeBoilerMachine::recipeModifier)
            .appearanceBlock(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST)
            .pattern(definition -> GTLMachines.DTPF.where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("e", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST.get())
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(16))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1)))
                    .where("b", Predicates.blocks(GTBlocks.CASING_INVAR_HEATPROOF.get()))
                    .where("C", Predicates.blocks(GCyMBlocks.MOLYBDENUM_DISILICIDE_COIL_BLOCK.get()))
                    .where("d", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_ROBUST.get()))
                    .where("s", Predicates.blocks(GTBlocks.CASING_TUNGSTENSTEEL_PIPE.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_robust_tungstensteel"), GTCEu.id("block/multiblock/generator/large_tungstensteel_boiler"))
            .register();

    public final static MultiblockMachineDefinition DIMENSIONALLY_TRANSCENDENT_STEAM_OVEN = REGISTRATE.multiblock("dimensionally_transcendent_steam_oven", holder -> new LargeSteamParallelMultiblockMachine(holder, 524288))
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.FURNACE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.dimensionally_transcendent_dirt_forge.tooltip.0"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("compass.node.gtceu.steam/steam_furnace")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTLRecipeModifiers.reduction(machine, recipe, 0.01, 1), (machine, recipe, params, result) -> LargeSteamParallelMultiblockMachine.recipeModifier(machine, recipe, 0))
            .appearanceBlock(GTBlocks.CASING_COKE_BRICKS)
            .pattern(definition -> GTLMachines.DTPF.where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("e", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get())
                            .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.STEAM_IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.STEAM_EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(4)))
                    .where("b", Predicates.blocks(Blocks.BRICKS))
                    .where("C", Predicates.blocks(Blocks.DEEPSLATE))
                    .where("d", Predicates.blocks(Blocks.STONE_BRICKS))
                    .where("s", Predicates.blocks(GTBlocks.CASING_BRONZE_BRICKS.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"), GTCEu.id("block/multiblock/steam_oven"))
            .register();

    public static final MultiblockMachineDefinition ADVANCED_MULTI_SMELTER = REGISTRATE
            .multiblock("advanced_multi_smelter", CoilWorkableElectricMultipleRecipesMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeTypes(GTRecipeTypes.FURNACE_RECIPES)
            .appearanceBlock(GTBlocks.CASING_INVAR_HEATPROOF)
            .tooltips(Component.translatable("gtceu.multiblock.coil_parallel"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.electric_furnace")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .pattern(definition -> MultiBlockMachineAStructureD.DIMENSIONALLY_TRANSCENDENT_STEAM_OVEN
                    .where('S', controller(blocks(definition.get())))
                    .where('X', blocks(GTBlocks.CASING_INVAR_HEATPROOF.get()).setMinGlobalLimited(9)
                            .or(autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER))
                            .or(autoAbilities(true, false, false)))
                    .where('M', abilities(PartAbility.MUFFLER))
                    .where('C', heatingCoils())
                    .where('#', air())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/solid/machine_casing_heatproof"), GTCEu.id("block/multiblock/multi_furnace"))
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal(FormattingUtil.formatNumbers(Math.min(2147483647, Math.pow(2, (double) ((CoilWorkableElectricMultipleRecipesMultiblockMachine) controller).getCoilType().getCoilTemperature() / 900)))).withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                }
            })
            .register();

    public final static MultiblockMachineDefinition NANO_CORE = REGISTRATE.multiblock("nano_core", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.NANO_FORGE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.nano_core.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.nano_core.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.nano_core.tooltip.2"))
            .tooltips(Component.translatable("gtceu.multiblock.only.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.nano_forge")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTLRecipeModifiers.reduction(machine, recipe, 1, 0.05), (machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, 8192, false).getFirst(), ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.NAQUADAH_ALLOY_CASING)
            .pattern((definition) -> MultiBlockMachineAStructureF.NANO_CORE
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.NAQUADAH_ALLOY_CASING.get()))
                    .where("F", Predicates.blocks(GTLBlocks.NAQUADAH_ALLOY_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER)))
                    .where("B", Predicates.blocks(GTLBlocks.HYPER_MECHANICAL_CASING.get()))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:spacetime_assembly_line_casing")))
                    .where("D", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("E", Predicates.blocks(Registries.getBlock("kubejs:containment_field_generator")))
                    .where("G", Predicates.blocks(GTLBlocks.ADVANCED_ASSEMBLY_LINE_UNIT.get()))
                    .where(" ", Predicates.any())
                    .build())
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal(String.valueOf(8192)).withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                }
            })
            .workableCasingRenderer(GTLCore.id("block/casings/hyper_mechanical_casing"), GTCEu.id("block/multiblock/gcym/large_assembler"))
            .register();
}
