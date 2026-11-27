package org.gtlcore.gtlcore.common.data.machines;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.pattern.GTLPredicates;
import org.gtlcore.gtlcore.client.renderer.machine.EyeOfHarmonyRenderer;
import org.gtlcore.gtlcore.common.data.*;
import org.gtlcore.gtlcore.common.data.machines.structure.AdvancedMultiBlockMachineStructureA;
import org.gtlcore.gtlcore.common.data.machines.structure.AdvancedMultiBlockMachineStructureB;
import org.gtlcore.gtlcore.common.machine.multiblock.SimulationMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.*;
import org.gtlcore.gtlcore.utils.MachineIO;
import org.gtlcore.gtlcore.utils.MachineUtil;
import org.gtlcore.gtlcore.utils.Registries;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.CoilWorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.recipe.OverclockingLogic;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.common.data.*;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import com.hepdd.gtmthings.data.CustomMachines;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.gregtechceu.gtceu.api.GTValues.IV;
import static com.gregtechceu.gtceu.api.pattern.Predicates.*;
import static com.gregtechceu.gtceu.common.data.GTRecipeTypes.DUMMY_RECIPES;
import static com.gregtechceu.gtceu.common.registry.GTRegistration.REGISTRATE;
import static java.lang.Math.max;
import static net.minecraft.core.registries.Registries.DIMENSION;

@SuppressWarnings("unused")
public class AdvancedMultiBlockMachineA {

    public static void init() {}

