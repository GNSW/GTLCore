package org.gtlcore.gtlcore.data.recipe;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.data.tag.GTLTagPrefix;
import org.gtlcore.gtlcore.common.data.*;
import org.gtlcore.gtlcore.common.data.machines.*;
import org.gtlcore.gtlcore.utils.Registries;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.UnificationEntry;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.data.tag.TagUtil;
import com.gregtechceu.gtceu.api.fluids.store.FluidStorageKeys;
import com.gregtechceu.gtceu.api.item.ComponentItem;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gregtechceu.gtceu.common.data.GTMaterials;
import com.gregtechceu.gtceu.common.data.machines.GTResearchMachines;
import com.gregtechceu.gtceu.data.recipe.CustomTags;
import com.gregtechceu.gtceu.data.recipe.VanillaRecipeHelper;

import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraftforge.fml.ModList;

import appeng.core.definitions.AEItems;
import com.hepdd.gtmthings.GTMThings;
import com.hepdd.gtmthings.data.CustomItems;
import com.hepdd.gtmthings.data.WirelessMachines;
import com.tterrag.registrate.util.entry.ItemEntry;
import earth.terrarium.adastra.common.registry.ModItems;
import org.apache.commons.lang3.ArrayUtils;

import java.util.List;
import java.util.function.Consumer;

import static appeng.core.definitions.AEBlocks.QUANTUM_LINK;
import static appeng.core.definitions.AEBlocks.QUANTUM_RING;
import static com.glodblock.github.extendedae.common.EPPItemAndBlock.WIRELESS_HUB;
import static com.glodblock.github.extendedae.common.EPPItemAndBlock.WIRELESS_TOOL;
import static com.gregtechceu.gtceu.api.GTValues.*;
import static com.gregtechceu.gtceu.api.data.tag.TagPrefix.*;
import static com.gregtechceu.gtceu.common.data.GTItems.*;
import static com.gregtechceu.gtceu.common.data.GTMaterials.*;
import static com.gregtechceu.gtceu.common.data.GTRecipeTypes.ASSEMBLER_RECIPES;
import static com.gregtechceu.gtceu.common.data.GTRecipeTypes.ASSEMBLY_LINE_RECIPES;
import static com.gregtechceu.gtceu.data.recipe.CraftingComponent.*;
import static com.gregtechceu.gtceu.data.recipe.misc.MetaTileEntityLoader.registerMachineRecipe;
import static org.gtlcore.gtlcore.common.data.GTLBlocks.CRAFTING_STORAGE_MAX;
import static org.gtlcore.gtlcore.common.data.GTLMachines.GTAEMachines.*;
import static org.gtlcore.gtlcore.common.data.GTLMachines.TAG_FILTER_ME_STOCK_BUS_PART_MACHINE;
import static org.gtlcore.gtlcore.common.data.GTLMaterials.*;
import static org.gtlcore.gtlcore.common.data.GTLRecipeTypes.SPACE_COSMIC_PROBE_RECEIVERS_RECIPES;
import static org.gtlcore.gtlcore.common.data.machines.AdditionalMultiBlockMachine.TRANSFINITE_COMPUTATION_ARRAY;
import static org.gtlcore.gtlcore.integration.ae2.wireless.GTLWirelessAeContent.WIRELESS_NETWORK_BOOKMARK;
import static org.gtlcore.gtlcore.integration.ae2.wireless.GTLWirelessAeContent.WIRELESS_NETWORK_CORE;
import static org.gtlcore.gtlcore.integration.wildcard.WildcardPatternCompatImpl.ME_STOCKING_WILDCARD_PATTERN_BUFFER;
import static org.gtlcore.gtlcore.integration.wildcard.WildcardPatternCompatImpl.ME_WILDCARD_PATTERN_BUFFER;
import static org.gtlcore.gtlcore.utils.Registries.getItem;

public class MachineRecipe {

