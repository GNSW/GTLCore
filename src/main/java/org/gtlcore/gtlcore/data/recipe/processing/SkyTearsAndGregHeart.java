package org.gtlcore.gtlcore.data.recipe.processing;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLMachines;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.utils.Registries;

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.UnificationEntry;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gregtechceu.gtceu.data.recipe.CustomTags;
import com.gregtechceu.gtceu.data.recipe.VanillaRecipeHelper;

import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import earth.terrarium.adastra.common.registry.ModItems;

import java.util.function.Consumer;

import static com.gregtechceu.gtceu.api.GTValues.ULV;
import static com.gregtechceu.gtceu.api.GTValues.VEX;
import static com.gregtechceu.gtceu.api.data.tag.TagPrefix.*;
import static com.gregtechceu.gtceu.common.data.GTMaterials.*;
import static com.gregtechceu.gtceu.common.data.GTRecipeTypes.DISTILLATION_RECIPES;
import static org.gtlcore.gtlcore.api.data.tag.GTLTagPrefix.nanoswarm;
import static org.gtlcore.gtlcore.common.data.GTLItems.*;
import static org.gtlcore.gtlcore.common.data.GTLMaterials.*;
import static org.gtlcore.gtlcore.common.data.GTLRecipeTypes.*;
import static org.gtlcore.gtlcore.common.data.machines.AdvancedMultiBlockMachineB.SPACE_ELEVATOR;
import static org.gtlcore.gtlcore.common.data.machines.MultiBlockMachineB.LARGE_FRAGMENT_WORLD_COLLECTION_MACHINE;

public class SkyTearsAndGregHeart {

    static final ItemStack[] skyFragments = {
            WORLD_FRAGMENTS_OVERWORLD.asStack(1),
            WORLD_FRAGMENTS_NETHER.asStack(1),
            WORLD_FRAGMENTS_END.asStack(1),
            WORLD_FRAGMENTS_REACTOR.asStack(1),

            WORLD_FRAGMENTS_MOON.asStack(1),
            WORLD_FRAGMENTS_MARS.asStack(1),
            WORLD_FRAGMENTS_VENUS.asStack(1),
            WORLD_FRAGMENTS_MERCURY.asStack(1),

            WORLD_FRAGMENTS_CERES.asStack(1),
            WORLD_FRAGMENTS_IO.asStack(1),
            WORLD_FRAGMENTS_GANYMEDE.asStack(1),
            WORLD_FRAGMENTS_PLUTO.asStack(1),

            WORLD_FRAGMENTS_ENCELADUS.asStack(1),
            WORLD_FRAGMENTS_TITAN.asStack(1),
            WORLD_FRAGMENTS_GLACIO.asStack(1),
            WORLD_FRAGMENTS_BARNARDA.asStack(1)
    };

    static final Material[][] skyOres = {
            { Goethite, YellowLimonite, Hematite, Malachite },// 铁矿脉 0
            { Soapstone, Talc, GlauconiteSand, Pentlandite },// 皂滑矿脉
            { Grossular, Spessartine, Pyrolusite, Tantalite },// 主世界锰矿脉
            { Chalcopyrite, Zeolite, Cassiterite, Realgar },// 铜锡矿脉
            { Chalcopyrite, Iron, Pyrite, Copper },// 铜矿脉
            { Galena, Silver, Lead, Lead },// 方铅矿脉
            { Tin, Tin, Cassiterite, Cassiterite },// 锡石矿脉
            { Redstone, Ruby, Cinnabar, Cinnabar },// 红石矿脉
            { Apatite, Apatite, TricalciumPhosphate, TricalciumPhosphate },// 磷灰石矿脉
            { Graphite, Diamond, Coal, Coal },// 钻石矿脉

            { Garnierite, Nickel, Cobaltite, Pentlandite },// 镍矿脉 10
            { Bentonite, Magnetite, Olivine, GlauconiteSand },// 橄榄石矿脉
            { Almandine, Pyrope, Sapphire, GreenSapphire },// 蓝宝石矿脉
            { Coal, Coal, Coal, Coal },// 煤炭矿脉
            { Magnetite, VanadiumMagnetite, Gold, Gold },// 磁铁矿脉
            { Lazurite, Sodalite, Lapis, Calcite },// 青金石矿脉
            { Kyanite, Mica, Pollucite, Pollucite },// 云母矿脉
            { GarnetRed, GarnetYellow, Amethyst, Opal },// 石榴石矿脉
            { BasalticMineralSand, GraniticMineralSand, FullersEarth, Gypsum },// 矿砂矿脉
            { RockSalt, Salt, Lepidolite, Spodumene },// 盐矿脉

            { CassiteriteSand, GarnetSand, Asbestos, Diatomite },// 锡石榴石矿脉 20
            { Oilsands, Oilsands, Oilsands, Oilsands },// 油砂矿脉
            { Bastnasite, Bastnasite, Monazite, Neodymium },// 独居石矿脉
            { Saltpeter, Diatomite, Electrotine, Alunite },// 硝石矿脉
            { Beryllium, Beryllium, Emerald, Emerald },// 铍矿脉
            { Grossular, Pyrolusite, Tantalite, Tantalite },// 锰矿脉
            { Wulfenite, Molybdenite, Molybdenum, Powellite },// 钼矿脉
            { Quartzite, CertusQuartz, Barite, Barite },// 赛特斯石英
            { Tetrahedrite, Tetrahedrite, Copper, Stibnite },// 黝铜矿脉
            { Goethite, YellowLimonite, Hematite, Gold },// 带状铁矿脉

            { BlueTopaz, Topaz, Chalcocite, Bornite },// 下界黄玉矿脉 30
            { NetherQuartz, NetherQuartz, Quartzite, Quartzite },// 下界石英矿脉
            { Sulfur, Pyrite, Sphalerite, Sphalerite },// 硫矿脉
            { Naquadah, Naquadah, Plutonium239, Plutonium239 },// 硅岩矿脉
            { Scheelite, Tungstate, Lithium, Lithium },// 白钨矿脉
            { Bauxite, Ilmenite, Aluminium, Aluminium },// 铝土矿脉
            { Bornite, Cooperite, Platinum, Palladium },// 谢尔顿矿脉
            { Pitchblende, Pitchblende, Uraninite, Uraninite },// 沥青铀矿脉
            { NetherQuartz, Barite, Quartzite, Quartzite },// 石英岩矿脉
            { BlueTopaz, BlueTopaz, Topaz, Topaz },// 黄玉矿脉

            { Copper, Copper, Stibnite, Stibnite },// 辉锑矿脉 40
            { Uraninite, Thorium, Plutonium239, Plutonium239 },// 钚矿脉
            { Uraninite, Pitchblende, Thorium, Thorium },// 铀矿脉
            { Apatite, TricalciumPhosphate, Pyrochlore, Pyrochlore },// 磷矿脉
            { Bentonite, Magnetite, Olivine, GlauconiteSand },
            { Magnesite, Magnesite, Desh, Desh },// 戴斯矿脉
            { Cobalt, Cobalt, Calorite, Magnesite },// 耐热矿脉
            { Gold, Gold, Ostrum, Ostrum },// 紫金矿脉
            { Trona, Trona, Cooperite, Celestine },// 天青石矿脉
            { Zircon, Grossular, Pyrolusite, Tantalite }// 锆石矿脉

    };

