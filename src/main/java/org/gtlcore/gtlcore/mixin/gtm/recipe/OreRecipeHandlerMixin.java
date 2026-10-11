package org.gtlcore.gtlcore.mixin.gtm.recipe;

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags;
import com.gregtechceu.gtceu.api.data.chemical.material.properties.OreProperty;
import com.gregtechceu.gtceu.api.data.chemical.material.properties.PropertyKey;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.MaterialStack;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.common.data.GTMaterials;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.data.recipe.VanillaRecipeHelper;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import com.gregtechceu.gtceu.data.recipe.generated.OreRecipeHandler;
import com.gregtechceu.gtceu.utils.GTUtil;

import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.mojang.datafixers.util.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags.HIGH_SIFTER_OUTPUT;
import static com.gregtechceu.gtceu.api.data.tag.TagPrefix.*;
import static com.gregtechceu.gtceu.common.data.GTMaterials.DistilledWater;
import static com.gregtechceu.gtceu.common.data.GTMaterials.Stone;
import static com.gregtechceu.gtceu.common.data.GTRecipeTypes.*;
import static org.gtlcore.gtlcore.common.data.GTLRecipeTypes.INTEGRATED_ORE_PROCESSOR;

@Mixin(OreRecipeHandler.class)
public abstract class OreRecipeHandlerMixin {

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void init(Consumer<FinishedRecipe> provider) {
        for (TagPrefix ore : ORES.keySet()) {
            Supplier<Material> material = ORES.get(ore).material();
            if (material != null && material.get() == GTMaterials.Stone) {
                ore.executeHandler(provider, PropertyKey.ORE, OreRecipeHandlerMixin::gtlcore$processOre);
                ore.executeHandler(provider, PropertyKey.ORE, OreRecipeHandlerMixin::gtlcore$processOreForgeHammer);
            }
        }

        rawOre.executeHandler(provider, PropertyKey.ORE, OreRecipeHandlerMixin::gtlcore$processRawOre);

        crushed.executeHandler(provider, PropertyKey.ORE, OreRecipeHandler::processCrushedOre);
        crushedPurified.executeHandler(provider, PropertyKey.ORE, OreRecipeHandler::processCrushedPurified);
        crushedRefined.executeHandler(provider, PropertyKey.ORE, OreRecipeHandler::processCrushedCentrifuged);
        dustImpure.executeHandler(provider, PropertyKey.ORE, OreRecipeHandler::processDirtyDust);
        dustPure.executeHandler(provider, PropertyKey.ORE, OreRecipeHandler::processPureDust);
    }

    @Unique
    private static void gtlcore$processOreForgeHammer(TagPrefix orePrefix, Material material, OreProperty property, Consumer<FinishedRecipe> provider) {
        ItemStack crushedStack = ChemicalHelper.get(crushed, material);
        int amountOfCrushedOre = property.getOreMultiplier();
        int oreTypeMultiplier = org.gtlcore.gtlcore.config.ConfigHolder.INSTANCE.content.oreMultiplier;
        crushedStack.setCount(crushedStack.getCount() * property.getOreMultiplier());

        GTRecipeBuilder builder = FORGE_HAMMER_RECIPES
                .recipeBuilder("hammer_" + material.getName() + "_ore_to_raw_ore")
                .inputItems(orePrefix.getItemTags(material)[0])
                .duration(10).EUt(16);
        if (material.hasProperty(PropertyKey.GEM) && !gem.isIgnored(material)) {
            builder.outputItems(GTUtil.copyAmount(amountOfCrushedOre * oreTypeMultiplier,
                    ChemicalHelper.get(gem, material, crushedStack.getCount())));
        } else {
            builder.outputItems(GTUtil.copyAmount(amountOfCrushedOre * oreTypeMultiplier, crushedStack));
        }
        builder.save(provider);
    }