    public static void init(Consumer<FinishedRecipe> provider) {
        VanillaRecipeHelper.addShapedRecipe(provider, true, "casing_uev", GTBlocks.MACHINE_CASING_UEV.asStack(),
                "PPP",
                "PwP", "PPP", 'P', new UnificationEntry(TagPrefix.plate, GTLMaterials.Quantanium));
        VanillaRecipeHelper.addShapedRecipe(provider, true, "casing_uiv", GTBlocks.MACHINE_CASING_UIV.asStack(),
                "PPP",
                "PwP", "PPP", 'P', new UnificationEntry(TagPrefix.plate, GTLMaterials.Adamantium));
        VanillaRecipeHelper.addShapedRecipe(provider, true, "casing_uxv", GTBlocks.MACHINE_CASING_UXV.asStack(),
                "PPP",
                "PwP", "PPP", 'P', new UnificationEntry(TagPrefix.plate, GTLMaterials.Vibranium));
        VanillaRecipeHelper.addShapedRecipe(provider, true, "casing_opv", GTBlocks.MACHINE_CASING_OpV.asStack(),
                "PPP",
                "PwP", "PPP", 'P', new UnificationEntry(TagPrefix.plate, GTLMaterials.Draconium));
        VanillaRecipeHelper.addShapedRecipe(provider, true, "casing_max", GTBlocks.MACHINE_CASING_MAX.asStack(),
                "PPP",
                "PwP", "PPP", 'P', new UnificationEntry(TagPrefix.plate, GTLMaterials.Chaos));
        ASSEMBLER_RECIPES.recipeBuilder("casing_uev").EUt(16).inputItems(plate, GTLMaterials.Quantanium, 8)
                .outputItems(GTBlocks.MACHINE_CASING_UEV.asStack()).circuitMeta(8).duration(50)
                .save(provider);
        ASSEMBLER_RECIPES.recipeBuilder("casing_uiv").EUt(16).inputItems(plate, GTLMaterials.Adamantium, 8)
                .outputItems(GTBlocks.MACHINE_CASING_UIV.asStack()).circuitMeta(8).duration(50)
                .save(provider);
        ASSEMBLER_RECIPES.recipeBuilder("casing_uxv").EUt(16).inputItems(plate, GTLMaterials.Vibranium, 8)
                .outputItems(GTBlocks.MACHINE_CASING_UXV.asStack()).circuitMeta(8).duration(50)
                .save(provider);
        ASSEMBLER_RECIPES.recipeBuilder("casing_opv").EUt(16).inputItems(plate, GTLMaterials.Draconium, 8)
                .outputItems(GTBlocks.MACHINE_CASING_OpV.asStack()).circuitMeta(8).duration(50)
                .save(provider);
        ASSEMBLER_RECIPES.recipeBuilder("casing_max").EUt(16).inputItems(plate, GTLMaterials.Chaos, 8)
                .outputItems(GTBlocks.MACHINE_CASING_MAX.asStack()).circuitMeta(8).duration(50)
                .save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("rotor_hatch_a")
                .inputItems(GTMachines.ITEM_IMPORT_BUS[EV], 1)
                .inputItems(COVER_ITEM_DETECTOR_ADVANCED)
                .inputItems(pipeLargeRestrictive, SterlingSilver, 4)
                .inputItems(CONVEYOR_MODULE_EV, 16)
                .inputFluids(Polyethylene.getFluid(L))
                .outputItems(GTLMachines.ROTOR_HATCH)
                .duration(400).EUt(480).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("adv")
                .inputItems(AdvancedMultiBlockMachineB.FLUID_DRILLING_RIG[ZPM])
                .inputItems(CIRCUIT.getIngredient(UEV), 4)
                .inputItems(CONVEYOR_MODULE_UHV, 4)
                .inputItems(FLUID_REGULATOR_UHV, 4)
                .outputItems(AdvancedMultiBlockMachineB.ADVANCED_INFINITE_DRILLER)
                .duration(400).EUt(V[UEV]).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("virtual_ingredient")
                .inputItems(ROBOT_ARM_IV, 2)
                .inputItems(FLUID_REGULATOR_IV, 2)
                .inputItems(TagPrefix.plate, Duranium, 4)
                .inputItems(CustomTags.LuV_CIRCUITS, 2)
                .inputFluids(SolderingAlloy.getFluid(1440))
                .outputItems(GTLItems.VIRTUAL_INGREDIENT)
                .duration(200).EUt(VA[EV]).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("block_bus")
                .inputItems(CONVEYOR_MODULE_LuV, 2)
                .inputItems(ROBOT_ARM_LuV, 2)
                .inputItems(EMITTER_LuV, 2)
                .inputItems(GTMachines.ITEM_IMPORT_BUS[LuV], 1)
                .inputItems(ITEM_FILTER)
                .inputItems(Registries.getBlock("kubejs:essence_block"))
                .inputFluids(SolderingAlloy.getFluid(L))
                .outputItems(GTLMachines.BLOCK_BUS)
                .duration(200).EUt(V[LuV]).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("wireless_data_hatch_transmitter")
                .inputItems(GTResearchMachines.DATA_HATCH_TRANSMITTER)
                .inputItems(CIRCUIT.getIngredient(LuV), 1)
                .inputItems(EMITTER_LuV)
                .inputFluids(SolderingAlloy.getFluid(L * 2))
                .outputItems(GTLMachines.WIRELESS_DATA_HATCH_TRANSMITTER)
                .duration(200)
                .EUt(GTValues.VA[LuV])
                .save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("wireless_data_hatch_receiver")
                .inputItems(GTResearchMachines.DATA_HATCH_RECEIVER)
                .inputItems(CIRCUIT.getIngredient(LuV), 1)
                .inputItems(EMITTER_LuV)
                .inputFluids(SolderingAlloy.getFluid(L * 2))
                .outputItems(GTLMachines.WIRELESS_DATA_HATCH_RECEIVER)
                .duration(200)
                .EUt(GTValues.VA[LuV])
                .save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("conversion_simulate_card")
                .inputItems(FIELD_GENERATOR_LuV)
                .inputItems(AEItems.SPEED_CARD, 2)
                .inputItems(AdvancedMultiBlockMachineA.BLOCK_CONVERSION_ROOM, 1)
                .inputItems(CIRCUIT.getIngredient(LuV), 2)
                .inputFluids(SolderingAlloy.getFluid(L))
                .outputItems(GTLItems.CONVERSION_SIMULATE_CARD)
                .duration(200).EUt(V[LuV]).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("hull_uhv").duration(50).EUt(16)
                .inputItems(GTBlocks.MACHINE_CASING_UHV)
                .inputItems(cableGtSingle, Europium, 2)
                .inputFluids(GTLMaterials.Polyetheretherketone.getFluid(L * 2))
                .outputItems(GTMachines.HULL[9]).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("hull_uev").duration(50).EUt(16)
                .inputItems(GTBlocks.MACHINE_CASING_UEV.asStack())
                .inputItems(cableGtSingle, GTLMaterials.Mithril, 2)
                .inputFluids(GTLMaterials.Polyetheretherketone.getFluid(L * 2))
                .outputItems(GTMachines.HULL[10]).save(provider);
        ASSEMBLER_RECIPES.recipeBuilder("hull_uiv").duration(50).EUt(16)
                .inputItems(GTBlocks.MACHINE_CASING_UIV.asStack())
                .inputItems(cableGtSingle, GTMaterials.Neutronium, 2)
                .inputFluids(GTLMaterials.Zylon.getFluid(L * 2))
                .outputItems(GTMachines.HULL[11]).save(provider);
        ASSEMBLER_RECIPES.recipeBuilder("hull_uxv").duration(50).EUt(16)
                .inputItems(GTBlocks.MACHINE_CASING_UXV.asStack())
                .inputItems(cableGtSingle, GTLMaterials.Taranium, 2)
                .inputFluids(GTLMaterials.Zylon.getFluid(L * 2))
                .outputItems(GTMachines.HULL[12]).save(provider);
        ASSEMBLER_RECIPES.recipeBuilder("hull_opv").duration(50).EUt(16)
                .inputItems(GTBlocks.MACHINE_CASING_OpV.asStack())
                .inputItems(cableGtSingle, GTLMaterials.Crystalmatrix, 2)
                .inputFluids(GTLMaterials.FullerenePolymerMatrixPulp.getFluid(L * 2))
                .outputItems(GTMachines.HULL[13]).save(provider);
        ASSEMBLER_RECIPES.recipeBuilder("hull_max").duration(50).EUt(16)
                .inputItems(GTBlocks.MACHINE_CASING_MAX.asStack())
                .inputItems(cableGtSingle, GTLMaterials.CosmicNeutronium, 2)
                .inputFluids(GTLMaterials.Radox.getFluid(L * 2))
                .outputItems(GTMachines.HULL[14]).save(provider);

        var multiHatchMaterials = new Material[] {
                GTMaterials.Neutronium, GTLMaterials.Enderium, GTLMaterials.Enderium,
                GTLMaterials.HeavyQuarkDegenerateMatter,
                GTLMaterials.HeavyQuarkDegenerateMatter,
        };
        for (int i = 0; i < multiHatchMaterials.length; i++) {
            var tier = GTMachines.MULTI_HATCH_TIERS[i + 6];
            var tierName = VN[tier].toLowerCase();

            var material = multiHatchMaterials[i];

            var importHatch = GTMachines.FLUID_IMPORT_HATCH[tier];
            var exportHatch = GTMachines.FLUID_EXPORT_HATCH[tier];

            var importHatch4x = GTMachines.FLUID_IMPORT_HATCH_4X[tier];
            var exportHatch4x = GTMachines.FLUID_EXPORT_HATCH_4X[tier];
            var importHatch9x = GTMachines.FLUID_IMPORT_HATCH_9X[tier];
            var exportHatch9x = GTMachines.FLUID_EXPORT_HATCH_9X[tier];

            VanillaRecipeHelper.addShapedRecipe(
                    provider, true, "fluid_import_hatch_4x_" + tierName,
                    importHatch4x.asStack(), "P", "M",
                    'M', importHatch.asStack(),
                    'P', new UnificationEntry(TagPrefix.pipeQuadrupleFluid, material));
            VanillaRecipeHelper.addShapedRecipe(
                    provider, true, "fluid_export_hatch_4x_" + tierName,
                    exportHatch4x.asStack(), "M", "P",
                    'M', exportHatch.asStack(),
                    'P', new UnificationEntry(TagPrefix.pipeQuadrupleFluid, material));
            VanillaRecipeHelper.addShapedRecipe(
                    provider, true, "fluid_import_hatch_9x_" + tierName,
                    importHatch9x.asStack(), "P", "M",
                    'M', importHatch.asStack(),
                    'P', new UnificationEntry(TagPrefix.pipeNonupleFluid, material));
            VanillaRecipeHelper.addShapedRecipe(
                    provider, true, "fluid_export_hatch_9x_" + tierName,
                    exportHatch9x.asStack(), "M", "P",
                    'M', exportHatch.asStack(),
                    'P', new UnificationEntry(TagPrefix.pipeNonupleFluid, material));
        }
        VanillaRecipeHelper.addShapedRecipe(provider, true, "rotor_holder_uhv", GTMachines.ROTOR_HOLDER[UHV].asStack(),
                "SGS", "GHG", "SGS", 'H', GTMachines.HULL[GTValues.UHV].asStack(), 'G',
                new UnificationEntry(TagPrefix.gear, GTLMaterials.Orichalcum), 'S',
                new UnificationEntry(TagPrefix.gearSmall, GTMaterials.Neutronium));
        VanillaRecipeHelper.addShapedRecipe(provider, true, "rotor_holder_uev", GTMachines.ROTOR_HOLDER[UEV].asStack(),
                "SGS", "GHG", "SGS", 'H', GTMachines.HULL[GTValues.UEV].asStack(), 'G',
                new UnificationEntry(TagPrefix.gear, GTLMaterials.AstralTitanium), 'S',
                new UnificationEntry(TagPrefix.gearSmall, GTLMaterials.Quantanium));
        VanillaRecipeHelper.addShapedRecipe(provider, true, GTLCore.id("ultimate_terminal"),
                GTLItems.ULTIMATE_TERMINAL.asStack(),
                "ABA", "CDC", "CEC",
                'A', new UnificationEntry(TagPrefix.screw, GTMaterials.BlackBronze),
                'B', net.minecraftforge.common.Tags.Items.GLASS_PANES,
                'C', new UnificationEntry(TagPrefix.plate, GTMaterials.Silicon),
                'D', CustomItems.ADVANCED_TERMINAL.asStack(),
                'E', CustomTags.MV_CIRCUITS);
        registerMachineRecipe(provider, ArrayUtils.subarray(GTMachines.TRANSFORMER, GTValues.UHV, GTValues.MAX),
                "WCC",
                "TH ", "WCC", 'W', POWER_COMPONENT, 'C', CABLE, 'T', CABLE_TIER_UP, 'H', HULL);
        registerMachineRecipe(provider,
                ArrayUtils.subarray(GTMachines.HI_AMP_TRANSFORMER_2A, GTValues.UHV, GTValues.MAX),
                "WCC", "TH ", "WCC",
                'W', POWER_COMPONENT, 'C', CABLE_DOUBLE, 'T', CABLE_TIER_UP_DOUBLE, 'H', HULL);
        registerMachineRecipe(provider,
                ArrayUtils.subarray(GTMachines.HI_AMP_TRANSFORMER_4A, GTValues.UHV, GTValues.MAX),
                "WCC", "TH ", "WCC",
                'W', POWER_COMPONENT, 'C', CABLE_QUAD, 'T', CABLE_TIER_UP_QUAD, 'H', HULL);
        registerMachineRecipe(provider, GTLMachines.DEHYDRATOR, "WCW", "AMA", "PRP", 'M', HULL, 'P', PLATE, 'C',
                CIRCUIT, 'W', WIRE_QUAD, 'R', ROBOT_ARM, 'A', CABLE_QUAD);
        registerMachineRecipe(provider, GTLMachines.LIGHTNING_PROCESSOR, "WEW", "AMA", "WSW", 'M', HULL, 'E',
                EMITTER, 'W', WIRE_HEX, 'S', SENSOR, 'A', CABLE_TIER_UP);

        ASSEMBLER_RECIPES.recipeBuilder("zpm_fluid_drilling_rig")
                .inputItems(GTMachines.HULL[UV])
                .inputItems(frameGt, Ruridit, 4)
                .inputItems(CustomTags.UV_CIRCUITS, 4)
                .inputItems(GTItems.ELECTRIC_MOTOR_UV, 4)
                .inputItems(GTItems.ELECTRIC_PUMP_UV, 4)
                .inputItems(gear, Neutronium, 4)
                .circuitMeta(2)
                .outputItems(AdvancedMultiBlockMachineB.FLUID_DRILLING_RIG[ZPM])
                .duration(400).EUt(VA[UV]).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("wood_distillation")
                .inputItems(MultiBlockMachineA.LARGE_PYROLYSE_OVEN, 2)
                .inputItems(GCyMMachines.LARGE_DISTILLERY, 4)
                .inputItems(CustomTags.LuV_CIRCUITS, 16)
                .inputItems(GTItems.EMITTER_LuV, 4)
                .inputItems(pipeHugeFluid, StainlessSteel, 8)
                .inputItems(GTItems.ELECTRIC_PUMP_IV, 8)
                .inputItems(WatertightSteel, 16)
                .inputItems(plateDouble, StainlessSteel, 32)
                .inputFluids(SolderingAlloy.getFluid(1296))
                .outputItems(MultiBlockMachineB.WOOD_DISTILLATION)
                .duration(400).EUt(VA[LuV])
                .save(provider);

        VanillaRecipeHelper.addShapedRecipe(provider, true, "generator_array",
                GeneratorMachine.GENERATOR_ARRAY.asStack(),
                "ABA", "BCB", "ABA", 'A', new UnificationEntry(plate, Steel),
                'B', CustomTags.LV_CIRCUITS, 'C', GTItems.EMITTER_LV.asStack());

        registerMachineRecipe(provider, GTMachines.FLUID_IMPORT_HATCH, " G", " M", 'M', HULL, 'G', GLASS);
        registerMachineRecipe(provider, GTMachines.FLUID_EXPORT_HATCH, " M", " G", 'M', HULL, 'G', GLASS);

        registerMachineRecipe(provider, GTMachines.ITEM_IMPORT_BUS, " C", " M", 'M', HULL, 'C',
                TagUtil.createItemTag("chests/wooden"));
        registerMachineRecipe(provider, GTMachines.ITEM_EXPORT_BUS, " M", " C", 'M', HULL, 'C',
                TagUtil.createItemTag("chests/wooden"));

        registerMachineRecipe(provider, GTMachines.DUAL_IMPORT_HATCH, "PG", "CM", 'P', PIPE_NONUPLE, 'M', HULL,
                'G', GLASS, 'C', CraftingComponentAddition.BUFFER);
        registerMachineRecipe(provider, GTMachines.DUAL_EXPORT_HATCH, "MG", "CP", 'P', PIPE_NONUPLE, 'M', HULL,
                'G', GLASS, 'C', CraftingComponentAddition.BUFFER);

        VanillaRecipeHelper.addShapedRecipe(provider, true, "hermetic_casing_uev",
                GTLBlocks.HERMETIC_CASING_UEV.asStack(), "PPP", "PFP", "PPP", 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Quantanium), 'F',
                new UnificationEntry(TagPrefix.pipeLargeFluid, GTMaterials.Neutronium));