    // sky_ores_number(n)
    static final int[][] skyOres_n = {
            { 64, 24, 24, 16, 800 },// 铁矿脉 0
            { 48, 32, 32, 16, 100 },// 皂滑矿脉
            { 48, 32, 32, 16, 150 },// 主世界锰矿脉
            { 64, 24, 24, 16, 500 },// 铜锡矿脉
            { 64, 24, 24, 16, 800 },// 铜矿脉
            { 64, 48, 8, 8, 100 },// 方铅矿脉
            { 64, 16, 32, 16, 800 },// 锡石矿脉
            { 64, 48, 8, 8, 120 },// 红石矿脉
            { 64, 16, 32, 16, 100 },// 磷灰石矿脉
            { 64, 48, 8, 8, 100 },// 钻石矿脉

            { 48, 32, 32, 16, 100 },// 镍矿脉 10
            { 48, 32, 32, 16, 50 },// 橄榄石矿脉
            { 48, 32, 32, 16, 150 },// 蓝宝石矿脉
            { 32, 32, 32, 32, 200 },// 煤炭矿脉
            { 64, 48, 8, 8, 120 },// 磁铁矿脉
            { 48, 32, 32, 16, 300 },// 青金石矿脉
            { 64, 48, 8, 8, 50 },// 云母矿脉
            { 48, 32, 32, 16, 300 },// 石榴石矿脉
            { 48, 32, 32, 16, 160 },// 矿砂矿脉
            { 48, 32, 32, 16, 100 },// 盐矿脉

            { 48, 32, 32, 16, 320 },// 锡石榴石矿脉 20
            { 64, 48, 8, 8, 120 },// 油砂矿脉
            { 36, 36, 28, 28, 100 },// 独居石矿脉
            { 36, 36, 36, 20, 300 },// 硝石矿脉
            { 38, 38, 26, 26, 250 },// 铍矿脉
            { 64, 48, 8, 8, 150 },// 锰矿脉
            { 64, 32, 16, 16, 50 },// 钼矿脉
            { 64, 48, 8, 8, 100 },// 赛特斯石英
            { 36, 36, 36, 20, 800 },// 黝铜矿脉
            { 48, 32, 32, 16, 300 },// 带状铁矿脉

            { 48, 32, 32, 16, 175 },// 下界黄玉矿脉 30
            { 48, 48, 16, 16, 160 },// 下界石英矿脉
            { 64, 48, 8, 8, 500 },// 硫矿脉
            { 48, 48, 16, 16, 100 },// 硅岩矿脉
            { 64, 48, 8, 8, 140 },// 白钨矿脉
            { 48, 48, 16, 16, 120 },// 铝土矿脉
            { 48, 32, 32, 16, 60 },// 谢尔顿矿脉
            { 64, 16, 32, 16, 75 },// 沥青铀矿脉
            { 52, 38, 22, 16, 120 },// 石英岩矿脉
            { 32, 32, 32, 32, 100 },// 黄玉矿脉

            { 32, 32, 32, 32, 100 },// 辉锑矿脉 40
            { 64, 48, 8, 8, 400 },// 钚矿脉
            { 64, 48, 8, 8, 400 },// 铀矿脉
            { 54, 54, 8, 8, 100 },// 磷矿脉
            { 26, 26, 50, 26, 75 },// 橄榄石矿脉
            { 42, 22, 42, 22, 60 },// 戴斯矿脉
            { 32, 32, 32, 32, 120 },// 耐热矿脉
            { 42, 22, 42, 22, 100 },// 紫金矿脉
            { 32, 32, 32, 32, 200 },// 天青石矿脉
            { 48, 32, 24, 24, 100 }// 锆石矿脉

    };

