package org.gtlcore.gtlcore.data.recipe;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.data.GTLMachines;
import org.gtlcore.gtlcore.common.data.machines.AdditionalMultiBlockMachine;
import org.gtlcore.gtlcore.common.data.machines.AdvancedMultiBlockMachineA;
import org.gtlcore.gtlcore.common.recipe.condition.GravityCondition;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.utils.Registries;

import com.gregtechceu.gtceu.api.data.chemical.material.stack.UnificationEntry;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gregtechceu.gtceu.common.data.GTMaterials;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.data.recipe.CustomTags;
import com.gregtechceu.gtceu.data.recipe.VanillaRecipeHelper;

import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import appeng.api.util.AEColor;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;

import java.util.function.Consumer;

import static appeng.core.definitions.AEBlocks.CRAFTING_MONITOR;
import static com.glodblock.github.extendedae.common.EPPItemAndBlock.*;
import static com.gregtechceu.gtceu.api.GTValues.*;
import static com.gregtechceu.gtceu.api.data.tag.TagPrefix.*;
import static com.gregtechceu.gtceu.common.data.GTItems.*;
import static com.gregtechceu.gtceu.common.data.GTMachines.BUFFER;
import static com.gregtechceu.gtceu.common.data.GTMaterials.*;
import static com.gregtechceu.gtceu.common.data.GTRecipeTypes.*;
import static org.gtlcore.gtlcore.common.data.GTLItems.*;
import static org.gtlcore.gtlcore.common.data.GTLMachines.GTAEMachines.ME_CRAFTING_CPU_INTERFACE;
import static org.gtlcore.gtlcore.common.data.GTLMaterials.*;
import static org.gtlcore.gtlcore.common.data.GTLRecipeTypes.*;
import static org.gtlcore.gtlcore.common.data.machines.MultiBlockMachineB.PRIMITIVE_VOID_ORE;

public class Misc {