    @Unique
    private static void gtlcore$processOre(TagPrefix orePrefix, Material material, OreProperty property, Consumer<FinishedRecipe> provider) {
        TagKey<Item> tag = orePrefix.getItemTags(material)[0];
        Material byproductMaterial = GTUtil.selectItemInList(0, material, property.getOreByProducts(), Material.class);
        ItemStack ingotStack;
        ItemStack byproductStack = ChemicalHelper.get(gem, byproductMaterial);
        if (byproductStack.isEmpty()) byproductStack = ChemicalHelper.get(dust, byproductMaterial);
        Material smeltingMaterial = property.getDirectSmeltResult() == null ? material :
                property.getDirectSmeltResult();
        ItemStack crushedStack = ChemicalHelper.get(crushed, material);
        int amountOfCrushedOre = property.getOreMultiplier();
        if (smeltingMaterial.hasProperty(PropertyKey.INGOT)) {
            ingotStack = ChemicalHelper.get(ingot, smeltingMaterial);
        } else if (smeltingMaterial.hasProperty(PropertyKey.GEM)) {
            ingotStack = ChemicalHelper.get(gem, smeltingMaterial);
        } else {
            ingotStack = ChemicalHelper.get(dust, smeltingMaterial);
        }
        int oreTypeMultiplier = org.gtlcore.gtlcore.config.ConfigHolder.INSTANCE.content.oreMultiplier;
        ingotStack.setCount(ingotStack.getCount() * property.getOreMultiplier() * oreTypeMultiplier);
        crushedStack.setCount(crushedStack.getCount() * property.getOreMultiplier());

        if (!crushedStack.isEmpty()) {
            GTRecipeBuilder builder = MACERATOR_RECIPES
                    .recipeBuilder("macerate_" + material.getName() + "_ore_to_raw_ore")
                    .inputItems(tag)
                    .outputItems(GTUtil.copyAmount(amountOfCrushedOre * 2 * oreTypeMultiplier, crushedStack))
                    .chancedOutput(byproductStack, 1400, 850)
                    .EUt(30)
                    .duration(26);

            for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                    ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial);
                    builder.chancedOutput(dustStack, 6700, 800);
                }
            }

            builder.save(provider);

            int crushedAmount = amountOfCrushedOre * 2 * oreTypeMultiplier;

            // 1 研磨-研磨-离心
            GTRecipeBuilder opBuilder1 = INTEGRATED_ORE_PROCESSOR
                    .recipeBuilder("integrated_ore_processor_1_" + material.getName())
                    .circuitMeta(1)
                    .inputItems(tag)
                    .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                    .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                    .chancedOutput(ChemicalHelper.get(dust, byproductMaterial,
                            property.getByProductMultiplier() * crushedAmount),
                            1400, 850) // 第二步副产
                    .duration((int) (26 + (26 + (material.getMass() * 4)) * crushedAmount))
                    .EUt(30);
            for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                    ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial).copyWithCount(crushedAmount);
                    opBuilder1.chancedOutput(dustStack, 6700, 800);
                }
            }
            Material byproduct1 = GTUtil.selectItemInList(0, material, property.getOreByProducts(), Material.class);
            if (byproduct1.hasProperty(PropertyKey.DUST)) {
                opBuilder1.chancedOutput(TagPrefix.dust, byproduct1, crushedAmount, "1/9", 0); // 第三步副产
            }
            opBuilder1.save(provider);

            // 2 研磨-洗矿-热离-研磨
            Material byproductMaterial1 = GTUtil.selectItemInList(
                    1, material, property.getOreByProducts(), Material.class);
            ItemStack byproductStack2 = ChemicalHelper.get(dust, GTUtil.selectItemInList(2,
                    material, property.getOreByProducts(), Material.class), crushedAmount);
            ItemStack crushedCentrifugedStack = ChemicalHelper.get(crushedRefined, material);
            if (!crushedCentrifugedStack.isEmpty()) {
                GTRecipeBuilder opBuilder2 = INTEGRATED_ORE_PROCESSOR
                        .recipeBuilder("integrated_ore_processor_2_" + material.getName())
                        .circuitMeta(2)
                        .inputItems(tag)
                        .inputFluids(DistilledWater.getFluid(100L * crushedAmount))
                        .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                        .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                        .chancedOutput(TagPrefix.dust, byproductMaterial, crushedAmount, "1/3", 0) // 第二步副产
                        .outputItems(TagPrefix.dust, GTMaterials.Stone, crushedAmount) // 第二步副产
                        .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/3", 0) // 第三步副产
                        .chancedOutput(byproductStack2, 1400, 850) // 第四步副产
                        .duration(26 + (200 + 200 + 26) * crushedAmount)
                        .EUt(30);
                for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                    if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                        ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial).copyWithCount(crushedAmount);
                        opBuilder2.chancedOutput(dustStack, 6700, 800);
                    }
                }
                opBuilder2.save(provider);
            }

            // 3 研磨-洗矿-研磨-离心
            ItemStack byproductStack1 = ChemicalHelper.get(dust, byproductMaterial1, crushedAmount);
            GTRecipeBuilder opBuilder3 = INTEGRATED_ORE_PROCESSOR
                    .recipeBuilder("integrated_ore_processor_3_" + material.getName())
                    .circuitMeta(3)
                    .inputItems(tag)
                    .inputFluids(DistilledWater.getFluid(100L * crushedAmount))
                    .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                    .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                    .chancedOutput(TagPrefix.dust, byproductMaterial, crushedAmount, "1/3", 0) // 第二步副产
                    .outputItems(TagPrefix.dust, GTMaterials.Stone, crushedAmount) // 第二步副产
                    .chancedOutput(byproductStack1, 1400, 850) // 第三步副产
                    .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                    .duration(26 + (200 + 26 + 16) * crushedAmount)
                    .EUt(30);
            for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                    ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial).copyWithCount(crushedAmount);
                    opBuilder3.chancedOutput(dustStack, 6700, 800);
                }
            }
            opBuilder3.save(provider);

            // 4 研磨-洗矿-筛选-离心
            if (material.hasProperty(PropertyKey.GEM)) {
                ItemStack exquisiteStack = ChemicalHelper.get(gemExquisite, material, crushedAmount);
                ItemStack flawlessStack = ChemicalHelper.get(gemFlawless, material, crushedAmount);
                ItemStack gemStack = ChemicalHelper.get(gem, material, crushedAmount);
                if (material.hasFlag(HIGH_SIFTER_OUTPUT)) {
                    GTRecipeBuilder opBuilder4 = INTEGRATED_ORE_PROCESSOR
                            .recipeBuilder("integrated_ore_processor_4_" + material.getName())
                            .circuitMeta(4)
                            .inputItems(tag)
                            .inputFluids(DistilledWater.getFluid(100L * crushedAmount))
                            .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                            .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial, crushedAmount, "1/3", 0) // 第二步副产
                            .outputItems(TagPrefix.dust, GTMaterials.Stone, crushedAmount) // 第二步副产
                            .chancedOutput(exquisiteStack, 500, 150) // 第三步副产
                            .chancedOutput(flawlessStack, 1500, 200) // 第三步副产
                            .chancedOutput(gemStack, 5000, 1000) // 第三步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                            .duration(26 + (200 + 210 + 16) * crushedAmount)
                            .EUt(30);
                    for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                        if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                            ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial).copyWithCount(crushedAmount);
                            opBuilder4.chancedOutput(dustStack, 6700, 800);
                        }
                    }
                    opBuilder4.save(provider);
                } else {
                    GTRecipeBuilder opBuilder4 = INTEGRATED_ORE_PROCESSOR
                            .recipeBuilder("integrated_ore_processor_4_" + material.getName())
                            .circuitMeta(4)
                            .inputItems(tag)
                            .inputFluids(DistilledWater.getFluid(100L * crushedAmount))
                            .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                            .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial, crushedAmount, "1/3", 0) // 第二步副产
                            .outputItems(TagPrefix.dust, GTMaterials.Stone, crushedAmount) // 第二步副产
                            .chancedOutput(exquisiteStack, 300, 100) // 第三步副产
                            .chancedOutput(flawlessStack, 1000, 150) // 第三步副产
                            .chancedOutput(gemStack, 3500, 500) // 第三步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                            .duration(26 + (200 + 210 + 16) * crushedAmount)
                            .EUt(30);
                    for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                        if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                            ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial).copyWithCount(crushedAmount);
                            opBuilder4.chancedOutput(dustStack, 6700, 800);
                        }
                    }
                    opBuilder4.save(provider);
                }
            }
            if (property.getWashedIn().getFirst() != null) {
                Material washingByproduct = GTUtil.selectItemInList(3, material, property.getOreByProducts(),
                        Material.class);
                Pair<Material, Integer> washedInTuple = property.getWashedIn();
                // 5 研磨-浸洗-热离-研磨
                if (!crushedCentrifugedStack.isEmpty()) {
                    GTRecipeBuilder opBuilder5 = INTEGRATED_ORE_PROCESSOR
                            .recipeBuilder("integrated_ore_processor_5_" + material.getName())
                            .circuitMeta(5)
                            .inputItems(tag)
                            .inputFluids(washedInTuple.getFirst().getFluid(washedInTuple.getSecond() * crushedAmount))
                            .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                            .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                            .chancedOutput(ChemicalHelper.get(dust, washingByproduct,
                                    property.getByProductMultiplier() * crushedAmount),
                                    7000, 580) // 第二步副产
                            .chancedOutput(ChemicalHelper.get(dust, Stone, crushedAmount), 4000, 650) // 第二步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/3", 0) // 第三步副产
                            .chancedOutput(byproductStack2, 1400, 850) // 第四步副产
                            .duration(26 + (200 + 200 + 26) * crushedAmount)
                            .EUt(30);
                    for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                        if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                            ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial).copyWithCount(crushedAmount);
                            opBuilder5.chancedOutput(dustStack, 6700, 800);
                        }
                    }
                    opBuilder5.save(provider);
                }

                // 6 研磨-浸洗-研磨-离心
                GTRecipeBuilder opBuilder6 = INTEGRATED_ORE_PROCESSOR
                        .recipeBuilder("integrated_ore_processor_6_" + material.getName())
                        .circuitMeta(6)
                        .inputItems(tag)
                        .inputFluids(washedInTuple.getFirst().getFluid(washedInTuple.getSecond() * crushedAmount))
                        .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                        .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                        .chancedOutput(ChemicalHelper.get(dust, washingByproduct,
                                property.getByProductMultiplier() * crushedAmount),
                                7000, 580) // 第二步副产
                        .chancedOutput(ChemicalHelper.get(dust, Stone, crushedAmount), 4000, 650) // 第二步副产
                        .chancedOutput(byproductStack1, 1400, 850) // 第三步副产
                        .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                        .duration(26 + (200 + 26 + 16) * crushedAmount)
                        .EUt(30);
                for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                    if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                        ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial).copyWithCount(crushedAmount);
                        opBuilder6.chancedOutput(dustStack, 6700, 800);
                    }
                }
                opBuilder6.save(provider);

                // 7 研磨-浸洗-筛选-离心
                if (material.hasProperty(PropertyKey.GEM)) {
                    ItemStack exquisiteStack = ChemicalHelper.get(gemExquisite, material, crushedAmount);
                    ItemStack flawlessStack = ChemicalHelper.get(gemFlawless, material, crushedAmount);
                    ItemStack gemStack = ChemicalHelper.get(gem, material, crushedAmount);
                    if (material.hasFlag(HIGH_SIFTER_OUTPUT)) {
                        GTRecipeBuilder opBuilder7 = INTEGRATED_ORE_PROCESSOR
                                .recipeBuilder("integrated_ore_processor_7_" + material.getName())
                                .circuitMeta(7)
                                .inputItems(tag)
                                .inputFluids(washedInTuple.getFirst()
                                        .getFluid(washedInTuple.getSecond() * crushedAmount))
                                .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                                .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                                .chancedOutput(ChemicalHelper.get(dust, washingByproduct,
                                        property.getByProductMultiplier() * crushedAmount),
                                        7000, 580) // 第二步副产
                                .chancedOutput(ChemicalHelper.get(dust, Stone, crushedAmount), 4000, 650) // 第二步副产
                                .chancedOutput(exquisiteStack, 500, 150) // 第三步副产
                                .chancedOutput(flawlessStack, 1500, 200) // 第三步副产
                                .chancedOutput(gemStack, 5000, 1000) // 第三步副产
                                .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                                .duration(26 + (200 + 210 + 16) * crushedAmount)
                                .EUt(30);
                        for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                            if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                                ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial)
                                        .copyWithCount(crushedAmount);
                                opBuilder7.chancedOutput(dustStack, 6700, 800);
                            }
                        }
                        opBuilder7.save(provider);
                    } else {
                        GTRecipeBuilder opBuilder7 = INTEGRATED_ORE_PROCESSOR
                                .recipeBuilder("integrated_ore_processor_7_" + material.getName())
                                .circuitMeta(7)
                                .inputItems(tag)
                                .inputFluids(washedInTuple.getFirst()
                                        .getFluid(washedInTuple.getSecond() * crushedAmount))
                                .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                                .chancedOutput(byproductStack, 1400, 850) // 第一步副产
                                .chancedOutput(ChemicalHelper.get(dust, washingByproduct,
                                        property.getByProductMultiplier() * crushedAmount),
                                        7000, 580) // 第二步副产
                                .chancedOutput(ChemicalHelper.get(dust, Stone, crushedAmount), 4000, 650) // 第二步副产
                                .chancedOutput(exquisiteStack, 300, 100) // 第三步副产
                                .chancedOutput(flawlessStack, 1000, 150) // 第三步副产
                                .chancedOutput(gemStack, 3500, 500) // 第三步副产
                                .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                                .duration(26 + (200 + 210 + 16) * crushedAmount)
                                .EUt(30);
                        for (MaterialStack secondaryMaterial : orePrefix.secondaryMaterials()) {
                            if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                                ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial)
                                        .copyWithCount(crushedAmount);
                                opBuilder7.chancedOutput(dustStack, 6700, 800);
                            }
                        }
                        opBuilder7.save(provider);
                    }
                }
            }
        }

        // do not try to add smelting recipes for materials which require blast furnace
        if (!ingotStack.isEmpty() && gtlcore$doesMaterialUseNormalFurnace(smeltingMaterial) && !orePrefix.isIgnored(material)) {
            float xp = Math.round(((1 + oreTypeMultiplier * 0.5f) * 0.5f - 0.05f) * 10f) / 10f;
            VanillaRecipeHelper.addSmeltingRecipe(provider,
                    "smelt_" + material.getName() + "_ore_to_ingot", tag, ingotStack, xp);
            VanillaRecipeHelper.addBlastingRecipe(provider,
                    "smelt_" + material.getName() + "_ore_to_ingot", tag, ingotStack, xp);
        }
    }

    @Unique
    private static void gtlcore$processRawOre(TagPrefix orePrefix, Material material, OreProperty property, Consumer<FinishedRecipe> provider) {
        ItemStack crushedStack = ChemicalHelper.get(crushed, material,
                material.getProperty(PropertyKey.ORE).getOreMultiplier() * org.gtlcore.gtlcore.config.ConfigHolder.INSTANCE.content.oreMultiplier / 2);
        ItemStack ingotStack;
        Material smeltingMaterial = property.getDirectSmeltResult() == null ? material :
                property.getDirectSmeltResult();
        if (smeltingMaterial.hasProperty(PropertyKey.INGOT)) {
            ingotStack = ChemicalHelper.get(ingot, smeltingMaterial,
                    material.getProperty(PropertyKey.ORE).getOreMultiplier());
        } else if (smeltingMaterial.hasProperty(PropertyKey.GEM)) {
            ingotStack = ChemicalHelper.get(gem, smeltingMaterial,
                    material.getProperty(PropertyKey.ORE).getOreMultiplier());
        } else {
            ingotStack = ChemicalHelper.get(dust, smeltingMaterial,
                    material.getProperty(PropertyKey.ORE).getOreMultiplier());
        }

        if (!crushedStack.isEmpty()) {
            GTRecipeBuilder builder = FORGE_HAMMER_RECIPES
                    .recipeBuilder("hammer_" + orePrefix.name + "_" + material.getName() + "_to_crushed_ore")
                    .inputItems(orePrefix, material)
                    .duration(10).EUt(16);
            if (material.hasProperty(PropertyKey.GEM) && !gem.isIgnored(material)) {
                builder.outputItems(ChemicalHelper.get(gem, material, crushedStack.getCount()));
            } else {
                builder.outputItems(crushedStack.copy());
            }
            builder.save(provider);

            GTRecipeBuilder builder2 = MACERATOR_RECIPES
                    .recipeBuilder("macerate_" + orePrefix.name + "_" + material.getName() + "_ore_to_crushed_ore")
                    .inputItems(orePrefix, material)
                    .outputItems(GTUtil.copyAmount(crushedStack.getCount() * 2, crushedStack))
                    .EUt(30)
                    .duration(26);

            Material byproductMaterial = GTUtil.selectItemInList(0, material, property.getOreByProducts(),
                    Material.class);
            ItemStack byproductStack = ChemicalHelper.get(gem, byproductMaterial);
            if (byproductStack.isEmpty()) {
                byproductStack = ChemicalHelper.get(dust, byproductMaterial);
            }
            builder2.chancedOutput(byproductStack, 1000, 300);

            for (MaterialStack secondaryMaterial : ore.secondaryMaterials()) {
                if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                    ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial);
                    builder2.chancedOutput(dustStack, 500, 100);
                    break;
                }
            }
            builder2.save(provider);

            int crushedAmount = crushedStack.getCount() * 2;

            // 1 研磨-研磨-离心
            GTRecipeBuilder opBuilder1 = INTEGRATED_ORE_PROCESSOR
                    .recipeBuilder("raw_integrated_ore_processor_1_" + material.getName())
                    .circuitMeta(1)
                    .inputItems(orePrefix, material)
                    .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                    .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                    .chancedOutput(ChemicalHelper.get(dust, byproductMaterial,
                            property.getByProductMultiplier() * crushedAmount),
                            1400, 850) // 第二步副产
                    .duration((int) (26 + (26 + (material.getMass() * 4)) * crushedAmount))
                    .EUt(30);
            Material byproduct1 = GTUtil.selectItemInList(0, material, property.getOreByProducts(), Material.class);
            if (byproduct1.hasProperty(PropertyKey.DUST)) {
                opBuilder1.chancedOutput(TagPrefix.dust, byproduct1, crushedAmount, "1/9", 0); // 第三步副产
            }
            opBuilder1.save(provider);

            // 2 研磨-洗矿-热离-研磨
            Material byproductMaterial1 = GTUtil.selectItemInList(
                    1, material, property.getOreByProducts(), Material.class);
            ItemStack byproductStack2 = ChemicalHelper.get(dust, GTUtil.selectItemInList(2,
                    material, property.getOreByProducts(), Material.class), crushedAmount);
            ItemStack crushedCentrifugedStack = ChemicalHelper.get(crushedRefined, material);
            if (!crushedCentrifugedStack.isEmpty()) {
                GTRecipeBuilder opBuilder2 = INTEGRATED_ORE_PROCESSOR
                        .recipeBuilder("raw_integrated_ore_processor_2_" + material.getName())
                        .circuitMeta(2)
                        .inputItems(orePrefix, material)
                        .inputFluids(DistilledWater.getFluid(100L * crushedAmount))
                        .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                        .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                        .chancedOutput(TagPrefix.dust, byproductMaterial, crushedAmount, "1/3", 0) // 第二步副产
                        .outputItems(TagPrefix.dust, GTMaterials.Stone, crushedAmount) // 第二步副产
                        .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/3", 0) // 第三步副产
                        .chancedOutput(byproductStack2, 1400, 850) // 第四步副产
                        .duration(26 + (200 + 200 + 26) * crushedAmount)
                        .EUt(30);
                opBuilder2.save(provider);
            }

            // 3 研磨-洗矿-研磨-离心
            ItemStack byproductStack1 = ChemicalHelper.get(dust, byproductMaterial1, crushedAmount);
            GTRecipeBuilder opBuilder3 = INTEGRATED_ORE_PROCESSOR
                    .recipeBuilder("raw_integrated_ore_processor_3_" + material.getName())
                    .circuitMeta(3)
                    .inputItems(orePrefix, material)
                    .inputFluids(DistilledWater.getFluid(100L * crushedAmount))
                    .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                    .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                    .chancedOutput(TagPrefix.dust, byproductMaterial, crushedAmount, "1/3", 0) // 第二步副产
                    .outputItems(TagPrefix.dust, GTMaterials.Stone, crushedAmount) // 第二步副产
                    .chancedOutput(byproductStack1, 1400, 850) // 第三步副产
                    .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                    .duration(26 + (200 + 26 + 16) * crushedAmount)
                    .EUt(30);
            for (MaterialStack secondaryMaterial : ore.secondaryMaterials()) {
                if (secondaryMaterial.material().hasProperty(PropertyKey.DUST)) {
                    ItemStack dustStack = ChemicalHelper.getGem(secondaryMaterial);
                    opBuilder3.chancedOutput(dustStack, 500, 100);
                    break;
                }
            }
            opBuilder3.save(provider);

            // 4 研磨-洗矿-筛选-离心
            if (material.hasProperty(PropertyKey.GEM)) {
                ItemStack exquisiteStack = ChemicalHelper.get(gemExquisite, material, crushedAmount);
                ItemStack flawlessStack = ChemicalHelper.get(gemFlawless, material, crushedAmount);
                ItemStack gemStack = ChemicalHelper.get(gem, material, crushedAmount);
                if (material.hasFlag(HIGH_SIFTER_OUTPUT)) {
                    GTRecipeBuilder opBuilder4 = INTEGRATED_ORE_PROCESSOR
                            .recipeBuilder("raw_integrated_ore_processor_4_" + material.getName())
                            .circuitMeta(4)
                            .inputItems(orePrefix, material)
                            .inputFluids(DistilledWater.getFluid(100L * crushedAmount))
                            .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                            .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial, crushedAmount, "1/3", 0) // 第二步副产
                            .outputItems(TagPrefix.dust, GTMaterials.Stone, crushedAmount) // 第二步副产
                            .chancedOutput(exquisiteStack, 500, 150) // 第三步副产
                            .chancedOutput(flawlessStack, 1500, 200) // 第三步副产
                            .chancedOutput(gemStack, 5000, 1000) // 第三步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                            .duration(26 + (200 + 210 + 16) * crushedAmount)
                            .EUt(30);
                    opBuilder4.save(provider);
                } else {
                    GTRecipeBuilder opBuilder4 = INTEGRATED_ORE_PROCESSOR
                            .recipeBuilder("raw_integrated_ore_processor_4_" + material.getName())
                            .circuitMeta(4)
                            .inputItems(orePrefix, material)
                            .inputFluids(DistilledWater.getFluid(100L * crushedAmount))
                            .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                            .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial, crushedAmount, "1/3", 0) // 第二步副产
                            .outputItems(TagPrefix.dust, GTMaterials.Stone, crushedAmount) // 第二步副产
                            .chancedOutput(exquisiteStack, 300, 100) // 第三步副产
                            .chancedOutput(flawlessStack, 1000, 150) // 第三步副产
                            .chancedOutput(gemStack, 3500, 500) // 第三步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                            .duration(26 + (200 + 210 + 16) * crushedAmount)
                            .EUt(30);
                    opBuilder4.save(provider);
                }
            }
            if (property.getWashedIn().getFirst() != null) {
                Material washingByproduct = GTUtil.selectItemInList(3, material, property.getOreByProducts(),
                        Material.class);
                Pair<Material, Integer> washedInTuple = property.getWashedIn();
                // 5 研磨-浸洗-热离-研磨
                if (!crushedCentrifugedStack.isEmpty()) {
                    GTRecipeBuilder opBuilder5 = INTEGRATED_ORE_PROCESSOR
                            .recipeBuilder("raw_integrated_ore_processor_5_" + material.getName())
                            .circuitMeta(5)
                            .inputItems(orePrefix, material)
                            .inputFluids(washedInTuple.getFirst().getFluid(washedInTuple.getSecond() * crushedAmount))
                            .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                            .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                            .chancedOutput(ChemicalHelper.get(dust, washingByproduct,
                                    property.getByProductMultiplier() * crushedAmount),
                                    7000, 580) // 第二步副产
                            .chancedOutput(ChemicalHelper.get(dust, Stone, crushedAmount), 4000, 650) // 第二步副产
                            .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/3", 0) // 第三步副产
                            .chancedOutput(byproductStack2, 1400, 850) // 第四步副产
                            .duration(26 + (200 + 200 + 26) * crushedAmount)
                            .EUt(30);
                    opBuilder5.save(provider);
                }

                // 6 研磨-浸洗-研磨-离心
                GTRecipeBuilder opBuilder6 = INTEGRATED_ORE_PROCESSOR
                        .recipeBuilder("raw_integrated_ore_processor_6_" + material.getName())
                        .circuitMeta(6)
                        .inputItems(orePrefix, material)
                        .inputFluids(washedInTuple.getFirst().getFluid(washedInTuple.getSecond() * crushedAmount))
                        .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                        .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                        .chancedOutput(ChemicalHelper.get(dust, washingByproduct,
                                property.getByProductMultiplier() * crushedAmount),
                                7000, 580) // 第二步副产
                        .chancedOutput(ChemicalHelper.get(dust, Stone, crushedAmount), 4000, 650) // 第二步副产
                        .chancedOutput(byproductStack1, 1400, 850) // 第三步副产
                        .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                        .duration(26 + (200 + 26 + 16) * crushedAmount)
                        .EUt(30);
                opBuilder6.save(provider);

                // 7 研磨-浸洗-筛选-离心
                if (material.hasProperty(PropertyKey.GEM)) {
                    ItemStack exquisiteStack = ChemicalHelper.get(gemExquisite, material, crushedAmount);
                    ItemStack flawlessStack = ChemicalHelper.get(gemFlawless, material, crushedAmount);
                    ItemStack gemStack = ChemicalHelper.get(gem, material, crushedAmount);
                    if (material.hasFlag(HIGH_SIFTER_OUTPUT)) {
                        GTRecipeBuilder opBuilder7 = INTEGRATED_ORE_PROCESSOR
                                .recipeBuilder("raw_integrated_ore_processor_7_" + material.getName())
                                .circuitMeta(7)
                                .inputItems(orePrefix, material)
                                .inputFluids(washedInTuple.getFirst()
                                        .getFluid(washedInTuple.getSecond() * crushedAmount))
                                .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                                .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                                .chancedOutput(ChemicalHelper.get(dust, washingByproduct,
                                        property.getByProductMultiplier() * crushedAmount),
                                        7000, 580) // 第二步副产
                                .chancedOutput(ChemicalHelper.get(dust, Stone, crushedAmount), 4000, 650) // 第二步副产
                                .chancedOutput(exquisiteStack, 500, 150) // 第三步副产
                                .chancedOutput(flawlessStack, 1500, 200) // 第三步副产
                                .chancedOutput(gemStack, 5000, 1000) // 第三步副产
                                .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                                .duration(26 + (200 + 210 + 16) * crushedAmount)
                                .EUt(30);
                        opBuilder7.save(provider);
                    } else {
                        GTRecipeBuilder opBuilder7 = INTEGRATED_ORE_PROCESSOR
                                .recipeBuilder("raw_integrated_ore_processor_7_" + material.getName())
                                .circuitMeta(7)
                                .inputItems(orePrefix, material)
                                .inputFluids(washedInTuple.getFirst()
                                        .getFluid(washedInTuple.getSecond() * crushedAmount))
                                .outputItems(ChemicalHelper.get(dust, material, crushedAmount)) // 最终产物
                                .chancedOutput(byproductStack, 1000, 300) // 第一步副产
                                .chancedOutput(ChemicalHelper.get(dust, washingByproduct,
                                        property.getByProductMultiplier() * crushedAmount),
                                        7000, 580) // 第二步副产
                                .chancedOutput(ChemicalHelper.get(dust, Stone, crushedAmount), 4000, 650) // 第二步副产
                                .chancedOutput(exquisiteStack, 300, 100) // 第三步副产
                                .chancedOutput(flawlessStack, 1000, 150) // 第三步副产
                                .chancedOutput(gemStack, 3500, 500) // 第三步副产
                                .chancedOutput(TagPrefix.dust, byproductMaterial1, crushedAmount, "1/9", 0) // 第四步副产
                                .duration(26 + (200 + 210 + 16) * crushedAmount)
                                .EUt(30);
                        opBuilder7.save(provider);
                    }
                }
            }
        }

        // do not try to add smelting recipes for materials which require blast furnace, or don't have smelting recipes
        // at all.
        if (!ingotStack.isEmpty() && gtlcore$doesMaterialUseNormalFurnace(smeltingMaterial) && !orePrefix.isIgnored(material)) {
            float xp = Math.round(((1 + property.getOreMultiplier() * 0.33f) / 3) * 10f) / 10f;
            VanillaRecipeHelper.addSmeltingRecipe(provider,
                    "smelt_" + orePrefix.name + "_" + material.getName() + "_ore_to_ingot",
                    ChemicalHelper.getTag(orePrefix, material), GTUtil.copyAmount(ingotStack.getCount(), ingotStack),
                    xp);
            VanillaRecipeHelper.addBlastingRecipe(provider,
                    "smelt_" + orePrefix.name + "_" + material.getName() + "_ore_to_ingot",
                    ChemicalHelper.getTag(orePrefix, material), GTUtil.copyAmount(ingotStack.getCount(), ingotStack),
                    xp);
        }

        if (!ConfigHolder.INSTANCE.recipes.disableManualCompression) {
            VanillaRecipeHelper.addShapedRecipe(provider, "compress_" + material.getName() + "_to_ore_block",
                    ChemicalHelper.get(rawOreBlock, material),
                    "BBB", "BBB", "BBB",
                    'B', Objects.requireNonNull(ChemicalHelper.getTag(rawOre, material)));
            VanillaRecipeHelper.addShapelessRecipe(provider, "decompress_" + material.getName() + "_from_ore_block",
                    ChemicalHelper.get(rawOre, material, 9),
                    Objects.requireNonNull(ChemicalHelper.getTag(rawOreBlock, material)));
        }
        COMPRESSOR_RECIPES.recipeBuilder("compress_" + material.getName() + "to_ore_block")
                .inputItems(rawOre, material, 9)
                .outputItems(rawOreBlock, material)
                .duration(300).EUt(2).save(provider);

        FORGE_HAMMER_RECIPES.recipeBuilder("decompress_" + material.getName() + "to_raw_ore")
                .inputItems(rawOreBlock, material)
                .outputItems(rawOre, material, 9)
                .circuitMeta(2)
                .duration(300).EUt(2).save(provider);
    }

    @Unique
    private static boolean gtlcore$doesMaterialUseNormalFurnace(Material material) {
        return !material.hasProperty(PropertyKey.BLAST) && !material.hasFlag(MaterialFlags.NO_ORE_SMELTING);
    }
}