    // sky_fluid(m)
    static final Material[] skyFluid = {
            SaltWater,// 盐水矿藏 0
            OilHeavy,// 重油矿藏
            RawOil,// 原油矿藏
            Oil,// 石油矿藏
            OilLight,// 轻油矿藏
            NaturalGas,// 天然气矿藏
            Lava,// 熔岩矿藏
            NaturalGas,// 下界天然气矿藏
            Helium3,// 氦-3 矿藏
            Helium,// 氦矿藏

            Radon,// 氡矿藏 10
            SulfuricAcid,// 硫酸矿藏
            Deuterium,// 氘矿藏
            Neon,// 氖矿藏
            Krypton,// 氪矿藏
            Radon,// 氡矿藏
            Xenon,// 氙矿藏
            CoalGas,// 煤气矿藏
            HydrochloricAcid,// 盐酸矿藏
            NitricAcid,// 硝酸矿藏

            Chlorine,// 氯矿藏 20
            Fluorine,// 氟矿藏
            Benzene,// 苯矿藏
            Methane,// 甲烷矿藏
            CharcoalByproducts,// 木炭副产矿藏
            UnknowWater// 不明液体矿藏

    };

    // sky_fluid
    static final int[] skyFluid_n = {
            1000,// 盐水矿藏 0
            2000,// 重油矿藏
            3000,// 原油矿藏
            3000,// 石油矿藏
            3000,// 轻油矿藏
            1750,// 天然气矿藏
            2500,// 熔岩矿藏
            3000,// 下界天然气矿藏
            1800,// 氦-3 矿藏
            3000,// 氦矿藏

            800,// 氡矿藏 10
            2500,// 硫酸矿藏
            3000,// 氘矿藏
            2500,// 氖矿藏
            2500,// 氪矿藏
            2500,// 氡矿藏
            2500,// 氙矿藏
            3000,// 煤气矿藏
            3500,// 盐酸矿藏
            3000,// 硝酸矿藏

            4200,// 氯矿藏 20
            3200,// 氟矿藏
            1600,// 苯矿藏
            2500,// 甲烷矿藏
            2600,// 木炭副产矿藏
            600 // 不明液体矿藏

    };

    // sky_digging_number()
    static final int[][] skyDNumber = {
            { 22, 6 },
            { 12, 2 },
            { 6, 0 },
            { 8, 0 },

            { 4, 2 },
            { 3, 1 },
            { 3, 1 },
            { 2, 1 },

            { 4, 4 },
            { 4, 1 },
            { 5, 1 },
            { 5, 1 },

            { 3, 2 },
            { 4, 3 },
            { 9, 0 },
            { 7, 1 }
    };

    // sky_digging_mapping
    static final int[][] skyDMap = {
            { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 0, 1, 2, 3, 4, 5 },
            { 22, 7, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 6, 7 },
            { 33, 14, 34, 35, 36, 37 },
            { 39, 38, 24, 23, 27, 32, 40, 26 },

            { 41, 35, 22, 42, 8, 9 },
            { 36, 34, 43, 10 },
            { 44, 32, 45, 11 },
            { 46, 10, 12 },

            { 27, 26, 22, 47, 13, 14, 15, 16 },
            { 48, 33, 44, 32, 17 },
            { 39, 38, 35, 32, 49, 18 },
            { 41, 33, 40, 10, 42, 19 },

            { 36, 44, 43, 20, 21 },
            { 23, 49, 45, 42, 22, 23, 24 },
            { 23, 32, 26, 48, 22, 36, 34, 46, 47 },
            { 41, 39, 27, 33, 34, 32, 43, 25 }
    };

    // sky_fluid_tool_head_drill
    static ItemStack[] skyDrill = {
            ChemicalHelper.get(toolHeadDrill, Steel, 1),
            ChemicalHelper.get(toolHeadDrill, Titanium, 1),
            ChemicalHelper.get(toolHeadDrill, NaquadahAlloy, 1),
            ChemicalHelper.get(toolHeadDrill, Neutronium, 1),
            new ItemStack(Registries.getBlock("kubejs:machine_casing_grinding_head"), 1)
    };

    //
    static int[] skyDrill_n = {
            1,
            16,
            128,
            1024
    };