    public static void init(Consumer<FinishedRecipe> provider) {
        VanillaRecipeHelper.addShapelessRecipe(provider, "programmed_circuit", IntCircuitBehaviour.stack(0),
                "A", CustomTags.LV_CIRCUITS);
        VanillaRecipeHelper.addShapelessRecipe(provider, "structure_detect", GTLItems.STRUCTURE_DETECT.asStack(),
                "A", Items.PAPER);
        VanillaRecipeHelper.addShapelessRecipe(provider, GTLCore.id("me_pattern_buffer_copy"),
                GTLItems.ME_PATTERN_BUFFER_COPY.asStack(),
                "A", AEItems.MEMORY_CARD);
        VanillaRecipeHelper.addShapelessRecipe(provider, GTLCore.id("me_pattern_buffer_cut"),
                GTLItems.ME_PATTERN_BUFFER_CUT.asStack(),
                "A", GTLItems.ME_PATTERN_BUFFER_COPY.asStack());
        VanillaRecipeHelper.addShapelessRecipe(provider, GTLCore.id("pattern_box"),
                GTLItems.PATTERN_BOX.asStack(),
                "A", new ItemStack(PACKING_TAPE));
        VanillaRecipeHelper.addShapedRecipe(provider, true, GTLCore.id("heat_sensor"),
                GTLMachines.HEAT_SENSOR.asStack(),
                "FDF", "BAB", "CEC",
                'A', GTMachines.HULL[12].asStack(),
                'B', new UnificationEntry(wireFine, EnrichedNaquadahTriniumEuropiumDuranide),
                'C', SENSOR_ZPM.asStack(),
                'D', Registries.getItemStack("gtceu:advanced_activity_detector_cover"),
                'E', Registries.getItemStack("gtceu:computer_monitor_cover"),
                'F', CustomTags.UV_CIRCUITS);
        VanillaRecipeHelper.addShapedRecipe(provider, true, GTLCore.id("fast_infinity_cell_0"),
                GTLItems.FAST_INFINITY_CELL.asStack(),
                "AAA", "BCD", "AAA",
                'A', AEItems.QUANTUM_ENTANGLED_SINGULARITY.stack(),
                'B', GTLItems.ITEM_INFINITY_CELL.asStack(),
                'C', ZERO_POINT_MODULE.asStack(),
                'D', GTLItems.FLUID_INFINITY_CELL.asStack());
        VanillaRecipeHelper.addShapedRecipe(provider, true, GTLCore.id("fast_infinity_cell_1"),
                GTLItems.FAST_INFINITY_CELL.asStack(),
                "AAA", "DCB", "AAA",
                'A', AEItems.QUANTUM_ENTANGLED_SINGULARITY.stack(),
                'B', GTLItems.ITEM_INFINITY_CELL.asStack(),
                'C', ZERO_POINT_MODULE.asStack(),
                'D', GTLItems.FLUID_INFINITY_CELL.asStack());
        if (ConfigHolder.INSTANCE.content.enablePrimitiveVoidOre) {
            VanillaRecipeHelper.addShapedRecipe(provider, true, "primitive_void_ore_recipes",
                    PRIMITIVE_VOID_ORE.asStack(), "DCD", "CGC", "DCD",
                    'D', Blocks.DIRT.asItem(),
                    'C', Items.STONE_PICKAXE.asItem(),
                    'G', new UnificationEntry(TagPrefix.block, GTMaterials.Iron));
            PRIMITIVE_VOID_ORE_RECIPES.recipeBuilder("primitive_void_ore_recipes")
                    .inputFluids(GTMaterials.Steam.getFluid(1000))
                    .duration(200)
                    .save(provider);
        }

        VanillaRecipeHelper.addShapelessRecipe(provider, "simulation_machine",
                AdvancedMultiBlockMachineA.SIMULATION_MACHINE.asStack(), "A", Blocks.COBBLESTONE);

        WOOD_DISTILLATION_RECIPES.recipeBuilder("wood_distillation_recipes")
                .inputItems(ItemTags.LOGS, 16)
                .inputFluids(Nitrogen.getFluid(1000))
                .outputItems(dust, DarkAsh, 8)
                .outputFluids(Water.getFluid(800))
                .outputFluids(Carbon.getFluid(490))
                .outputFluids(Methanol.getFluid(480))
                .outputFluids(Benzene.getFluid(350))
                .outputFluids(CarbonMonoxide.getFluid(340))
                .outputFluids(Creosote.getFluid(300))
                .outputFluids(Dimethylbenzene.getFluid(240))
                .outputFluids(AceticAcid.getFluid(160))
                .outputFluids(Methane.getFluid(130))
                .outputFluids(Acetone.getFluid(80))
                .outputFluids(Phenol.getFluid(75))
                .outputFluids(Toluene.getFluid(75))
                .outputFluids(Ethylene.getFluid(20))
                .outputFluids(Hydrogen.getFluid(20))
                .outputFluids(MethylAcetate.getFluid(16))
                .outputFluids(Ethanol.getFluid(16))
                .duration(200).EUt(VA[MV])
                .save(provider);

        AUTOCLAVE_RECIPES.recipeBuilder("water_agar_mix").EUt(VA[HV]).duration(600)
                .inputItems(dust, Gelatin)
                .inputFluids(DistilledWater.getFluid(1000))
                .outputFluids(WaterAgarMix.getFluid(1000))
                .cleanroom(CleanroomType.STERILE_CLEANROOM)
                .save(provider);

        DEHYDRATOR_RECIPES.recipeBuilder("agar")
                .inputFluids(WaterAgarMix.getFluid(1000))
                .outputItems(dust, Agar, 1)
                .duration(420).EUt(VA[MV])
                .cleanroom(CleanroomType.STERILE_CLEANROOM)
                .save(provider);

        CHEMICAL_RECIPES.recipeBuilder("soda_ash_from_carbon_dioxide")
                .circuitMeta(2)
                .inputItems(dust, SodiumHydroxide, 6)
                .inputFluids(CarbonDioxide.getFluid(1000))
                .outputItems(dust, SodaAsh, 6)
                .outputFluids(Water.getFluid(1000))
                .duration(80).EUt(VA[HV])
                .save(provider);

        BLAST_RECIPES.recipeBuilder("engraved_crystal_chip_from_emerald")
                .inputItems(plate, Emerald)
                .inputItems(RAW_CRYSTAL_CHIP)
                .inputFluids(Helium.getFluid(1000))
                .outputItems(ENGRAVED_CRYSTAL_CHIP)
                .blastFurnaceTemp(5000)
                .duration(900).EUt(VA[HV])
                .addCondition(new GravityCondition(true))
                .save(provider);

        BLAST_RECIPES.recipeBuilder("engraved_crystal_chip_from_olivine")
                .inputItems(plate, Olivine)
                .inputItems(RAW_CRYSTAL_CHIP)
                .inputFluids(Helium.getFluid(1000))
                .outputItems(ENGRAVED_CRYSTAL_CHIP)
                .blastFurnaceTemp(5000)
                .duration(900).EUt(VA[HV])
                .addCondition(new GravityCondition(true))
                .save(provider);

        CHEMICAL_BATH_RECIPES.recipeBuilder("quantum_star")
                .inputItems(gem, NetherStar)
                .inputFluids(Radon.getFluid(1250))
                .outputItems(QUANTUM_STAR)
                .duration(1920).EUt(VA[HV])
                .addCondition(new GravityCondition(true))
                .save(provider);

        AUTOCLAVE_RECIPES.recipeBuilder("gravi_star")
                .inputItems(QUANTUM_STAR)
                .inputFluids(Neutronium.getFluid(L * 2))
                .outputItems(GRAVI_STAR)
                .duration(480).EUt(VA[IV])
                .addCondition(new GravityCondition(true))
                .save(provider);

        CHEMICAL_BATH_RECIPES.recipeBuilder("quantum_eye")
                .inputItems(gem, EnderEye)
                .inputFluids(Radon.getFluid(250))
                .outputItems(QUANTUM_EYE)
                .duration(480).EUt(VA[HV])
                .addCondition(new GravityCondition(true))
                .save(provider);

        LIGHTNING_PROCESSOR_RECIPES.recipeBuilder("ender_pearl_dust").duration(400).EUt(VA[LV])
                .inputItems(dust, Beryllium)
                .inputItems(dust, Potassium, 4)
                .inputFluids(Nitrogen.getFluid(5000))
                .circuitMeta(1)
                .outputItems(dust, EnderPearl, 10)
                .save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("molecular_assembler_matrix")
                .inputItems(frameGt, NaquadahAlloy, 16)
                .inputItems(FIELD_GENERATOR_UV, 8)
                .inputItems(ULTIMATE_BATTERY)
                .inputItems(CustomTags.UHV_CIRCUITS, 16)
                .inputItems(wireFine, RutheniumTriniumAmericiumNeutronate, 64)
                .inputItems(wireFine, RutheniumTriniumAmericiumNeutronate, 64)
                .inputItems(plate, AbyssalAlloy, 16)
                .inputItems(EX_INTERFACE.asItem(), 8)
                .inputItems(INGREDIENT_BUFFER.asItem(), 8)
                .inputItems(EX_IO_PORT.asItem(), 8)
                .inputItems(EX_PATTERN_PROVIDER.asItem(), 8)
                .inputItems(EX_ASSEMBLER.asItem(), 8)
                .inputFluids(MutatedLivingSolder.getFluid(2304))
                .inputFluids(Highurabilityompoundteel.getFluid(1728))
                .inputFluids(Antimatter.getFluid(4000))
                .inputFluids(Polyetheretherketone.getFluid(2340))
                .outputItems(AdditionalMultiBlockMachine.MOLECULAR_ASSEMBLER_MATRIX)
                .EUt(VA[9]).duration(200)
                .stationResearch((b) -> b.researchStack(EX_ASSEMBLER.asItem().getDefaultInstance())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack()).EUt(VA[9]).CWUt(256))
                .save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("me_molecular_assembler_io")
                .inputItems(frameGt, Neutronium, 16)
                .inputItems(SENSOR_UV, 8)
                .inputItems(EMITTER_UV, 8)
                .inputItems(CustomTags.UHV_CIRCUITS, 4)
                .inputItems(EX_INTERFACE.asItem(), 32)
                .inputItems(INGREDIENT_BUFFER.asItem(), 32)
                .inputItems(EX_IO_PORT.asItem(), 32)
                .inputItems(AEItems.QUANTUM_ENTANGLED_SINGULARITY.asItem(), 2)
                .inputItems(AEItems.QUANTUM_ENTANGLED_SINGULARITY.asItem(), 2)
                .inputItems(AEItems.SPATIAL_128_CELL_COMPONENT.asItem(), 16)
                .inputFluids(MutatedLivingSolder.getFluid(1152))
                .inputFluids(Naquadria.getFluid(1728))
                .inputFluids(Plutonium241.getFluid(2340))
                .inputFluids(Mithril.getFluid(2340))
                .outputItems(GTLMachines.GTAEMachines.ME_MOLECULAR_ASSEMBLER_IO)
                .EUt(VA[9]).duration(200)
                .stationResearch((b) -> b.researchStack(EX_IO_PORT.asItem().getDefaultInstance())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack()).EUt(VA[9]).CWUt(128))
                .save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("me_craft_speed_core")
                .inputItems(GTMachines.WORLD_ACCELERATOR[UV], 2)
                .inputItems(FLUID_REGULATOR_UV, 8)
                .inputItems(CONVEYOR_MODULE_UV, 8)
                .inputItems(Registries.getItemStack("kubejs:bioware_processing_core", 4))
                .inputItems(AEItems.SPEED_CARD.asItem(), 64)
                .inputItems(AEItems.SINGULARITY.asItem(), 64)
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.RED, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.RED, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.RED, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.RED, 64))
                .inputFluids(MutatedLivingSolder.getFluid(1152))
                .inputFluids(Nobelium.getFluid(1728))
                .inputFluids(Orichalcum.getFluid(2340))
                .inputFluids(Mithril.getFluid(2340))
                .outputItems(GTLMachines.GTAEMachines.ME_CRAFT_SPEED_CORE)
                .EUt(VA[9]).duration(200)
                .stationResearch((b) -> b.researchStack(GTMachines.WORLD_ACCELERATOR[UV].asStack())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack()).EUt(VA[9]).CWUt(128))
                .save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("me_craft_parallel_core")
                .inputItems(ELECTRIC_MOTOR_UV, 16)
                .inputItems(FIELD_GENERATOR_UV, 4)
                .inputItems(Registries.getItemStack("kubejs:bioware_processing_core", 4))
                .inputItems(EX_ASSEMBLER.asItem(), 64)
                .inputItems(AEItems.CRAFTING_CARD.asItem(), 64)
                .inputItems(AEItems.SINGULARITY.asItem(), 64)
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.PURPLE, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.PURPLE, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.PURPLE, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.PURPLE, 64))
                .inputFluids(MutatedLivingSolder.getFluid(1152))
                .inputFluids(Neptunium.getFluid(1728))
                .inputFluids(Orichalcum.getFluid(2340))
                .inputFluids(Mithril.getFluid(2340))
                .outputItems(GTLMachines.GTAEMachines.ME_CRAFT_PARALLEL_CORE)
                .EUt(VA[9]).duration(200)
                .stationResearch((b) -> b.researchStack(AEBlocks.MOLECULAR_ASSEMBLER.stack())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack()).EUt(VA[9]).CWUt(128))
                .save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("me_crafting_cpu_interface")
                .inputItems(frameGt, Quantanium, 16)
                .inputItems(GTMachines.HULL[UV])
                .inputItems(FIELD_GENERATOR_UHV, 8)
                .inputItems(EMITTER_UHV, 4)
                .inputItems(SENSOR_UHV, 4)
                .inputItems(BUFFER[UHV], 32)
                .inputItems(CRAFTING_MONITOR.asItem(), 32)
                .inputItems(CustomTags.UHV_CIRCUITS, 4)
                .inputItems(CELL_COMPONENT_256M, 16)
                .inputFluids(AminatedFullerene.getFluid(4000))
                .inputFluids(CarbonNanotubes.getFluid(5760))
                .inputFluids(DegenerateRhenium.getFluid(8000))
                .inputFluids(PolyurethaneResin.getFluid(8000))
                .outputItems(ME_CRAFTING_CPU_INTERFACE)
                .stationResearch(b -> b
                        .researchStack(Registries.getItemStack("mae2:256x_crafting_accelerator"))
                        .dataStack(TOOL_DATA_MODULE.asStack()).EUt(VA[UHV]).CWUt(256))
                .EUt(VA[UHV]).duration(800)
                .save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("me_craft_pattern_container")
                .inputItems(GTMachines.HULL[UV])
                .inputItems(FIELD_GENERATOR_UV, 4)
                .inputItems(CustomTags.UV_CIRCUITS)
                .inputItems(EX_PATTERN_PROVIDER.asItem(), 64)
                .inputItems(AEItems.CAPACITY_CARD.asItem(), 64)
                .inputItems(AEItems.SINGULARITY.asItem(), 64)
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.BLUE, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.BLUE, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.BLUE, 64))
                .inputItems(AEItems.COLORED_LUMEN_PAINT_BALL.stack(AEColor.BLUE, 64))
                .inputFluids(MutatedLivingSolder.getFluid(1152))
                .inputFluids(Mendelevium.getFluid(1728))
                .inputFluids(DamascusSteel.getFluid(2304))
                .inputFluids(Titanium50.getFluid(2304))
                .outputItems(GTLMachines.GTAEMachines.ME_CRAFT_PATTERN_CONTAINER)
                .EUt(VA[9]).duration(200)
                .stationResearch((b) -> b.researchStack(EX_PATTERN_PROVIDER.asItem().getDefaultInstance())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack()).EUt(VA[9]).CWUt(128))
                .save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("max_fluid_regulator")
                .inputItems(ELECTRIC_PUMP_MAX)
                .inputItems(CustomTags.MAX_CIRCUITS, 2)
                .circuitMeta(1)
                .outputItems(FLUID_REGULATOR_MAX)
                .EUt(VA[13]).duration(100)
                .save(provider);
    }
}