    public final static MultiblockMachineDefinition SIMULATION_MACHINE = REGISTRATE
            .multiblock("simulation_machine", SimulationMachine::new)
            .langValue("Simulation Machine")
            .rotationState(RotationState.ALL)
            .recipeType(DUMMY_RECIPES)
            .appearanceBlock(() -> Blocks.STONE)
            .tooltips(Component.translatable("gtceu.machine.simulation_machine.tooltip.0"))
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.SIMULATION_MACHINE
                    .where("c", controller(blocks(definition.get())))
                    .where("a", Predicates.blocks(Blocks.STONE))
                    .build())
            .workableCasingRenderer(new ResourceLocation("minecraft", "block/stone"),
                    GTCEu.id("block/multiblock/gcym/large_chemical_bath"))
            .compassSections(GTCompassSections.TIER[IV])
            .compassNodeSelf()
            .register();

    public final static MultiblockMachineDefinition GREENHOUSE = REGISTRATE.multiblock("greenhouse", GreenhouseMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.GREENHOUSE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.greenhouse.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.greenhouse.tooltip.1"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GCPM"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.greenhouse")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .appearanceBlock(GTBlocks.MACHINE_CASING_ULV)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK))
            .pattern((definition) -> AdvancedMultiBlockMachineStructureA.GREENHOUSE
                    .where("E", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("G", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get()))
                    .where("B", Predicates.blocks(GTBlocks.MACHINE_CASING_ULV.get())
                            .setMinGlobalLimited(40)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("#", Predicates.air())
                    .where("0", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/voltage/ulv/side"), GTCEu.id("block/multiblock/implosion_compressor"))
            .register();

    public final static MultiblockMachineDefinition EYE_OF_HARMONY = REGISTRATE.multiblock("eye_of_harmony", HarmonyMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.COSMOS_SIMULATION_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.eye_of_harmony.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.eye_of_harmony.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.eye_of_harmony.tooltip.2"))
            .tooltips(Component.translatable("gtceu.machine.eye_of_harmony.tooltip.3"))
            .tooltips(Component.translatable("gtceu.machine.eye_of_harmony.tooltip.4"))
            .tooltips(Component.translatable("gtceu.machine.eye_of_harmony.tooltip.5"))
            .tooltips(Component.translatable("gtceu.machine.eye_of_harmony.tooltip.6"))
            .tooltips(Component.translatable("gtceu.machine.eye_of_harmony.tooltip.7"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.cosmos_simulation")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(HarmonyMachine::recipeModifier)
            .appearanceBlock(GTBlocks.HIGH_POWER_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.EYE_OF_HARMONY
                    .where('~', Predicates.controller(Predicates.blocks(definition.get())))
                    .where('A', Predicates.blocks(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get()))
                    .where('B', Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get())
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1)))
                    .where('D', Predicates.blocks(GTLBlocks.DIMENSION_INJECTION_CASING.get()))
                    .where('E', Predicates.blocks(Registries.getBlock("kubejs:dimensional_bridge_casing")))
                    .where('F', Predicates.blocks(Registries.getBlock("kubejs:spacetime_compression_field_generator")))
                    .where('G', Predicates.blocks(Registries.getBlock("kubejs:dimensional_stability_casing")))
                    .where(" ", Predicates.any())
                    .build())
            .renderer(EyeOfHarmonyRenderer::new)
            .hasTESR(true)
            .register();

    public final static MultiblockMachineDefinition SPACE_PROBE_SURFACE_RECEPTION = REGISTRATE.multiblock("space_probe_surface_reception", SpaceProbeSurfaceReceptionMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.SPACE_PROBE_SURFACE_RECEPTION_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.space_probe_surface_reception.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.space_probe_surface_reception")))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GCY"))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GCyMBlocks.CASING_ATOMIC)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.SPACE_PROBE_SURFACE_RECEPTION
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GCyMBlocks.CASING_ATOMIC.get())
                            .setMinGlobalLimited(140)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_RECEPTION).setMaxGlobalLimited(1)))
                    .where("c", Predicates.blocks(GCyMBlocks.HEAT_VENT.get()))
                    .where("d", Predicates.blocks(Registries.getBlock("kubejs:neutronium_pipe_casing")))
                    .where("e", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTLMaterials.BlackTitanium)))
                    .where("f", Predicates.blocks(GTLBlocks.EXTREME_STRENGTH_TRITANIUM_CASING.get()))
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/gcym/atomic_casing"), GTCEu.id("block/multiblock/data_bank"))
            .register();

    public final static MultiblockMachineDefinition SPACE_COSMIC_PROBE_RECEIVERS = REGISTRATE.multiblock("space_cosmic_probe_receivers", holder -> new SpaceProbeSurfaceReceptionMachine(holder) {

        @Override
        @Nullable
        protected BlockPos findTopBlock() {
            Level level = getLevel();
            if (level == null) return null;

            BlockPos pos = getPos();
            BlockPos[] coordinates = new BlockPos[] {
                    pos.offset(9, 20, 0),
                    pos.offset(-9, 20, 0),
                    pos.offset(0, 20, 9),
                    pos.offset(0, 20, -9)
            };

            for (BlockPos checkPos : coordinates) {
                if (level.getBlockState(checkPos)
                        .is(ChemicalHelper.getBlock(TagPrefix.frameGt, GTLMaterials.Vibranium))) {
                    return checkPos.offset(0, 1, 0);
                }
            }
            return null;
        }
    })
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.SPACE_COSMIC_PROBE_RECEIVERS_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.space_probe_surface_reception.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.space_cosmic_probe_receivers")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.HYPER_MECHANICAL_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.SPACE_COSMIC_PROBE_RECEIVERS
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTLBlocks.EXTREME_STRENGTH_TRITANIUM_CASING.get()))
                    .where("B", Predicates.blocks(GCyMBlocks.CASING_ATOMIC.get()))
                    .where("C", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("D", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Neutronium)))
                    .where("E", Predicates.blocks(GTLBlocks.OXIDATION_RESISTANT_HASTELLOY_N_MECHANICAL_CASING.get()))
                    .where("F", Predicates.blocks(GTLBlocks.HYPER_MECHANICAL_CASING.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(4))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_RECEPTION).setMaxGlobalLimited(1)))
                    .where("G", Predicates.blocks(Registries.getBlock("kubejs:neutronium_pipe_casing")))
                    .where("H", Predicates.blocks(Registries.getBlock("kubejs:annihilate_core")))
                    .where("I", Predicates.blocks(GCyMBlocks.HEAT_VENT.get()))
                    .where("J", Predicates.blocks(GTLBlocks.ANTIFREEZE_HEATPROOF_MACHINE_CASING.get()))
                    .where("K", Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get()))
                    .where("L", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTLMaterials.Vibranium)))
                    .where("M", Predicates.blocks(Registries.getBlock("kubejs:speeding_pipe")))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/hyper_mechanical_casing"), GTCEu.id("block/multiblock/data_bank"))
            .register();

    public final static MultiblockMachineDefinition DIMENSIONALLY_TRANSCENDENT_PLASMA_FORGE = REGISTRATE.multiblock("dimensionally_transcendent_plasma_forge", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.DIMENSIONALLY_TRANSCENDENT_PLASMA_FORGE_RECIPES)
            .recipeType(GTLRecipeTypes.STELLAR_FORGE_RECIPES)
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.dimensionally_transcendent_plasma_forge.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.dimensionally_transcendent_plasma_forge"), Component.translatable("gtceu.stellar_forge")))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers(GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING)
            .pattern(definition -> GTLMachines.DTPF.where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("e", Predicates.blocks(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get())
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("b", Predicates.blocks(GTLBlocks.DIMENSION_INJECTION_CASING.get()))
                    .where("C", Predicates.heatingCoils())
                    .where("d", Predicates.blocks(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get()))
                    .where("s", Predicates.blocks(Registries.getBlock("kubejs:dimensional_bridge_casing")))
                    .where(" ", Predicates.any())
                    .build())
            .beforeWorking((machine, recipe) -> {
                if (machine instanceof CoilWorkableElectricMultiblockMachine coilWorkableElectricMultiblockMachine) {
                    int coilTemp = coilWorkableElectricMultiblockMachine.getCoilType().getCoilTemperature();
                    coilTemp = coilTemp == 273 ? 32000 : coilTemp;
                    if (machine.getRecipeType() == GTLRecipeTypes.STELLAR_FORGE_RECIPES) {
                        if (coilTemp == 32000) return true;
                    } else if (recipe.data.getInt("ebf_temp") <= coilTemp) {
                        return true;
                    }
                }
                machine.getRecipeLogic().interruptRecipe();
                return false;
            })
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed() && controller instanceof CoilWorkableElectricMultiblockMachine machine) {
                    int coilTemp = machine.getCoilType().getCoilTemperature();
                    coilTemp = coilTemp == 273 ? 32000 : coilTemp;
                    components.add(Component.translatable("gtceu.multiblock.blast_furnace.max_temperature", Component.literal(FormattingUtil.formatNumbers(coilTemp) + "K").withStyle(ChatFormatting.BLUE)));
                    if (machine.getRecipeType() == GTLRecipeTypes.STELLAR_FORGE_RECIPES && coilTemp != 32000) {
                        components.add(Component.translatable("message.gtlcore.coil_incompatible_recipe_mode").withStyle(ChatFormatting.RED));
                    }
                }
            })
            .workableCasingRenderer(GTLCore.id("block/casings/dimensionally_transcendent_casing"), GTCEu.id("block/multiblock/dimensionally_transcendent_plasma_forge"))
            .register();

    public final static MultiblockMachineDefinition CIRCUIT_ASSEMBLY_LINE = REGISTRATE.multiblock("circuit_assembly_line", (holder) -> new StorageMachine(holder, 64))
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.CIRCUIT_ASSEMBLY_LINE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.circuit_assembly_line.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.circuit_assembly_line")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> {
                boolean isParallel = false;
                int p = 0;
                if (machine instanceof StorageMachine storageMachine) {
                    ItemStack item = storageMachine.getMachineStorageItem();
                    p = Math.min((item.getCount() * 2), 128);
                    long inputEUt = RecipeHelper.getInputEUt(recipe);
                    if (inputEUt == GTValues.VA[GTValues.UV]) {
                        isParallel = Objects.equals(Registries.getItemId(item), "kubejs:precision_circuit_assembly_robot_mk1");
                    } else if (inputEUt == GTValues.VA[GTValues.UHV]) {
                        isParallel = Objects.equals(Registries.getItemId(item), "kubejs:precision_circuit_assembly_robot_mk2");
                    } else if (inputEUt == GTValues.VA[GTValues.UEV]) {
                        isParallel = Objects.equals(Registries.getItemId(item), "kubejs:precision_circuit_assembly_robot_mk3");
                    } else if (inputEUt == GTValues.VA[GTValues.UIV]) {
                        isParallel = Objects.equals(Registries.getItemId(item), "kubejs:precision_circuit_assembly_robot_mk4");
                    } else if (inputEUt == GTValues.VA[GTValues.UXV]) {
                        isParallel = Objects.equals(Registries.getItemId(item), "kubejs:precision_circuit_assembly_robot_mk5");
                    }
                }
                if (isParallel) {
                    return GTRecipeModifiers.accurateParallel(machine, recipe, p, false).getFirst();
                } else {
                    return recipe;
                }
            }, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.PIKYONIUM_MACHINE_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.CIRCUIT_ASSEMBLY_LINE
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.PIKYONIUM_MACHINE_CASING.get())
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMinGlobalLimited(1).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(GTBlocks.CASING_LAMINATED_GLASS.get()))
                    .where("d", Predicates.blocks(GTMachines.ITEM_IMPORT_BUS[0].get()).or(Predicates.blocks(CustomMachines.HUGE_ITEM_IMPORT_BUS[0].get())))
                    .where("e", Predicates.blocks(Registries.getBlock("kubejs:machine_casing_circuit_assembly_line")))
                    .where("f", GTLPredicates.diffAbilities(List.of(PartAbility.EXPORT_ITEMS), List.of(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS)))
                    .where("g", Predicates.abilities(PartAbility.IMPORT_FLUIDS_4X).or(Predicates.blocks(GTLMachines.HUGE_FLUID_IMPORT_HATCH[4].get())))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/pikyonium_machine_casing"), GTCEu.id("block/multiblock/assembly_line"))
            .register();

    public final static MultiblockMachineDefinition ASSEMBLER_MODULE = REGISTRATE.multiblock("assembler_module", (holder) -> new SpaceElevatorModuleMachine(holder, true))
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.ASSEMBLER_MODULE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.resource_collection.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.assembler_module")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(SpaceElevatorModuleMachine::recipeModifier)
            .appearanceBlock(GTLBlocks.SPACE_ELEVATOR_MECHANICAL_CASING)
            .pattern((definition) -> AdvancedMultiBlockMachineStructureA.ASSEMBLER_MODULE
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.SPACE_ELEVATOR_MECHANICAL_CASING.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes())))
                    .where("a", Predicates.blocks(Registries.getBlock("kubejs:module_base")))
                    .where("c", Predicates.blocks(Registries.getBlock("kubejs:module_connector")))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/space_elevator_mechanical_casing"), GTCEu.id("block/multiblock/gcym/large_assembler"))
            .register();

    public final static MultiblockMachineDefinition RESOURCE_COLLECTION = REGISTRATE.multiblock("resource_collection", (holder) -> new SpaceElevatorModuleMachine(holder, false))
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.MINER_MODULE_RECIPES)
            .recipeType(GTLRecipeTypes.DRILLING_MODULE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.resource_collection.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.miner_module"), Component.translatable("gtceu.drilling_module")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(SpaceElevatorModuleMachine::recipeModifier)
            .appearanceBlock(GTLBlocks.SPACE_ELEVATOR_MECHANICAL_CASING)
            .pattern((definition) -> AdvancedMultiBlockMachineStructureA.RESOURCE_COLLECTION
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.SPACE_ELEVATOR_MECHANICAL_CASING.get())
                            .or(Predicates.autoAbilities(definition.getRecipeTypes())))
                    .where("a", Predicates.blocks(Registries.getBlock("kubejs:module_base")))
                    .where("c", Predicates.blocks(Registries.getBlock("kubejs:module_connector")))
                    .build())
            .workableCasingRenderer(GTLCore.id("block/space_elevator_mechanical_casing"), GTCEu.id("block/multiblock/gcym/large_assembler"))
            .register();

    private static final Map<String, String> COV_RECIPE = new HashMap<>();

    static {
        COV_RECIPE.put("minecraft:bone_block", "kubejs:essence_block");
        COV_RECIPE.put("minecraft:oak_log", "minecraft:crimson_stem");
        COV_RECIPE.put("minecraft:birch_log", "minecraft:warped_stem");
        COV_RECIPE.put("gtceu:calcium_block", "minecraft:bone_block");
        COV_RECIPE.put("minecraft:moss_block", "minecraft:sculk");
        COV_RECIPE.put("minecraft:grass_block", "minecraft:moss_block");
        COV_RECIPE.put("kubejs:infused_obsidian", "kubejs:draconium_block_charged");
    }

    private static boolean blockConversionRoom(List<int[]> poses, IRecipeLogicMachine machine, int tier) {
        if (machine instanceof WorkableElectricMultiblockMachine workableElectricMultiblockMachine) {
            if (workableElectricMultiblockMachine.getOffsetTimer() % 20 == 0) {
                Level level = machine.self().getLevel();
                if (level != null) {
                    int amount = workableElectricMultiblockMachine.getTier() * tier - 7;
                    int[] pos = new int[] {};
                    for (int i = 0; i < amount; i++) {
                        int[] pos_0 = poses.get((int) (Math.random() * poses.size()));
                        if (pos_0 != pos) {
                            pos = pos_0;
                            BlockPos blockPos = machine.self().getPos().offset(pos[0], pos[1], pos[2]);
                            String block = Registries.getBlockId(level.getBlockState(blockPos).getBlock());
                            if (COV_RECIPE.containsKey(block)) {
                                level.setBlockAndUpdate(blockPos, Registries.getBlock(COV_RECIPE.get(block)).defaultBlockState());
                            }
                        } else {
                            i--;
                        }
                    }
                }
            }
        }
        return true;
    }

    public final static MultiblockMachineDefinition BLOCK_CONVERSION_ROOM = REGISTRATE.multiblock("block_conversion_room", holder -> new BlockConversionRoomMachine(holder, false))
            .rotationState(RotationState.NONE)
            .allowExtendedFacing(false)
            .allowFlip(false)
            .recipeType(GTLRecipeTypes.BLOCK_CONVERSION_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.block_conversion_room.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.block_conversion_room.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.block_conversion")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(new OverclockingLogic(1, 4, false)))
            .appearanceBlock(GTLBlocks.ALUMINIUM_BRONZE_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.BLOCK_CONVERSION_ROOM
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.ALUMINIUM_BRONZE_CASING.get()).setMinGlobalLimited(120)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.blocks(GTLMachines.BLOCK_BUS.getBlock()).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(Registries.getBlock("kubejs:shining_obsidian")))
                    .where("d", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get())
                            .or(Predicates.blocks(Blocks.IRON_DOOR).setMaxGlobalLimited(4)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/aluminium_bronze_casing"), GTCEu.id("block/multiblock/cleanroom"))
            .register();

    public final static MultiblockMachineDefinition LARGE_BLOCK_CONVERSION_ROOM = REGISTRATE.multiblock("large_block_conversion_room", (holder) -> new BlockConversionRoomMachine(holder, true))
            .rotationState(RotationState.NONE)
            .allowExtendedFacing(false)
            .allowFlip(false)
            .recipeType(GTLRecipeTypes.BLOCK_CONVERSION_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.block_conversion_room.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.large_block_conversion_room.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.large_block_conversion_room.tooltip.2"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.block_conversion")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(new OverclockingLogic(1, 4, false)))
            .appearanceBlock(GTLBlocks.ALUMINIUM_BRONZE_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.LARGE_BLOCK_CONVERSION_ROOM
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("b", Predicates.blocks(GTLBlocks.ALUMINIUM_BRONZE_CASING.get()).setMinGlobalLimited(240)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.blocks(GTLMachines.BLOCK_BUS.getBlock()).setMaxGlobalLimited(3))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("c", Predicates.blocks(Registries.getBlock("kubejs:shining_obsidian")))
                    .where("d", Predicates.blocks(GTBlocks.CASING_TEMPERED_GLASS.get())
                            .or(Predicates.blocks(Blocks.IRON_DOOR).setMaxGlobalLimited(4)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/aluminium_bronze_casing"), GTCEu.id("block/multiblock/cleanroom"))
            .register();

    public final static MultiblockMachineDefinition PCB_FACTORY = REGISTRATE.multiblock("pcb_factory", PCBFactoryMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .allowExtendedFacing(false)
            .recipeType(GTLRecipeTypes.PCB_FACTORY_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.pcb_factory.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.pcb_factory.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.pcb_factory.tooltip.2"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GTNH"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.pcb_factory")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(PCBFactoryMachine::recipeModifier)
            .appearanceBlock(GCyMBlocks.CASING_WATERTIGHT)
            .pattern((definition) -> AdvancedMultiBlockMachineStructureA.PCB_FACTORY
                    .where("a", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("j", Predicates.blocks(GCyMBlocks.CASING_WATERTIGHT.get()).setMinGlobalLimited(60)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where("b", Predicates.blocks(GTBlocks.CASING_STAINLESS_CLEAN.get()))
                    .where("c", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.BlueSteel)))
                    .where("d", Predicates.blocks(GTLBlocks.ANTIFREEZE_HEATPROOF_MACHINE_CASING.get()))
                    .where("e", Predicates.blocks(GTBlocks.CASING_LAMINATED_GLASS.get()))
                    .where("g", Predicates.blocks(GTBlocks.CASING_POLYTETRAFLUOROETHYLENE_PIPE.get()))
                    .where("h", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.Ultimet)))
                    .where("i", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.HSLASteel)))
                    .where("k", Predicates.blocks(GCyMBlocks.CASING_STRESS_PROOF.get()))
                    .where("l", Predicates.blocks(GTBlocks.CASING_STAINLESS_EVAPORATION.get()))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTCEu.id("block/casings/gcym/watertight_casing"), GTCEu.id("block/multiblock/gcym/large_maceration_tower"))
            .register();

    public final static MultiblockMachineDefinition BLAZE_BLAST_FURNACE = REGISTRATE.multiblock("blaze_blast_furnace", CoilWorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.BLAST_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.5))
            .tooltips(Component.translatable("gtceu.machine.blaze_blast_furnace.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.blaze_blast_furnace.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.a"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.2"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GT++"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.electric_blast_furnace")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTLRecipeModifiers.reduction(machine, recipe, 1, 0.5), (machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, 64, false).getFirst(), GTRecipeModifiers::ebfOverclock)
            .appearanceBlock(GTLBlocks.BLAZE_BLAST_FURNACE_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.BLAZE_BLAST_FURNACE
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("X", Predicates.blocks(GTLBlocks.BLAZE_BLAST_FURNACE_CASING.get()).setMinGlobalLimited(9)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("M", Predicates.abilities(PartAbility.MUFFLER))
                    .where("C", Predicates.heatingCoils())
                    .where("#", Predicates.air())
                    .build())
            .beforeWorking((machine, recipe) -> {
                if (MachineIO.inputFluid((WorkableMultiblockMachine) machine, GTMaterials.Blaze.getFluid((long) (Math.pow(2, (((CoilWorkableElectricMultiblockMachine) machine).getTier() - 2)) * 10)))) {
                    return true;
                }
                machine.getRecipeLogic().interruptRecipe();
                return false;
            })
            .onWorking(machine -> {
                if (machine instanceof CoilWorkableElectricMultiblockMachine coilWorkableElectricMultiblockMachine && coilWorkableElectricMultiblockMachine.getOffsetTimer() % 20 == 0) {
                    if (MachineIO.inputFluid((WorkableMultiblockMachine) machine, GTMaterials.Blaze.getFluid((long) (Math.pow(2, (coilWorkableElectricMultiblockMachine.getTier() - 2)) * 10)))) {
                        return true;
                    }
                    machine.getRecipeLogic().setProgress(0);
                }
                return true;
            })
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal("64").withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                }
                if (controller instanceof CoilWorkableElectricMultiblockMachine coilMachine && controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.blast_furnace.max_temperature",
                            Component.translatable(FormattingUtil.formatNumbers(coilMachine.getCoilType().getCoilTemperature() + 100L * max(0, coilMachine.getTier() - GTValues.MV)) + "K")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED))));
                }
            })
            .workableCasingRenderer(GTLCore.id("block/blaze_blast_furnace_casing"), GTCEu.id("block/multiblock/electric_blast_furnace"))
            .register();

    public final static MultiblockMachineDefinition COLD_ICE_FREEZER = REGISTRATE.multiblock("cold_ice_freezer", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.VACUUM_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.5))
            .tooltips(Component.translatable("gtceu.machine.cold_ice_freezer.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.blaze_blast_furnace.tooltip.1"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "GT++"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.vacuum_freezer")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTLRecipeModifiers.reduction(machine, recipe, 1, 0.5), (machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, 64, false).getFirst(), GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.COLD_ICE_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.COLD_ICE_FREEZER
                    .where("S", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("X", Predicates.blocks(GTLBlocks.COLD_ICE_CASING.get()).setMinGlobalLimited(10)
                            .or(Predicates.autoAbilities(definition.getRecipeTypes()))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("M", Predicates.abilities(PartAbility.MUFFLER))
                    .where("#", Predicates.blocks(GTBlocks.HERMETIC_CASING_LuV.get()))
                    .build())
            .beforeWorking((machine, recipe) -> {
                if (MachineIO.inputFluid((WorkableMultiblockMachine) machine, GTMaterials.Ice.getFluid((long) (Math.pow(2, (((WorkableElectricMultiblockMachine) machine).getTier() - 2)) * 10)))) {
                    return true;
                }
                machine.getRecipeLogic().interruptRecipe();
                return false;
            })
            .onWorking(machine -> {
                if (machine instanceof WorkableElectricMultiblockMachine workableElectricMultiblockMachine && workableElectricMultiblockMachine.getOffsetTimer() % 20 == 0) {
                    if (MachineIO.inputFluid((WorkableMultiblockMachine) machine, GTMaterials.Ice.getFluid((long) (Math.pow(2, (workableElectricMultiblockMachine.getTier() - 2)) * 10)))) {
                        return true;
                    }
                    machine.getRecipeLogic().setProgress(0);
                }
                return true;
            })
            .additionalDisplay((controller, components) -> {
                if (controller.isFormed()) {
                    components.add(Component.translatable("gtceu.multiblock.parallel", Component.literal("64").withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
                }
            })
            .workableCasingRenderer(GTLCore.id("block/cold_ice_casing"), GTCEu.id("block/multiblock/vacuum_freezer"))
            .register();

    public final static MultiblockMachineDefinition DOOR_OF_CREATE = REGISTRATE.multiblock("door_of_create", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NONE)
            .allowExtendedFacing(false)
            .allowFlip(false)
            .recipeType(GTLRecipeTypes.DOOR_OF_CREATE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.door_of_create")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(new OverclockingLogic(1, 1, false)))
            .appearanceBlock(GTLBlocks.DIMENSION_CONNECTION_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.DOOR_OF_CREATE
                    .where("b", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("a", Predicates.blocks(GTLBlocks.DIMENSION_CONNECTION_CASING.get()))
                    .where("d", Predicates.blocks(GTLBlocks.DIMENSION_CONNECTION_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(1)))
                    .where("c", Predicates.blocks(Registries.getBlock("kubejs:dimension_creation_casing")))
                    .where(" ", Predicates.any())
                    .build())
            .onWorking(machine -> {
                if (machine.getRecipeLogic().getProgress() == 5 && machine instanceof WorkableElectricMultiblockMachine workableElectricMultiblockMachine) {
                    if (machine.self().getLevel() instanceof ServerLevel level) {
                        BlockPos pos = machine.self().getPos().offset(0, -13, 0);
                        level.sendParticles(ParticleTypes.DRAGON_BREATH, pos.getX(), pos.getY(), pos.getZ(), 1000, 4.0, 4.0, 4.0, 0.01);

                        List<Entity> entities = level.getEntitiesOfClass(Entity.class, new AABB(pos.getX() - 10, pos.getY() - 10, pos.getZ() - 10, pos.getX() + 10, pos.getY() + 10, pos.getZ() + 10));
                        for (Entity entity : entities) {
                            if (entity instanceof ItemEntity itemEntity) {
                                switch (Registries.getItemId(itemEntity.getItem())) {
                                    case "gtceu:magnetohydrodynamicallyconstrainedstarmatter_block" -> {
                                        MachineUtil.createItemEntity(level, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), new ItemStack(Blocks.COMMAND_BLOCK, itemEntity.getItem()
                                                .getCount()));
                                        itemEntity.discard();
                                    }
                                    case "gtceu:magmatter_ingot" -> {
                                        if (itemEntity.getItem().getCount() >= 64) {
                                            MachineUtil.createItemEntity(level, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), new ItemStack(Registries.getItem("gtceu:magmatter_block"), itemEntity.getItem()
                                                    .getCount() / 64));
                                            itemEntity.discard();
                                        }
                                    }
                                    case "expatternprovider:fishbig" -> {
                                        if (itemEntity.getItem().getCount() >= 64) {
                                            MachineUtil.createItemEntity(level, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), new ItemStack(Registries.getItem("gtlcore:ultimate_tea"), itemEntity.getItem()
                                                    .getCount() / 64));
                                            itemEntity.discard();
                                        }
                                    }
                                    case "gtlcore:ultimate_tea" -> {
                                        if (itemEntity.getItem().getCount() >= 16) {
                                            MachineUtil.createItemEntity(level, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), new ItemStack(Registries.getItem("kubejs:heartofthesmogus"), itemEntity.getItem()
                                                    .getCount() / 16));
                                            itemEntity.discard();
                                        }
                                    }
                                }
                            } else if (entity instanceof ServerPlayer player) {
                                if (MachineUtil.hasFullArmorSet(player)) {
                                    var createLevel = level.getServer().getLevel(ResourceKey.create(DIMENSION, new ResourceLocation("kubejs", "create")));
                                    if (createLevel != null) {
                                        player.teleportTo(createLevel, 0.0, 1.0, 0.0, player.getXRot(), player.getYRot());
                                    }
                                } else {
                                    player.displayClientMessage(Component.translatable("message.gtlcore.equipment_incompatible_dimension"), true);
                                }
                            }
                        }
                    }
                }
                return true;
            })
            .workableCasingRenderer(GTLCore.id("block/dimension_connection_casing"), GTCEu.id("block/multiblock/door_of_create"))
            .register();

    public final static MultiblockMachineDefinition BEDROCK_DRILLING_RIG = REGISTRATE.multiblock("bedrock_drilling_rig", BedrockDrillingRig::new)
            .rotationState(RotationState.NONE)
            .allowExtendedFacing(false)
            .allowFlip(false)
            .recipeType(GTLRecipeTypes.BEDROCK_DRILLING_RIG_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.bedrock_drilling_rig.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.bedrock_drilling_rig.tooltip.1"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.bedrock_drilling_rig")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.ECHO_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.BEDROCK_DRILLING_RIG
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("c", Predicates.blocks(GTLBlocks.ECHO_CASING.get())
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1)))
                    .where("a", Predicates.blocks(GTLBlocks.OXIDATION_RESISTANT_HASTELLOY_N_MECHANICAL_CASING.get()))
                    .where("b", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTMaterials.HastelloyX)))
                    .where("d", Predicates.blocks(GTBlocks.CASING_TITANIUM_PIPE.get()))
                    .where("e", Predicates.blocks(GCyMBlocks.MOLYBDENUM_DISILICIDE_COIL_BLOCK.get()))
                    .where("f", Predicates.blocks(Registries.getBlock("kubejs:neutronium_gearbox")))
                    .where("g", Predicates.blocks(Registries.getBlock("kubejs:machine_casing_grinding_head")))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/casings/echo_casing"), GTCEu.id("block/multiblock/cleanroom"))
            .register();

    public final static MultiblockMachineDefinition CREATE_AGGREGATION = REGISTRATE.multiblock("create_aggregation", WorkableElectricMultiblockMachine::new)
            .rotationState(RotationState.NONE)
            .allowExtendedFacing(false)
            .allowFlip(false)
            .recipeType(GTLRecipeTypes.CREATE_AGGREGATION_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.create_aggregation")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifier(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(new OverclockingLogic(1, 1, false)))
            .appearanceBlock(GTLBlocks.DIMENSION_CONNECTION_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.CREATE_AGGREGATION
                    .where("a", Predicates.blocks(GTLBlocks.DIMENSION_CONNECTION_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_RECEPTION).setMaxGlobalLimited(1)))
                    .where("b", Predicates.blocks(Registries.getBlock("kubejs:dimensional_bridge_casing")))
                    .where("c", Predicates.blocks(ChemicalHelper.getBlock(TagPrefix.frameGt, GTLMaterials.Infinity)))
                    .where("d", Predicates.blocks(GTLBlocks.CREATE_CASING.get()))
                    .where("e", Predicates.blocks(Registries.getBlock("kubejs:spacetime_compression_field_generator")))
                    .where("f", Predicates.blocks(Registries.getBlock("kubejs:create_aggregatione_core")))
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where(" ", Predicates.any())
                    .build())
            .onWorking(machine -> {
                if (machine.getRecipeLogic().getProgress() == 19) {
                    if (machine.self().getLevel() instanceof ServerLevel level) {
                        BlockPos pos = machine.self().getPos().offset(0, -16, 0);
                        String blockId = Registries.getBlockId(level.getBlockState(pos).getBlock());

                        switch (blockId) {
                            case "kubejs:command_block_broken" -> {
                                if (MachineIO.inputItem((WorkableMultiblockMachine) machine, Registries.getItemStack("kubejs:chain_command_block_core")))
                                    level.setBlockAndUpdate(pos, Blocks.CHAIN_COMMAND_BLOCK.defaultBlockState());
                            }
                            case "kubejs:chain_command_block_broken" -> {
                                if (MachineIO.inputItem((WorkableMultiblockMachine) machine, Registries.getItemStack("kubejs:repeating_command_block_core")))
                                    level.setBlockAndUpdate(pos, Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState());
                            }
                            case "expatternprovider:fishbig" -> {
                                if (MachineIO.inputItem((WorkableMultiblockMachine) machine, new ItemStack(GTLItems.ULTIMATE_TEA, 8)))
                                    level.setBlockAndUpdate(pos, GTMachines.CREATIVE_FLUID.defaultBlockState());
                                else if (MachineIO.inputItem((WorkableMultiblockMachine) machine, Registries.getItemStack("kubejs:heartofthesmogus", 64)))
                                    level.setBlockAndUpdate(pos, GTMachines.CREATIVE_ITEM.defaultBlockState());
                            }
                        }
                    }
                }
                return true;
            })
            .workableCasingRenderer(GTLCore.id("block/dimension_connection_casing"), GTCEu.id("block/multiblock/create_aggregation"))
            .register();

    public final static MultiblockMachineDefinition SUPRACHRONAL_ASSEMBLY_LINE_MODULE = REGISTRATE.multiblock("suprachronal_assembly_line_module", SuprachronalAssemblyLineModuleMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.ASSEMBLY_LINE_RECIPES)
            .recipeType(GTLRecipeTypes.CIRCUIT_ASSEMBLY_LINE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.4))
            .tooltips(Component.translatable("gtceu.machine.suprachronal_assembly_line_module.tooltip.0"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_2.tooltip",
                    Component.translatable("gtceu.assembly_line"), Component.translatable("gtceu.circuit_assembly_line")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTLRecipeModifiers.reduction(machine, recipe, 1, 0.4), (machine, recipe, params, result) -> GTRecipeModifiers.accurateParallel(machine, recipe, ((SuprachronalAssemblyLineModuleMachine) machine).getParallel(), false).getFirst(), GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.MOLECULAR_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureA.SUPRACHRONAL_ASSEMBLY_LINE_MODULE
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("B", Predicates.blocks(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get()))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:molecular_coil")))
                    .where("D", Predicates.blocks(GTBlocks.FUSION_GLASS.get()))
                    .where("E", Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get()))
                    .where("A", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.OPTICAL_DATA_RECEPTION).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/molecular_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();

    public final static MultiblockMachineDefinition SUPRACHRONAL_ASSEMBLY_LINE = REGISTRATE.multiblock("suprachronal_assembly_line", SuprachronalAssemblyLineMachine::new)
            .rotationState(RotationState.ALL)
            .recipeType(GTLRecipeTypes.SUPRACHRONAL_ASSEMBLY_LINE_RECIPES)
            .recipeType(GTRecipeTypes.ASSEMBLY_LINE_RECIPES)
            .recipeType(GTLRecipeTypes.CIRCUIT_ASSEMBLY_LINE_RECIPES)
            .tooltips(Component.translatable("gtceu.machine.suprachronal_assembly_line.tooltip.0"))
            .tooltips(Component.translatable("gtceu.machine.duration_multiplier.tooltip", 0.4))
            .tooltips(Component.translatable("gtceu.machine.suprachronal_assembly_line.tooltip.1"))
            .tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
            .tooltips(Component.translatable("gtceu.machine.perfect_oc"))
            .tooltips(Component.translatable("gtceu.multiblock.parallelizable.tooltip"))
            .tooltips(Component.translatable("tooltip.gtlcore.structure.source", "TST"))
            .tooltips(Component.translatable("gtceu.machine.available_recipe_map_3.tooltip",
                    Component.translatable("gtceu.suprachronal_assembly_line"), Component.translatable("gtceu.assembly_line"), Component.translatable("gtceu.circuit_assembly_line")))
            .tooltipBuilder(GTLMachines.GTL_ADD)
            .recipeModifiers((machine, recipe, params, result) -> GTLRecipeModifiers.reduction(machine, recipe, 1, 0.4), GTRecipeModifiers.PARALLEL_HATCH, GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK))
            .appearanceBlock(GTLBlocks.MOLECULAR_CASING)
            .pattern(definition -> AdvancedMultiBlockMachineStructureB.SUPRACHRONAL_ASSEMBLY_LINE
                    .where("~", Predicates.controller(Predicates.blocks(definition.get())))
                    .where("A", Predicates.blocks(GTBlocks.FUSION_GLASS.get()))
                    .where("B", Predicates.blocks(Registries.getBlock("kubejs:spacetime_assembly_line_unit")))
                    .where("C", Predicates.blocks(Registries.getBlock("kubejs:spacetime_assembly_line_casing")))
                    .where("D", Predicates.cleanroomFilters())
                    .where("E", Predicates.blocks(GTLBlocks.DIMENSION_INJECTION_CASING.get()))
                    .where("F", Predicates.blocks(Registries.getBlock("kubejs:molecular_coil")))
                    .where("G", Predicates.blocks(Registries.getBlock("kubejs:dimensional_bridge_casing")))
                    .where("H", Predicates.blocks(GTBlocks.HIGH_POWER_CASING.get()))
                    .where("I", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("J", Predicates.blocks(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get()))
                    .where("M", Predicates.blocks(Registries.getBlock("kubejs:hollow_casing")))
                    .where("K", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get()))
                    .where("L", Predicates.blocks(GTLBlocks.MOLECULAR_CASING.get())
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1))
                            .or(Predicates.abilities(PartAbility.OPTICAL_DATA_RECEPTION).setExactLimit(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMaxGlobalLimited(2))
                            .or(Predicates.abilities(PartAbility.INPUT_LASER).setMaxGlobalLimited(1)))
                    .where(" ", Predicates.any())
                    .build())
            .workableCasingRenderer(GTLCore.id("block/molecular_casing"), GTCEu.id("block/multiblock/fusion_reactor"))
            .register();
}