    public static void init(Consumer<FinishedRecipe> provider) {
        if (ConfigHolder.INSTANCE.content.enableSkyBlokeMode) {
            VanillaRecipeHelper.addShapedRecipe(provider,
                    GTLCore.id("make_fragment_world_collection_machine"), GTLMachines.FRAGMENT_WORLD_COLLECTION_MACHINE[ULV].asStack(),
                    "WNW", "PCQ", "XNX",
                    'W', new UnificationEntry(cableGtSingle, Lead),
                    'N', GTBlocks.RUBBER_LOG,
                    'P', TREASURES_CRYSTAL,
                    'Q', MINING_CRYSTAL,
                    'C', GTMachines.HULL[1].asStack(),
                    'X', Blocks.ANVIL);

            VanillaRecipeHelper.addShapedRecipe(provider, true, "make_large_fragment_world_collection_machine",
                    LARGE_FRAGMENT_WORLD_COLLECTION_MACHINE.asStack(), "WNW", "PCQ", "XNX",
                    'W', CustomTags.IV_CIRCUITS,
                    'N', GTItems.FIELD_GENERATOR_EV,
                    'P', TREASURES_CRYSTAL,
                    'Q', MINING_CRYSTAL,
                    'C', GTLMachines.FRAGMENT_WORLD_COLLECTION_MACHINE[ULV].asStack(),
                    'X', GTBlocks.CASING_TUNGSTENSTEEL_GEARBOX.asStack());

            for (int i = 0; i < 16; i++) {
                for (int k = skyDNumber[i][0]; k < (skyDNumber[i][0] + skyDNumber[i][1]); k++) {
                    int circuit = (i == 0 ? (k + 2) : (k + 1));
                    for (int m = 0; m < 5; m++) {
                        int fluid_output = (m == 4 ? Integer.MAX_VALUE : skyFluid_n[skyDMap[i][k]] * skyDrill_n[m]);
                        FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_" + (i + 1) + "_" + (k + 1) + "_" + (m + 1))
                                .notConsumable(skyFragments[i])
                                .chancedInput(skyDrill[m], 100 - (m * 10), 0)
                                .chancedOutput(skyFragments[i], 50, 0)
                                .chancedOutput(MINING_CRYSTAL.asStack(1), 5, 5)
                                .outputFluids(skyFluid[skyDMap[i][k]].getFluid(fluid_output))
                                .circuitMeta(circuit)
                                .duration(200)
                                .EUt(8)
                                .save(provider);
                    }
                }
            }

            for (int i = 0; i < skyDNumber.length; i++) {
                for (int j = 0; j < skyDNumber[i][0]; j++) {
                    var build = FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_" + (i + 1) + "_" + (j + 1));
                    // base append
                    build
                            .notConsumable(skyFragments[i])
                            .chancedOutput(skyFragments[i], 50, 0)
                            .chancedOutput(MINING_CRYSTAL.asStack(1), 5, 5)
                            .outputItems(rawOre, skyOres[skyDMap[i][j]][0], skyOres_n[skyDMap[i][j]][0])
                            .outputItems(rawOre, skyOres[skyDMap[i][j]][1], skyOres_n[skyDMap[i][j]][1])
                            .outputItems(rawOre, skyOres[skyDMap[i][j]][2], skyOres_n[skyDMap[i][j]][2])
                            .outputItems(rawOre, skyOres[skyDMap[i][j]][3], skyOres_n[skyDMap[i][j]][3])
                            .EUt(8)
                            .duration(24000 / skyOres_n[skyDMap[i][j]][4])
                            .circuitMeta(i == 0 ? j + 2 : j + 1);
                    // o
                    switch (i) {
                        case 0 -> build
                                .outputItems(Blocks.STONE.asItem(), 32)
                                .outputItems(dust, Stone, 32)
                                .outputItems(Blocks.DEEPSLATE.asItem(), 32)
                                .outputItems(dust, Deepslate, 32);
                        case 1 -> build
                                .outputItems(Blocks.NETHERRACK.asItem(), 32)
                                .outputItems(dust, Netherrack, 32)
                                .outputItems(Blocks.BASALT.asItem(), 32)
                                .outputItems(dust, Basalt, 32);
                        case 2 -> build
                                .outputItems(Blocks.END_STONE.asItem(), 64)
                                .outputItems(dust, Endstone, 64);
                        case 3 -> build
                                .outputItems(Blocks.DIORITE.asItem(), 64)
                                .outputItems(dust, Diorite, 64);
                        case 4 -> build.outputItems(ModItems.MOON_STONE, 64);
                        case 5 -> build.outputItems(ModItems.MARS_STONE, 64);
                        case 6 -> build.outputItems(ModItems.VENUS_STONE, 64);
                        case 7 -> build.outputItems(ModItems.MERCURY_STONE, 64);
                        case 8 -> build.outputItems(new ItemStack(Registries.getBlock("kubejs:ceresstone"), 64));
                        case 9 -> build.outputItems(new ItemStack(Registries.getBlock("kubejs:iostone"), 64));
                        case 10 -> build.outputItems(new ItemStack(Registries.getBlock("kubejs:ganymedestone"), 64));
                        case 11 -> build.outputItems(new ItemStack(Registries.getBlock("kubejs:plutostone"), 64));
                        case 12 -> build.outputItems(new ItemStack(Registries.getBlock("kubejs:enceladusstone"), 64));
                        case 13 -> build.outputItems(new ItemStack(Registries.getBlock("kubejs:titanstone"), 64));
                        case 14 -> build.outputItems(ModItems.GLACIO_STONE, 64);
                        case 15 -> build.outputItems(Blocks.STONE.asItem(), 64);
                    }
                    build.save(provider);
                }
            }

            {
                FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_special_1")
                        .notConsumable(WORLD_FRAGMENTS_OVERWORLD.asStack(1))
                        .chancedOutput(WORLD_FRAGMENTS_OVERWORLD.asStack(1), 50, 0)
                        .chancedOutput(TREASURES_CRYSTAL.asStack(), 5, 5)
                        .chancedOutput(new ItemStack(Blocks.DIRT, 16), 6000, 0)
                        .chancedOutput(new ItemStack(Blocks.GRAVEL, 16), 4000, 0)
                        .chancedOutput(new ItemStack(Blocks.SAND, 16), 3000, 0)
                        .chancedOutput(new ItemStack(Items.CLAY_BALL, 64), 2000, 0)
                        .chancedOutput(new ItemStack(Items.OAK_SAPLING, 8), 2000, 0)
                        .chancedOutput(new ItemStack(Items.BIRCH_SAPLING, 8), 2000, 0)
                        .chancedOutput(new ItemStack(Items.SPRUCE_SAPLING, 8), 2000, 0)
                        .chancedOutput(new ItemStack(Items.JUNGLE_SAPLING, 8), 2000, 0)
                        .chancedOutput(new ItemStack(Items.CHERRY_SAPLING, 8), 2000, 0)
                        .chancedOutput(new ItemStack(Items.MANGROVE_PROPAGULE, 8), 2000, 0)
                        .chancedOutput(Lava.getFluid(1000), 500, 0)
                        .circuitMeta(0)
                        .duration(200)
                        .EUt(8)
                        .save(provider);

                FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_special_2")
                        .notConsumable(WORLD_FRAGMENTS_OVERWORLD.asStack(1))
                        .chancedOutput(WORLD_FRAGMENTS_OVERWORLD.asStack(1), 50, 0)
                        .chancedOutput(TREASURES_CRYSTAL.asStack(), 5, 5)
                        .chancedOutput(new ItemStack(Items.SUGAR_CANE, 8), 2000, 0)
                        .chancedOutput(new ItemStack(GTBlocks.RUBBER_SAPLING, 4), 1000, 0)
                        .chancedOutput(new ItemStack(Items.LEATHER, 4), 500, 0)
                        .chancedOutput(new ItemStack(Items.STRING, 8), 500, 0)
                        .chancedOutput(new ItemStack(Items.HONEYCOMB, 1), 2000, 0)
                        .chancedOutput(new ItemStack(Items.KELP, 1), 2000, 0)
                        .chancedOutput(new ItemStack(Blocks.SCULK_SHRIEKER, 2), 100, 0)
                        .chancedOutput(new ItemStack(Blocks.SCULK_SENSOR, 2), 100, 0)
                        .chancedOutput(new ItemStack(Blocks.SOUL_SAND, 4), 5, 0)
                        .chancedOutput(new ItemStack(Items.TOTEM_OF_UNDYING, 1), 10, 0)
                        .chancedOutput(RawOil.getFluid(1000), 2000, 0)
                        .circuitMeta(1)
                        .duration(200)
                        .EUt(8)
                        .save(provider);

                FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_special_3")
                        .notConsumable(WORLD_FRAGMENTS_REACTOR.asStack(1))
                        .chancedOutput(WORLD_FRAGMENTS_REACTOR.asStack(1), 50, 0)
                        .chancedOutput(TREASURES_CRYSTAL.asStack(), 5, 5)
                        .chancedOutput(new ItemStack(Blocks.DIRT, 16), 6000, 0)
                        .chancedOutput(new ItemStack(Blocks.DIORITE, 16), 6000, 0)
                        .chancedOutput(new ItemStack(Blocks.ANDESITE, 16), 6000, 0)
                        .chancedOutput(new ItemStack(Blocks.GRANITE, 16), 6000, 0)
                        .chancedOutput(new ItemStack(GTBlocks.RED_GRANITE, 16), 6000, 0)
                        .chancedOutput(new ItemStack(GTBlocks.MARBLE, 16), 6000, 0)
                        .chancedOutput(new ItemStack(Blocks.SUSPICIOUS_SAND, 16), 6000, 0)
                        .chancedOutput(new ItemStack(Blocks.SUSPICIOUS_GRAVEL, 16), 6000, 0)
                        .chancedOutput(new ItemStack(Registries.getItem("ae2:mysterious_cube"), 1), 100, 0)
                        .chancedOutput(new ItemStack(Registries.getItem("ae2:sky_stone_block"), 16), 500, 0)
                        .circuitMeta(0)
                        .duration(200)
                        .EUt(8)
                        .save(provider);

                FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_special_4")
                        .notConsumable(WORLD_FRAGMENTS_NETHER.asStack(1))
                        .chancedOutput(WORLD_FRAGMENTS_NETHER.asStack(1), 50, 0)
                        .chancedOutput(TREASURES_CRYSTAL.asStack(), 5, 5)
                        .chancedOutput(new ItemStack(Blocks.SOUL_SAND, 16), 6000, 0)
                        .chancedOutput(new ItemStack(Blocks.SOUL_SOIL, 16), 3000, 0)
                        .chancedOutput(new ItemStack(Blocks.ANCIENT_DEBRIS, 4), 500, 0)
                        .chancedOutput(new ItemStack(Items.NETHER_WART, 12), 200, 0)
                        .chancedOutput(new ItemStack(Items.CRIMSON_FUNGUS, 8), 2000, 0)
                        .chancedOutput(new ItemStack(Items.WARPED_FUNGUS, 8), 2000, 0)
                        .chancedOutput(new ItemStack(Items.BLAZE_ROD, 8), 500, 0)
                        .chancedOutput(Lava.getFluid(8000), 5000, 0)
                        .circuitMeta(0)
                        .duration(200)
                        .EUt(8)
                        .save(provider);

                FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_special_5")
                        .notConsumable(WORLD_FRAGMENTS_END.asStack(1))
                        .chancedOutput(WORLD_FRAGMENTS_END.asStack(1), 50, 0)
                        .chancedOutput(TREASURES_CRYSTAL.asStack(), 5, 5)
                        .chancedOutput(new ItemStack(Blocks.DRAGON_EGG, 1), 5, 0)
                        .chancedOutput(new ItemStack(Blocks.DRAGON_HEAD, 1), 5, 0)
                        .chancedOutput(new ItemStack(Items.DRAGON_BREATH, 1), 500, 0)
                        .chancedOutput(new ItemStack(Items.SHULKER_SHELL, 8), 2000, 0)
                        .chancedOutput(new ItemStack(Items.CHORUS_FRUIT, 16), 4000, 0)
                        .chancedOutput(new ItemStack(Items.CHORUS_FLOWER, 1), 500, 0)
                        .circuitMeta(0)
                        .duration(200)
                        .EUt(8)
                        .save(provider);

                FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_special_6")
                        .notConsumable(WORLD_FRAGMENTS_GLACIO.asStack(1))
                        .chancedOutput(WORLD_FRAGMENTS_GLACIO.asStack(1), 50, 0)
                        .chancedOutput(TREASURES_CRYSTAL.asStack(), 5, 5)
                        .chancedOutput(new ItemStack(Registries.getItem("kubejs:glacio_spirit"), 1), 500, 0)
                        .chancedOutput(new ItemStack(ModItems.ICE_SHARD.get(), 1), 9500, 0)
                        .circuitMeta(0)
                        .duration(200)
                        .EUt(8)
                        .save(provider);

                FRAGMENT_WORLD_COLLECTION.recipeBuilder("sky_block_digging_special_7")
                        .notConsumable(WORLD_FRAGMENTS_BARNARDA.asStack(1))
                        .chancedOutput(WORLD_FRAGMENTS_BARNARDA.asStack(1), 50, 0)
                        .chancedOutput(TREASURES_CRYSTAL.asStack(), 5, 5)
                        .chancedOutput(new ItemStack(Registries.getBlock("kubejs:barnarda_log"), 1), 500, 0)
                        .chancedOutput(new ItemStack(Registries.getBlock("kubejs:barnarda_leaves"), 1), 9500, 0)
                        .chancedOutput(BarnardaAir.getFluid(16000), 2000, 0)
                        .circuitMeta(0)
                        .duration(200)
                        .EUt(8)
                        .save(provider);
            }

            WORLD_DATA_SCANNER_RECIPES.recipeBuilder("sky_block_make_overworld_data")
                    .notConsumable(WORLD_FRAGMENTS_OVERWORLD)
                    .inputItems(GTItems.TOOL_DATA_STICK.asStack())
                    .inputItems(dust, Stone, 64)
                    .inputFluids(PCBCoolant.getFluid(100))
                    .inputFluids(Air.getFluid(64000))
                    .outputItems(Registries.getItem("kubejs:overworld_data"))
                    .duration(4000)
                    .EUt(32)
                    .save(provider);

            WORLD_DATA_SCANNER_RECIPES.recipeBuilder("sky_block_make_nether_data")
                    .notConsumable(WORLD_FRAGMENTS_VENUS)
                    .inputItems(GTItems.TOOL_DATA_STICK.asStack(2))
                    .inputItems(dust, Netherrack, 64)
                    .inputFluids(PCBCoolant.getFluid(200))
                    .inputFluids(NetherAir.getFluid(64000))
                    .outputItems(Registries.getItem("kubejs:nether_data"))
                    .duration(4000)
                    .EUt(128)
                    .save(provider);

            WORLD_DATA_SCANNER_RECIPES.recipeBuilder("sky_block_make_end_data")
                    .notConsumable(WORLD_FRAGMENTS_PLUTO)
                    .inputItems(GTItems.TOOL_DATA_STICK.asStack(4))
                    .inputItems(dust, Endstone, 64)
                    .inputFluids(PCBCoolant.getFluid(400))
                    .inputFluids(EnderAir.getFluid(64000))
                    .outputItems(Registries.getItem("kubejs:end_data"))
                    .duration(4000)
                    .EUt(512)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_damascus_steel_dust")
                    .notConsumable(WORLD_FRAGMENTS_REACTOR)
                    .inputItems(dust, Steel, 1)
                    .inputFluids(Lubricant.getFluid(100))
                    .outputItems(dust, DamascusSteel, 1)
                    .circuitMeta(9)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            QFT_RECIPES.recipeBuilder("make_miracle_crystal")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .inputItems(WORLD_FRAGMENTS_NETHER, 1)
                    .inputItems(WORLD_FRAGMENTS_END, 1)
                    .inputItems(WORLD_FRAGMENTS_REACTOR, 1)
                    .inputItems(WORLD_FRAGMENTS_MOON, 1)
                    .inputItems(WORLD_FRAGMENTS_MARS, 1)
                    .inputItems(WORLD_FRAGMENTS_VENUS, 1)
                    .inputItems(WORLD_FRAGMENTS_MERCURY, 1)
                    .inputItems(WORLD_FRAGMENTS_CERES, 1)
                    .inputItems(WORLD_FRAGMENTS_IO, 1)
                    .inputItems(WORLD_FRAGMENTS_GANYMEDE, 1)
                    .inputItems(WORLD_FRAGMENTS_PLUTO, 1)
                    .inputItems(WORLD_FRAGMENTS_ENCELADUS, 1)
                    .inputItems(WORLD_FRAGMENTS_TITAN, 1)
                    .inputItems(WORLD_FRAGMENTS_GLACIO, 1)
                    .inputItems(WORLD_FRAGMENTS_BARNARDA, 1)
                    .inputItems(MINING_CRYSTAL, 1)
                    .inputItems(TREASURES_CRYSTAL, 1)
                    .inputFluids(UUMatter.getFluid(100000000))
                    .inputFluids(Eternity.getFluid(100000))
                    .inputFluids(Magmatter.getFluid(100000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 3000, 0)
                    .duration(2000)
                    .EUt(VEX[19])
                    .save(provider);

            QFT_RECIPES.recipeBuilder("make_miracle_crystal2")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .inputItems(WORLD_FRAGMENTS_NETHER, 1)
                    .inputItems(WORLD_FRAGMENTS_END, 1)
                    .inputItems(WORLD_FRAGMENTS_REACTOR, 1)
                    .inputItems(WORLD_FRAGMENTS_MOON, 1)
                    .inputItems(WORLD_FRAGMENTS_MARS, 1)
                    .inputItems(WORLD_FRAGMENTS_VENUS, 1)
                    .inputItems(WORLD_FRAGMENTS_MERCURY, 1)
                    .inputItems(WORLD_FRAGMENTS_CERES, 1)
                    .inputItems(WORLD_FRAGMENTS_IO, 1)
                    .inputItems(WORLD_FRAGMENTS_GANYMEDE, 1)
                    .inputItems(WORLD_FRAGMENTS_PLUTO, 1)
                    .inputItems(WORLD_FRAGMENTS_ENCELADUS, 1)
                    .inputItems(WORLD_FRAGMENTS_TITAN, 1)
                    .inputItems(WORLD_FRAGMENTS_GLACIO, 1)
                    .inputItems(WORLD_FRAGMENTS_BARNARDA, 1)
                    .inputItems(MINING_CRYSTAL, 1)
                    .inputItems(TREASURES_CRYSTAL, 1)
                    .inputFluids(Miracle.getFluid(1000))
                    .inputFluids(Eternity.getFluid(100000))
                    .inputFluids(Magmatter.getFluid(100000))
                    .outputItems(MIRACLE_CRYSTAL, 1)
                    .duration(2000)
                    .EUt(VEX[20])
                    .save(provider);
        }

        {
            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_1")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(Registries.getItem("kubejs:reactor_core"))
                    .inputItems(block, Steel, 4)
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_REACTOR, 1)
                    .dimension(new ResourceLocation("overworld"))
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_2")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(ModItems.TIER_1_ROCKET)
                    .inputFluids(RocketFuel.getFluid(16000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_MOON, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_3")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(ModItems.TIER_2_ROCKET)
                    .inputFluids(RocketFuelRp1.getFluid(16000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_MARS, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_4")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(ModItems.TIER_3_ROCKET)
                    .inputFluids(DenseHydrazineFuelMixture.getFluid(16000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_VENUS, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_5")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(ModItems.TIER_3_ROCKET)
                    .inputFluids(DenseHydrazineFuelMixture.getFluid(16000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_MERCURY, 1)
                    .circuitMeta(31)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_6")
                    .inputItems(WORLD_FRAGMENTS_VENUS, 1)
                    .notConsumable(Registries.getItem("kubejs:nether_data"))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_NETHER, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_7")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(ModItems.TIER_4_ROCKET)
                    .inputFluids(RocketFuelCn3h7o3.getFluid(16000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_CERES, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_8")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(Registries.getItem("ad_astra_rocketed:tier_5_rocket"))
                    .inputFluids(RocketFuelH8n4c2o4.getFluid(16000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_IO, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_9")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(Registries.getItem("ad_astra_rocketed:tier_5_rocket"))
                    .inputFluids(RocketFuelH8n4c2o4.getFluid(16000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_GANYMEDE, 1)
                    .circuitMeta(31)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_13")
                    .inputItems(WORLD_FRAGMENTS_PLUTO, 1)
                    .notConsumable(new ItemStack(Registries.getItem("kubejs:end_data"), 16))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_END, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_14")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(Registries.getItem("ad_astra_rocketed:tier_7_rocket"))
                    .inputFluids(StellarEnergyRocketFuel.getFluid(16000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_GLACIO, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            FRAGMENT_WORLD_COLLECTION.recipeBuilder("make_world_fragments_15")
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .notConsumable(SPACE_ELEVATOR.getItem())
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 1, 0)
                    .outputItems(WORLD_FRAGMENTS_BARNARDA, 1)
                    .circuitMeta(32)
                    .duration(200)
                    .EUt(8)
                    .save(provider);

            for (int i = 0; i < 16; i++) {
                ELEMENT_COPYING_RECIPES.recipeBuilder("copy_world_fragments_" + (i))
                        .notConsumable(skyFragments[i])
                        .inputFluids(UUMatter.getFluid(1000000))
                        .outputItems(skyFragments[i])
                        .duration(200)
                        .EUt(VEX[18])
                        .save(provider);
            }

        }

        {
            MASS_FABRICATOR_RECIPES.recipeBuilder("make_miracle_gas")
                    .inputItems(MIRACLE_CRYSTAL, 1)
                    .inputFluids(PrimordialMatter.getFluid(100000))
                    .outputFluids(MiracleGas.getFluid(90000))
                    .duration(2000)
                    .EUt(VEX[21])
                    .save(provider);

            DISTILLATION_RECIPES.recipeBuilder("make_miracle")
                    .inputFluids(MiracleGas.getFluid(100000))
                    .outputItems(MIRACLE_CRYSTAL, 1)
                    .outputFluids(PrimordialMatter.getFluid(90000))
                    .outputFluids(Miracle.getFluid(1000))
                    .duration(6000)
                    .EUt(VEX[22])
                    .save(provider);

            PRECISION_ASSEMBLER_RECIPES.recipeBuilder("make_super_glue")
                    .inputItems(new ItemStack(Registries.getItem("kubejs:hyper_stable_self_healing_adhesive"), 64))
                    .inputItems(new ItemStack(Registries.getItem("kubejs:cosmic_fabric"), 32))
                    .inputItems(foil, Shirabon, 64)
                    .inputItems(new ItemStack(Registries.getItem("kubejs:chain_command_block_core"), 8))
                    .inputFluids(Miracle.getFluid(1000000))
                    .inputFluids(Infinity.getFluid(1000000))
                    .inputFluids(PrimordialMatter.getFluid(1000000))
                    .inputFluids(SuperMutatedLivingSolder.getFluid(1000000))
                    .outputItems(SUPER_GLUE)
                    .duration(10000)
                    .EUt(VEX[23])
                    .save(provider);

            ELECTRIC_IMPLOSION_COMPRESSOR_RECIPES.recipeBuilder("make_big_fish")
                    .inputItems(Items.PUFFERFISH, 2000000000)
                    .inputItems(block, MagnetohydrodynamicallyConstrainedStarMatter, 1000000)
                    .outputItems(COMPRESSED_PUFFERFISH)
                    .duration(200)
                    .EUt(VEX[26])
                    .save(provider);

        }

        if (!ConfigHolder.INSTANCE.content.enableSkyBlokeMode) {

            VanillaRecipeHelper.addShapedRecipe(provider,
                    GTLCore.id("make_fragment_world_collection_machine"), GTLMachines.FRAGMENT_WORLD_COLLECTION_MACHINE[ULV].asStack(),
                    "WNW", "PCP", "XNX",
                    'X', new UnificationEntry(cableGtSingle, CosmicNeutronium),
                    'N', CustomTags.MAX_CIRCUITS,
                    'P', SENSOR_MAX,
                    'C', GTMachines.HULL[14].asStack(),
                    'W', FIELD_GENERATOR_MAX);

            QFT_RECIPES.recipeBuilder("make_miracle_crystal")
                    .notConsumable(new ItemStack(Registries.getItem("kubejs:giga_chad"), 64))
                    .chancedInput(ChemicalHelper.get(nanoswarm, CosmicNeutronium, 4), 1000, 0)
                    .chancedInput(ChemicalHelper.get(nanoswarm, SpaceTime, 4), 1000, 0)
                    .chancedInput(ChemicalHelper.get(nanoswarm, TranscendentMetal, 4), 1000, 0)
                    .chancedInput(ChemicalHelper.get(nanoswarm, Eternity, 4), 1000, 0)
                    .inputItems(new ItemStack(Registries.getItem("kubejs:chaotic_core"), 16))
                    .inputItems(new ItemStack(Registries.getItem("kubejs:quantum_anomaly"), 16))
                    .inputFluids(ExcitedDtec.getFluid(1000000))
                    .inputFluids(TranscendentMetal.getFluid(100000))
                    .inputFluids(Shirabon.getFluid(100000))
                    .chancedOutput(MIRACLE_CRYSTAL.asStack(), 6000, 0)
                    .duration(1000)
                    .EUt(VEX[18])
                    .save(provider);

            QFT_RECIPES.recipeBuilder("make_miracle_crystal2")
                    .chancedInput(new ItemStack(Registries.getItem("kubejs:chaotic_core"), 16), 1000, 0)
                    .chancedInput(new ItemStack(Registries.getItem("kubejs:quantum_anomaly"), 16), 1000, 0)
                    .inputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .inputItems(WORLD_FRAGMENTS_NETHER, 1)
                    .inputItems(WORLD_FRAGMENTS_END, 1)
                    .inputItems(WORLD_FRAGMENTS_REACTOR, 1)
                    .inputItems(WORLD_FRAGMENTS_MOON, 1)
                    .inputItems(WORLD_FRAGMENTS_MARS, 1)
                    .inputItems(WORLD_FRAGMENTS_VENUS, 1)
                    .inputItems(WORLD_FRAGMENTS_MERCURY, 1)
                    .inputItems(WORLD_FRAGMENTS_CERES, 1)
                    .inputItems(WORLD_FRAGMENTS_IO, 1)
                    .inputItems(WORLD_FRAGMENTS_GANYMEDE, 1)
                    .inputItems(WORLD_FRAGMENTS_PLUTO, 1)
                    .inputItems(WORLD_FRAGMENTS_ENCELADUS, 1)
                    .inputItems(WORLD_FRAGMENTS_TITAN, 1)
                    .inputItems(WORLD_FRAGMENTS_GLACIO, 1)
                    .inputItems(WORLD_FRAGMENTS_BARNARDA, 1)
                    .inputFluids(Miracle.getFluid(1000))
                    .inputFluids(TranscendentMetal.getFluid(100000))
                    .inputFluids(Shirabon.getFluid(100000))
                    .outputItems(MIRACLE_CRYSTAL, 1)
                    .duration(1000)
                    .EUt(VEX[19])
                    .save(provider);

            GRAVITATION_SHOCKBURST_RECIPES.recipeBuilder("make_world_fragments_0")
                    .inputItems(Blocks.GRASS_BLOCK.asItem(), 64)
                    .inputItems(MIRACLE_CRYSTAL, 1)
                    .outputItems(WORLD_FRAGMENTS_OVERWORLD, 1)
                    .duration(200)
                    .EUt(VEX[17])
                    .dimension(new ResourceLocation("overworld"))
                    .save(provider);

        }
    }
}