        VanillaRecipeHelper.addShapedRecipe(provider, true, "hermetic_casing_uiv",
                GTLBlocks.HERMETIC_CASING_UIV.asStack(), "PPP", "PFP", "PPP", 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Adamantium), 'F',
                new UnificationEntry(TagPrefix.pipeLargeFluid, GTMaterials.Neutronium));

        VanillaRecipeHelper.addShapedRecipe(provider, true, "hermetic_casing_uxv",
                GTLBlocks.HERMETIC_CASING_UXV.asStack(), "PPP", "PFP", "PPP", 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Vibranium), 'F',
                new UnificationEntry(TagPrefix.pipeLargeFluid, GTLMaterials.Enderium));

        VanillaRecipeHelper.addShapedRecipe(provider, true, "hermetic_casing_opv",
                GTLBlocks.HERMETIC_CASING_OpV.asStack(), "PPP", "PFP", "PPP", 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Draconium), 'F',
                new UnificationEntry(TagPrefix.pipeLargeFluid,
                        GTLMaterials.HeavyQuarkDegenerateMatter));

        VanillaRecipeHelper.addShapedRecipe(provider, true, "quantum_tank_uev",
                GTMachines.QUANTUM_TANK[UEV].asStack(),
                "CGC", "PHP", "CUC", 'C', CustomTags.UEV_CIRCUITS, 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Quantanium), 'U',
                GTItems.ELECTRIC_PUMP_UHV.asStack(),
                'G', GTItems.FIELD_GENERATOR_UV.asStack(), 'H',
                GTLBlocks.HERMETIC_CASING_UEV.asStack());

        VanillaRecipeHelper.addShapedRecipe(provider, true, "quantum_tank_uiv",
                GTMachines.QUANTUM_TANK[UIV].asStack(),
                "CGC", "PHP", "CUC", 'C', CustomTags.UIV_CIRCUITS, 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Adamantium), 'U',
                GTItems.ELECTRIC_PUMP_UEV.asStack(),
                'G', GTItems.FIELD_GENERATOR_UHV.asStack(), 'H',
                GTLBlocks.HERMETIC_CASING_UIV.asStack());

        VanillaRecipeHelper.addShapedRecipe(provider, true, "quantum_tank_uxv",
                GTMachines.QUANTUM_TANK[UXV].asStack(),
                "CGC", "PHP", "CUC", 'C', CustomTags.UXV_CIRCUITS, 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Vibranium), 'U',
                GTItems.ELECTRIC_PUMP_UIV.asStack(),
                'G', GTItems.FIELD_GENERATOR_UEV.asStack(), 'H',
                GTLBlocks.HERMETIC_CASING_UXV.asStack());

        VanillaRecipeHelper.addShapedRecipe(provider, true, "quantum_tank_opv",
                GTMachines.QUANTUM_TANK[OpV].asStack(),
                "CGC", "PHP", "CUC", 'C', CustomTags.OpV_CIRCUITS, 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Draconium), 'U',
                GTItems.ELECTRIC_PUMP_UXV.asStack(),
                'G', GTItems.FIELD_GENERATOR_UIV.asStack(), 'H',
                GTLBlocks.HERMETIC_CASING_OpV.asStack());

        VanillaRecipeHelper.addShapedRecipe(provider, true, "quantum_chest_uev",
                GTMachines.QUANTUM_CHEST[UEV].asStack(), "CPC", "PHP", "CFC", 'C',
                CustomTags.UEV_CIRCUITS, 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Quantanium), 'F',
                GTItems.FIELD_GENERATOR_UV.asStack(), 'H', GTMachines.HULL[10].asStack());

        VanillaRecipeHelper.addShapedRecipe(provider, true, "quantum_chest_uiv",
                GTMachines.QUANTUM_CHEST[UIV].asStack(), "CPC", "PHP", "CFC", 'C',
                CustomTags.UIV_CIRCUITS, 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Adamantium), 'F',
                GTItems.FIELD_GENERATOR_UHV.asStack(), 'H', GTMachines.HULL[11].asStack());

        VanillaRecipeHelper.addShapedRecipe(provider, true, "quantum_chest_uxv",
                GTMachines.QUANTUM_CHEST[UXV].asStack(), "CPC", "PHP", "CFC", 'C',
                CustomTags.UXV_CIRCUITS, 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Vibranium), 'F',
                GTItems.FIELD_GENERATOR_UEV.asStack(), 'H', GTMachines.HULL[12].asStack());

        VanillaRecipeHelper.addShapedRecipe(provider, true, "quantum_chest_opv",
                GTMachines.QUANTUM_CHEST[OpV].asStack(), "CPC", "PHP", "CFC", 'C',
                CustomTags.OpV_CIRCUITS, 'P',
                new UnificationEntry(TagPrefix.plate, GTLMaterials.Draconium), 'F',
                GTItems.FIELD_GENERATOR_UIV.asStack(), 'H', GTMachines.HULL[13].asStack());

        VanillaRecipeHelper.addShapedRecipe(provider, true, "large_block_conversion_room",
                AdvancedMultiBlockMachineA.LARGE_BLOCK_CONVERSION_ROOM.asStack(), "SES", "EHE", "SES",
                'S', GTItems.SENSOR_ZPM.asStack(), 'E', GTItems.EMITTER_ZPM.asStack(), 'H',
                AdvancedMultiBlockMachineA.BLOCK_CONVERSION_ROOM.asStack());

        List<ItemEntry<ComponentItem>> WIRELESS_ENERGY_RECEIVE_COVER = List.of(
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_LV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_MV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_HV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_EV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_IV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_LUV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_ZPM,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UHV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UEV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UIV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UXV,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_OPV,
                GTLItems.WIRELESS_ENERGY_RECEIVE_COVER_MAX);

        List<ItemEntry<ComponentItem>> WIRELESS_ENERGY_RECEIVE_COVER_4A = List.of(
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_LV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_MV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_HV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_EV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_IV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_LUV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_ZPM_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UHV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UEV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UIV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_UXV_4A,
                CustomItems.WIRELESS_ENERGY_RECEIVE_COVER_OPV_4A,
                GTLItems.WIRELESS_ENERGY_RECEIVE_COVER_MAX_4A);

        for (int tier : GTValues.tiersBetween(GTValues.LV, GTValues.HV)) {
            ASSEMBLER_RECIPES
                    .recipeBuilder(GTMThings.id("wireless_energy_input_hatch_" + GTValues.VN[tier].toLowerCase() + "_4a"))
                    .inputItems(GTMachines.ENERGY_INPUT_HATCH_4A[tier].asStack())
                    .inputItems(WIRELESS_ENERGY_RECEIVE_COVER.get(tier - 1).asStack(2))
                    .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                    .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                    .outputItems(WirelessMachines.WIRELESS_ENERGY_INPUT_HATCH_4A[tier].asStack())
                    .duration(200)
                    .EUt(GTValues.VA[tier])
                    .save(provider);

            ASSEMBLER_RECIPES
                    .recipeBuilder(GTMThings.id("wireless_energy_input_hatch_" + GTValues.VN[tier].toLowerCase() + "_16a"))
                    .inputItems(GTMachines.ENERGY_INPUT_HATCH_16A[tier].asStack())
                    .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(tier - 1).asStack(2))
                    .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                    .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                    .outputItems(WirelessMachines.WIRELESS_ENERGY_INPUT_HATCH_16A[tier].asStack())
                    .duration(200)
                    .EUt(GTValues.VA[tier])
                    .save(provider);

            ASSEMBLER_RECIPES
                    .recipeBuilder(GTMThings.id("wireless_energy_output_hatch_" + GTValues.VN[tier].toLowerCase() + "_4a"))
                    .inputItems(GTMachines.ENERGY_OUTPUT_HATCH_4A[tier].asStack())
                    .inputItems(WIRELESS_ENERGY_RECEIVE_COVER.get(tier - 1).asStack(2))
                    .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                    .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                    .outputItems(WirelessMachines.WIRELESS_ENERGY_OUTPUT_HATCH_4A[tier].asStack())
                    .duration(200)
                    .EUt(GTValues.VA[tier])
                    .save(provider);

            ASSEMBLER_RECIPES
                    .recipeBuilder(GTMThings.id("wireless_energy_output_hatch_" + GTValues.VN[tier].toLowerCase() + "_16a"))
                    .inputItems(GTMachines.ENERGY_OUTPUT_HATCH_16A[tier].asStack())
                    .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(tier - 1).asStack(2))
                    .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                    .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                    .outputItems(WirelessMachines.WIRELESS_ENERGY_OUTPUT_HATCH_16A[tier].asStack())
                    .duration(200)
                    .EUt(GTValues.VA[tier])
                    .save(provider);
        }

        for (int tier : GTValues.tiersBetween(GTValues.EV, GTValues.MAX)) {
            ASSEMBLER_RECIPES
                    .recipeBuilder(GTMThings.id("wireless_energy_input_hatch_" + GTValues.VN[tier].toLowerCase() + "_64a"))
                    .inputItems(GTMachines.SUBSTATION_ENERGY_INPUT_HATCH[tier].asStack())
                    .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(tier - 1).asStack(4))
                    .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                    .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                    .outputItems(GTLMachines.WIRELESS_ENERGY_INPUT_HATCH_64A[tier].asStack())
                    .duration(200)
                    .EUt(GTValues.VA[tier])
                    .save(provider);

            ASSEMBLER_RECIPES
                    .recipeBuilder(GTMThings.id("wireless_energy_output_hatch_" + GTValues.VN[tier].toLowerCase() + "_64a"))
                    .inputItems(GTMachines.SUBSTATION_ENERGY_OUTPUT_HATCH[tier].asStack())
                    .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(tier - 1).asStack(4))
                    .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                    .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                    .outputItems(GTLMachines.WIRELESS_ENERGY_OUTPUT_HATCH_64A[tier].asStack())
                    .duration(200)
                    .EUt(GTValues.VA[tier])
                    .save(provider);
        }

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings
                        .id("wireless_energy_input_hatch_" + GTValues.VN[MAX].toLowerCase()))
                .inputItems(GTMachines.ENERGY_INPUT_HATCH[MAX].asStack())
                .inputItems(GTLItems.WIRELESS_ENERGY_RECEIVE_COVER_MAX.asStack())
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack())
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_INPUT_HATCH[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings
                        .id("wireless_energy_output_hatch_" + GTValues.VN[MAX].toLowerCase()))
                .inputItems(GTMachines.ENERGY_OUTPUT_HATCH[MAX].asStack())
                .inputItems(GTLItems.WIRELESS_ENERGY_RECEIVE_COVER_MAX.asStack())
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack())
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_OUTPUT_HATCH[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_input_hatch_" + GTValues.VN[MAX].toLowerCase() + "_4a"))
                .inputItems(GTMachines.ENERGY_INPUT_HATCH_4A[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER.get(MAX - 1).asStack(2))
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_INPUT_HATCH_4A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_input_hatch_" + GTValues.VN[MAX].toLowerCase() + "_16a"))
                .inputItems(GTMachines.ENERGY_INPUT_HATCH_16A[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(MAX - 1).asStack(2))
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_INPUT_HATCH_16A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_output_hatch_" + GTValues.VN[MAX].toLowerCase() + "_4a"))
                .inputItems(GTMachines.ENERGY_OUTPUT_HATCH_4A[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER.get(MAX - 1).asStack(2))
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_OUTPUT_HATCH_4A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_output_hatch_" + GTValues.VN[MAX].toLowerCase() + "_16a"))
                .inputItems(GTMachines.ENERGY_OUTPUT_HATCH_16A[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(MAX - 1).asStack(2))
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_OUTPUT_HATCH_16A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_input_hatch_" + GTValues.VN[MAX].toLowerCase() + "_256a"))
                .inputItems(GTMachines.LASER_INPUT_HATCH_256[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(MAX - 1).asStack(4))
                .inputItems(GTMachines.ACTIVE_TRANSFORMER.asStack())
                .inputItems(GTBlocks.SUPERCONDUCTING_COIL.asStack())
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_INPUT_HATCH_256A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_input_hatch_" + GTValues.VN[MAX].toLowerCase() + "_1024a"))
                .inputItems(GTMachines.LASER_INPUT_HATCH_1024[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(MAX - 1).asStack(8))
                .inputItems(GTMachines.ACTIVE_TRANSFORMER.asStack())
                .inputItems(GTBlocks.SUPERCONDUCTING_COIL.asStack())
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_INPUT_HATCH_1024A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_input_hatch_" + GTValues.VN[MAX].toLowerCase() + "_4096a"))
                .inputItems(GTMachines.LASER_INPUT_HATCH_4096[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(MAX - 1).asStack(16))
                .inputItems(GTMachines.ACTIVE_TRANSFORMER.asStack())
                .inputItems(GTBlocks.SUPERCONDUCTING_COIL.asStack())
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_INPUT_HATCH_4096A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_output_hatch_" + GTValues.VN[MAX].toLowerCase() + "_256a"))
                .inputItems(GTMachines.LASER_OUTPUT_HATCH_256[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(MAX - 1).asStack(4))
                .inputItems(GTMachines.ACTIVE_TRANSFORMER.asStack())
                .inputItems(GTBlocks.SUPERCONDUCTING_COIL.asStack())
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_OUTPUT_HATCH_256A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_output_hatch_" + GTValues.VN[MAX].toLowerCase() + "_1024a"))
                .inputItems(GTMachines.LASER_OUTPUT_HATCH_1024[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(MAX - 1).asStack(8))
                .inputItems(GTMachines.ACTIVE_TRANSFORMER.asStack())
                .inputItems(GTBlocks.SUPERCONDUCTING_COIL.asStack())
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_OUTPUT_HATCH_1024A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTMThings.id("wireless_energy_output_hatch_" + GTValues.VN[MAX].toLowerCase() + "_4096a"))
                .inputItems(GTMachines.LASER_OUTPUT_HATCH_4096[MAX].asStack())
                .inputItems(WIRELESS_ENERGY_RECEIVE_COVER_4A.get(MAX - 1).asStack(16))
                .inputItems(GTMachines.ACTIVE_TRANSFORMER.asStack())
                .inputItems(GTBlocks.SUPERCONDUCTING_COIL.asStack())
                .inputItems(GTItems.COVER_ENERGY_DETECTOR_ADVANCED.asStack(1))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(144))
                .outputItems(WirelessMachines.WIRELESS_ENERGY_OUTPUT_HATCH_4096A[MAX].asStack())
                .duration(200)
                .EUt(GTValues.VA[MAX])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTLCore.id("heat_sensor"))
                .inputItems(SENSOR_UXV, 4)
                .inputItems(EMITTER_UXV, 4)
                .inputItems(CIRCUIT.getIngredient(UXV), 16)
                .inputItems(HULL.getIngredient(UXV), 1)
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(1440))
                .outputItems(GTLMachines.HEAT_SENSOR)
                .duration(200).EUt(VA[UXV])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTLCore.id("me_input_assembly"))
                .inputItems(GTLMachines.GTAEMachines.ITEM_IMPORT_BUS_ME, 2)
                .inputItems(GTLMachines.GTAEMachines.FLUID_IMPORT_HATCH_ME, 2)
                .inputItems(CustomTags.EV_CIRCUITS)
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(1440))
                .outputItems(GTLMachines.ME_INPUT_ASSEMBLY)
                .duration(100).EUt(VA[HV])
                .save(provider);

        ASSEMBLER_RECIPES
                .recipeBuilder(GTLCore.id("heat_sensor"))
                .inputItems(GTLMachines.GTAEMachines.STOCKING_IMPORT_BUS_ME, 2)
                .inputItems(GTLMachines.GTAEMachines.STOCKING_IMPORT_HATCH_ME, 2)
                .inputItems(CIRCUIT.getIngredient(IV))
                .inputFluids(GTMaterials.SolderingAlloy.getFluid(1440))
                .outputItems(GTLMachines.ME_DUAL_HATCH_STOCK_PART_MACHINE)
                .duration(200).EUt(VA[EV])
                .save(provider);

        ASSEMBLY_LINE_RECIPES
                .recipeBuilder(GTLCore.id("space_cosmic_probe_receivers"))
                .inputItems(EMITTER_OpV, 16)
                .inputItems(SENSOR_OpV, 16)
                .inputItems(CIRCUIT.getIngredient(MAX), 16)
                .inputItems(ChemicalHelper.get(rod, GTLMaterials.Infinity), 32)
                .inputItems(ChemicalHelper.get(plateDouble, GTLMaterials.Starmetal), 32)
                .inputItems(AdvancedMultiBlockMachineA.SPACE_PROBE_SURFACE_RECEPTION, 8)
                .inputItems(GTLBlocks.ADVANCED_FUSION_COIL, 16)
                .inputItems(getItem("kubejs:awakened_core"), 16)
                .inputItems(ChemicalHelper.get(GTLTagPrefix.nanoswarm, GTLMaterials.BlackDwarfMatter), 64)
                .inputItems(ChemicalHelper.get(GTLTagPrefix.nanoswarm, GTLMaterials.WhiteDwarfMatter), 64)
                .inputItems(GTLItems.INSANELY_ULTIMATE_BATTERY)
                .inputFluids(GTLMaterials.SuperMutatedLivingSolder.getFluid(32000))
                .inputFluids(GTLMaterials.Periodicium.getFluid(16000))
                .stationResearch(b -> b.researchStack(AdvancedMultiBlockMachineA.SPACE_PROBE_SURFACE_RECEPTION.asStack())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack())
                        .EUt(VA[UXV])
                        .CWUt(2048))
                .outputItems(AdvancedMultiBlockMachineA.SPACE_COSMIC_PROBE_RECEIVERS)
                .duration(2000)
                .EUt(GTValues.VA[OpV])
                .save(provider);

        GTLRecipeTypes.ASSEMBLER_MODULE_RECIPES.recipeBuilder("fast_conversion_simulate_card")
                .inputItems(FIELD_GENERATOR_UIV, 4)
                .inputItems(EMITTER_UIV, 4)
                .inputItems(SENSOR_UIV, 4)
                .inputItems(CIRCUIT.getIngredient(UIV), 8)
                .inputItems(GTLItems.CONVERSION_SIMULATE_CARD)
                .inputFluids(GTLMaterials.CosmicSuperconductor.getFluid(16000))
                .inputFluids(GTLMaterials.Periodicium.getFluid(16000))
                .inputFluids(GTLMaterials.HeavyLeptonMixture.getFluid(FluidStorageKeys.GAS, 8000))
                .inputFluids(GTLMaterials.Legendarium.getFluid(8000))
                .outputItems(GTLItems.FAST_CONVERSION_SIMULATE_CARD)
                .addData("SEPMTier", 5)
                .duration(200).EUt(V[UXV]).save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("advanced_rare_earth_centrifugal")
                .inputItems(ChemicalHelper.get(GTLTagPrefix.nanoswarm, Neutronium), 32)
                .inputItems(ChemicalHelper.get(plateDense, TungstenSteel), 9)
                .inputItems(ChemicalHelper.get(plateDouble, GTLMaterials.Enderium), 9)
                .inputItems(ChemicalHelper.get(plateDouble, GTLMaterials.Echoite), 16)
                .inputItems(CIRCUIT.getIngredient(UXV), 16)
                .inputItems(ChemicalHelper.get(plateDense, NaquadahAlloy), 9)
                .inputItems(ChemicalHelper.get(pipeLargeItem, Osmiridium), 8)
                .inputItems(ChemicalHelper.get(rod, GTLMaterials.AttunedTengam), 16)
                .inputItems(ChemicalHelper.get(cableGtHex, GTLMaterials.Mithril), 16)
                .inputFluids(GTLMaterials.MutatedLivingSolder.getFluid(32000))
                .inputFluids(GTLMaterials.FallKing.getFluid(24000))
                .inputFluids(GTLMaterials.Quantum.getFluid(32000))
                .outputItems(AdditionalMultiBlockMachine.ADVANCED_RARE_EARTH_CENTRIFUGAL)
                .stationResearch(b -> b.researchStack(MultiBlockMachineA.RARE_EARTH_CENTRIFUGAL.asStack())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack())
                        .EUt(VA[UXV])
                        .CWUt(2048))
                .addData("SEPMTier", 5)
                .duration(200).EUt(V[UXV]).save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("transfinite_computation_array")
                .inputItems(frameGt, Adamantium, 16)
                .inputItems(FIELD_GENERATOR_UHV, 8)
                .inputItems(GTLItems.REALLY_ULTIMATE_BATTERY)
                .inputItems(CustomTags.UEV_CIRCUITS, 16)
                .inputItems(CRAFTING_STORAGE_MAX.asStack(), 16)
                .inputItems(Registries.getItemStack("mae2:256x_crafting_accelerator", 64))
                .inputItems(Registries.getItemStack("mae2:256x_crafting_accelerator", 64))
                .inputItems(wireFine, Enderite, 32)
                .inputItems(wireFine, Enderite, 32)
                .inputItems(plate, Highurabilityompoundteel, 16)
                .inputFluids(EuvPhotoresist.getFluid(3000))
                .inputFluids(Seaborgium.getFluid(2304))
                .inputFluids(Dubnium.getFluid(2304))
                .inputFluids(Kevlar.getFluid(14400))
                .outputItems(TRANSFINITE_COMPUTATION_ARRAY)
                .EUt(VA[UEV]).duration(1200)
                .stationResearch(b -> b
                        .researchStack(CRAFTING_STORAGE_MAX.asStack())
                        .dataStack(TOOL_DATA_MODULE.asStack()).EUt(VA[UHV]).CWUt(256))
                .save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("advanced_vacuum_drying_furnace")
                .inputItems(CIRCUIT.getIngredient(UIV), 32)
                .inputItems(GTBlocks.HIGH_POWER_CASING, 16)
                .inputItems(ModItems.CALORITE_PLATE, 16)
                .inputItems(MultiBlockMachineA.VACUUM_DRYING_FURNACE, 64)
                .inputItems(ChemicalHelper.get(pipeLargeFluid, Neutronium), 4)
                .inputItems(ELECTRIC_PUMP_UEV, 64)
                .inputItems(FIELD_GENERATOR_UEV, 64)
                .inputItems(ChemicalHelper.get(GTLTagPrefix.nanoswarm, Iron), 32)
                .inputItems(ChemicalHelper.get(plateDouble, RedSteel), 16)
                .inputItems(ChemicalHelper.get(plateDense, Steel), 16)
                .inputItems(ChemicalHelper.get(plateDense, RhodiumPlatedPalladium), 16)
                .inputFluids(SolderingAlloy.getFluid(16000))
                .inputFluids(GTLMaterials.HastelloyX78.getFluid(8000))
                .inputFluids(GTLMaterials.Quantum.getFluid(16000))
                .outputItems(AdditionalMultiBlockMachine.ADVANCED_VACUUM_DRYING_FURNACE)
                .stationResearch(b -> b.researchStack(MultiBlockMachineA.VACUUM_DRYING_FURNACE.asStack())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack())
                        .EUt(VA[UIV])
                        .CWUt(256))
                .addData("SEPMTier", 2)
                .duration(200).EUt(V[UIV]).save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("huge_incubator")
                .inputItems(CIRCUIT.getIngredient(UIV), 32)
                .inputItems(MultiBlockMachineA.LARGE_INCUBATOR, 4)
                .inputItems(GTMachines.BREWERY[UIV], 4)
                .inputItems(GTMachines.FERMENTER[UIV], 4)
                .inputItems(GTLItems.STERILIZED_PETRI_DISH, 16)
                .inputItems(ChemicalHelper.get(gear, Neutronium), 64)
                .inputItems(ROBOT_ARM_UIV, 16)
                .inputItems(FLUID_REGULATOR_UIV, 16)
                .inputItems(FIELD_GENERATOR_UIV, 16)
                .inputItems(EMITTER_UIV, 16)
                .inputItems(SENSOR_UIV, 16)
                .inputItems(ChemicalHelper.get(plate, GTLMaterials.DegenerateRhenium), 32)
                .inputItems(ChemicalHelper.get(gearSmall, GTLMaterials.Vibramantium), 32)
                .inputItems(ChemicalHelper.get(rod, GTLMaterials.Vibramantium), 16)
                .inputItems(ChemicalHelper.get(screw, GTLMaterials.Vibramantium), 16)
                .inputItems(getItem("kubejs:x_ray_laser"), 1)
                .inputFluids(SolderingAlloy.getFluid(16000))
                .inputFluids(GTLMaterials.MutatedLivingSolder.getFluid(16000))
                .inputFluids(GTLMaterials.Photopolymer.getFluid(8000))
                .inputFluids(GTMaterials.SterileGrowthMedium.getFluid(8000))
                .outputItems(AdditionalMultiBlockMachine.HUGE_INCUBATOR)
                .stationResearch(b -> b.researchStack(MultiBlockMachineA.LARGE_INCUBATOR.asStack())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack())
                        .EUt(VA[UIV])
                        .CWUt(256))
                .duration(200).EUt(V[UIV]).save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("advanced_neutron_activator")
                .inputItems(CIRCUIT.getIngredient(UXV), 32)
                .inputItems(ChemicalHelper.get(frameGt, GTLMaterials.BlackTitanium), 32)
                .inputItems(AdvancedMultiBlockMachineB.NEUTRON_ACTIVATOR, 16)
                .inputItems(GTLMachines.NEUTRON_COMPRESSOR, 16)
                .inputItems(GTLMachines.NEUTRON_ACCELERATOR[UHV], 8)
                .inputItems(GTLMachines.NEUTRON_ACCELERATOR[UEV], 8)
                .inputItems(GTLMachines.NEUTRON_ACCELERATOR[UIV], 8)
                .inputItems(GTLItems.EXTREMELY_ULTIMATE_BATTERY)
                .inputItems(getItem("kubejs:wyvern_core"), 4)
                .inputFluids(GTLMaterials.SuperMutatedLivingSolder.getFluid(16000))
                .inputFluids(GTLMaterials.MutatedLivingSolder.getFluid(16000))
                .inputFluids(GTMaterials.Oganesson.getFluid(1296))
                .inputFluids(GTLMaterials.Vibranium.getFluid(1296))
                .outputItems(AdditionalMultiBlockMachine.ADVANCED_NEUTRON_ACTIVATOR)
                .stationResearch(b -> b.researchStack(AdvancedMultiBlockMachineB.NEUTRON_ACTIVATOR.asStack())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack())
                        .EUt(VA[UXV])
                        .CWUt(512))
                .duration(400).EUt(V[UXV]).save(provider);

        ASSEMBLY_LINE_RECIPES.recipeBuilder("me_recipe_pattern_buffer")
                .inputItems(ME_FINAL_PATTERN_BUFFER)
                .inputItems(HULL.getIngredient(UXV))
                .inputItems(getItem("kubejs:fm_chip"), 32)
                .inputItems(EMITTER_UXV, 8)
                .inputItems(SENSOR_UXV, 8)
                .inputItems(CIRCUIT.getIngredient(UXV), 4)
                .inputItems(getItem("kubejs:ultrashort_pulse_laser"), 4)
                .inputItems(getItem("kubejs:wyvern_core"), 8)
                .inputItems(plateDouble, Legendarium, 4)
                .inputItems(plateDouble, CelestialTungsten, 4)
                .inputFluids(SuperMutatedLivingSolder.getFluid(1152))
                .inputFluids(Sunnarium.getFluid(2000))
                .inputFluids(CosmicSuperconductor.getFluid(1000))
                .inputFluids(GammaRaysPhotoresist.getFluid(10000))
                .outputItems(ME_RECIPE_PATTERN_BUFFER)
                .stationResearch(b -> b.researchStack(ME_FINAL_PATTERN_BUFFER.asStack())
                        .dataStack(GTItems.TOOL_DATA_MODULE.asStack())
                        .EUt(GTValues.VA[GTValues.UEV])
                        .CWUt(576))
                .EUt(VA[UIV]).duration(600).save(provider);

        if (ModList.get().isLoaded("wildcard_pattern")) {
            ASSEMBLY_LINE_RECIPES.recipeBuilder("me_wildcard_pattern_buffer")
                    .inputItems(ME_EXTEND_PATTERN_BUFFER)
                    .inputItems(EMITTER_UHV, 4)
                    .inputItems(CIRCUIT.getIngredient(UEV), 16)
                    .inputItems(TAG_FILTER_ME_STOCK_BUS_PART_MACHINE, 4)
                    .inputItems(getItem("kubejs:low_frequency_laser"), 8)
                    .inputItems(getItem("kubejs:medium_frequency_laser"), 8)
                    .inputItems(getItem("kubejs:high_frequency_laser"), 8)
                    .inputItems(ORE_DICTIONARY_FILTER, 32)
                    .inputItems(TAG_FLUID_FILTER, 32)
                    .inputItems(plate, Kevlar, 8)
                    .inputItems(plate, CarbonNanotubes, 8)
                    .inputItems(wireFine, Enderite, 36)
                    .inputFluids(GTLMaterials.MutatedLivingSolder.getFluid(576))
                    .inputFluids(Lubricant.getFluid(500))
                    .inputFluids(GTLMaterials.UuAmplifier.getFluid(576))
                    .outputItems(ME_WILDCARD_PATTERN_BUFFER)
                    .EUt(GTValues.VA[GTValues.UHV])
                    .duration(600)
                    .stationResearch(b -> b.researchStack(ME_EXTEND_PATTERN_BUFFER.asStack())
                            .dataStack(GTItems.TOOL_DATA_MODULE.asStack())
                            .EUt(GTValues.VA[GTValues.UEV])
                            .CWUt(256))
                    .save(provider);

            ASSEMBLY_LINE_RECIPES.recipeBuilder("me_stocking_wildcard_pattern_buffer")
                    .inputItems(HULL.getIngredient(UIV))
                    .inputItems(ME_WILDCARD_PATTERN_BUFFER, 2)
                    .inputItems(ME_STOCKING_PATTERN_BUFFER, 2)
                    .inputItems(EMITTER_UEV, 16)
                    .inputItems(CIRCUIT.getIngredient(UIV), 16)
                    .inputItems(FullerenePolymerMatrixPulp, 8)
                    .inputItems(getItem("kubejs:rydberg_spinorial_assembly"), 8)
                    .inputItems(plate, EchoShard, 16)
                    .inputFluids(SuperMutatedLivingSolder.getFluid(1440))
                    .inputFluids(CaliforniumCyclopentadienide.getFluid(1500))
                    .inputFluids(UuAmplifier.getFluid(576))
                    .outputItems(ME_STOCKING_WILDCARD_PATTERN_BUFFER)
                    .EUt(GTValues.VA[GTValues.UEV])
                    .duration(600)
                    .stationResearch(b -> b.researchStack(ME_STOCKING_PATTERN_BUFFER.asStack())
                            .dataStack(GTItems.TOOL_DATA_MODULE.asStack())
                            .EUt(GTValues.VA[GTValues.UEV])
                            .CWUt(432))
                    .save(provider);
        }

        VanillaRecipeHelper.addShapedRecipe(provider, true, GTLCore.id("performance_monitor"), GTLMachines.PERFORMANCE_MONITOR.asStack(),
                "AAA",
                "ABA",
                "AAA",
                'B', GTItems.PORTABLE_SCANNER.asStack(), 'A', TagPrefix.plate, GTMaterials.Steel);

        // adv
        space_probe(GTLMaterials.Starlight, 2, 1000, 2, provider);
        space_probe(GTLMaterials.Starlight, 3, 10000, 2, provider);
        space_probe(GTLMaterials.HeavyLeptonMixture, 1, 100, 1, provider);
        space_probe(GTLMaterials.HeavyLeptonMixture, 2, 1000, 1, provider);
        space_probe(GTLMaterials.HeavyLeptonMixture, 3, 10000, 1, provider);
        space_probe(GTLMaterials.CosmicElement, 3, 10000, 3, provider);

        ASSEMBLER_RECIPES.recipeBuilder("wireless_network_core")
                .inputItems(GTMachines.HULL[4])
                .inputItems(WIRELESS_HUB.asItem(), 4)
                .inputItems(QUANTUM_LINK.asItem(), 4)
                .inputItems(QUANTUM_RING.asItem(), 36)
                .inputItems(wireGtHex, UraniumTriplatinum, 2)
                .inputItems(CIRCUIT.getIngredient(LuV), 8)
                .inputItems(FIELD_GENERATOR_EV)
                .inputFluids(Radon.getFluid(1000))
                .outputItems(WIRELESS_NETWORK_CORE)
                .duration(400).EUt(VA[EV]).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("wireless_network_bookmark")
                .inputItems(GTMachines.HULL[4])
                .inputItems(WIRELESS_TOOL.asItem())
                .inputItems(CIRCUIT.getIngredient(EV), 2)
                .inputItems(wireFine, TungstenSteel, 16)
                .inputItems(EMITTER_EV, 4)
                .outputItems(WIRELESS_NETWORK_BOOKMARK)
                .duration(400).EUt(VA[EV]).save(provider);

        ASSEMBLER_RECIPES.recipeBuilder("wireless_network_binding_tool")
                .inputItems(WIRELESS_TOOL.asItem())
                .inputItems(CIRCUIT.getIngredient(EV))
                .inputItems(EMITTER_EV)
                .outputItems(org.gtlcore.gtlcore.integration.ae2.wireless.GTLWirelessAeContent.WIRELESS_NETWORK_BINDING_TOOL.get())
                .duration(200).EUt(VA[EV]).save(provider);
    }

    private static void space_probe(Material material, int grade, int amount, int circuit, Consumer<FinishedRecipe> provider) {
        SPACE_COSMIC_PROBE_RECEIVERS_RECIPES
                .recipeBuilder(GTLCore.id("space_cosmic_probe_receivers_" + material.getName()))
                .notConsumable(getItem("kubejs:space_probe_mk" + grade))
                .outputFluids(material.getFluid(amount))
                .EUt((long) (VA[UEV] * Math.pow(4, grade)))
                .duration(200)
                .circuitMeta(circuit)
                .CWUt(grade)
                .save(provider);
    }
}
