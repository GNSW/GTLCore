package org.gtlcore.gtlcore.common.data.material;

import org.gtlcore.gtlcore.api.data.chemical.material.info.GTLMaterialFlags;
import org.gtlcore.gtlcore.api.data.chemical.material.info.GTLMaterialIconSet;
import org.gtlcore.gtlcore.api.item.tool.GTLToolType;
import org.gtlcore.gtlcore.common.data.GTLElements;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialIconSet;
import com.gregtechceu.gtceu.api.data.chemical.material.properties.ToolProperty;
import com.gregtechceu.gtceu.api.fluids.FluidBuilder;
import com.gregtechceu.gtceu.api.fluids.attribute.FluidAttributes;
import com.gregtechceu.gtceu.api.fluids.store.FluidStorageKeys;
import com.gregtechceu.gtceu.api.item.tool.GTToolType;

import static com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags.*;
import static com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialIconSet.*;
import static com.gregtechceu.gtceu.api.data.chemical.material.properties.BlastProperty.GasTier.*;
import static com.gregtechceu.gtceu.common.data.GTMaterials.*;
import static org.gtlcore.gtlcore.common.data.GTLMaterials.*;

public class MaterialBuilder {

    public static void init() {
        BarnardaAir = new Material.Builder(GTCEu.id("barnarda_air"))
                .gas()
                .color(0x563a24)
                .iconSet(DULL)
                .buildAndRegister();

        AlienAlgae = new Material.Builder(GTCEu.id("alien_algae"))
                .ore()
                .addOreByproducts(Paper, Agar)
                .color(0x808000)
                .iconSet(WOOD)
                .buildAndRegister();

        Bloodstone = new Material.Builder(GTCEu.id("bloodstone"))
                .ore()
                .addOreByproducts(Deepslate, Redstone)
                .color(0xd80036)
                .iconSet(QUARTZ)
                .buildAndRegister();

        PerditioCrystal = new Material.Builder(GTCEu.id("perditio_crystal"))
                .dust()
                .color(0x656565)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        EarthCrystal = new Material.Builder(GTCEu.id("earth_crystal"))
                .dust()
                .ore()
                .addOreByproducts(PerditioCrystal)
                .color(0x00f100)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        IgnisCrystal = new Material.Builder(GTCEu.id("ignis_crystal"))
                .dust()
                .ore()
                .addOreByproducts(PerditioCrystal)
                .color(0xd90000)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        InfusedGold = new Material.Builder(GTCEu.id("infused_gold"))
                .dust()
                .ore()
                .color(0xc99614)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("Au?");

        Thaumium = new Material.Builder(GTCEu.id("thaumium"))
                .dust()
                .components(InfusedGold, 1)
                .color(0x54537e)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AstralSilver = new Material.Builder(GTCEu.id("astral_silver"))
                .dust()
                .fluid()
                .components(Silver, 2, Thaumium, 1)
                .color(0xd9d9f1)
                .iconSet(BRIGHT)
                .buildAndRegister();

        HighEnergyMixture = new Material.Builder(GTCEu.id("highenergymixture"))
                .dust()
                .color(0xdbd69c)
                .iconSet(SAND)
                .buildAndRegister();

        Luminessence = new Material.Builder(GTCEu.id("luminessence"))
                .dust()
                .components(HighEnergyMixture, 1, PhosphoricAcid, 1)
                .color(0x838914)
                .iconSet(DULL)
                .buildAndRegister();

        Sunnarium = new Material.Builder(GTCEu.id("sunnarium"))
                .ingot()
                .fluid()
                .color(0xfcfc00)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        PulsatingAlloy = new Material.Builder(GTCEu.id("pulsating_alloy"))
                .ingot()
                .color(0x6ae26e)
                .iconSet(SHINY)
                .components(Iron, 1)
                .cableProperties(8, 1, 0, true)
                .buildAndRegister();

        ConductiveAlloy = new Material.Builder(GTCEu.id("conductive_alloy"))
                .ingot()
                .color(0xf7b29b)
                .iconSet(MAGNETIC)
                .components(Iron, 1, Redstone, 1)
                .cableProperties(32, 1, 0, true)
                .buildAndRegister();

        EnergeticAlloy = new Material.Builder(GTCEu.id("energetic_alloy"))
                .ingot()
                .fluid()
                .color(0xffb545)
                .iconSet(SHINY)
                .blastTemp(1650, LOW, GTValues.VA[GTValues.MV], 700)
                .components(Gold, 2, Redstone, 1, Glowstone, 1)
                .cableProperties(128, 1, 0, true)
                .buildAndRegister();

        VibrantAlloy = new Material.Builder(GTCEu.id("vibrant_alloy"))
                .ingot()
                .fluid()
                .color(0xa4ff70)
                .iconSet(SHINY)
                .blastTemp(2375, LOW, GTValues.VA[GTValues.HV], 1300)
                .components(EnergeticAlloy, 1, EnderPearl, 1)
                .cableProperties(512, 1, 0, true)
                .buildAndRegister();

        UuAmplifier = new Material.Builder(GTCEu.id("uu_amplifier"))
                .fluid()
                .color(0xaa2b9f)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        Celestine = new Material.Builder(GTCEu.id("celestine"))
                .ore()
                .color(0x3c4899)
                .components(Strontium, 1, Sulfur, 1, Oxygen, 4)
                .iconSet(EMERALD)
                .buildAndRegister();

        Zircon = new Material.Builder(GTCEu.id("zircon"))
                .ore()
                .color(0xde953c)
                .iconSet(EMERALD)
                .buildAndRegister().setFormula("ZrSiO₄");

        Jasper = new Material.Builder(GTCEu.id("jasper"))
                .gem()
                .ore()
                .addOreByproducts(Talc, Boron)
                .color(0xc85050)
                .iconSet(EMERALD)
                .buildAndRegister().setFormula("?");

        BismuthTellurite = new Material.Builder(GTCEu.id("bismuth_tellurite"))
                .dust()
                .components(Bismuth, 2, Tellurium, 3)
                .color(0x004222)
                .iconSet(BRIGHT)
                .buildAndRegister();

        Prasiolite = new Material.Builder(GTCEu.id("prasiolite"))
                .dust()
                .components(Silicon, 5, Oxygen, 10, Iron, 1)
                .color(0x9eB749)
                .iconSet(BRIGHT)
                .buildAndRegister();

        CubicZirconia = new Material.Builder(GTCEu.id("cubic_zirconia"))
                .gem()
                .color(0xf3d5d7)
                .components(Zirconium, 1, Oxygen, 2)
                .iconSet(EMERALD)
                .flags(GENERATE_PLATE)
                .buildAndRegister();

        MagnetoResonatic = new Material.Builder(GTCEu.id("magneto_resonatic"))
                .gem()
                .color(0xff97ff)
                .components(Prasiolite, 3, BismuthTellurite, 6, CubicZirconia, 1, SteelMagnetic, 1)
                .iconSet(MAGNETIC)
                .buildAndRegister();

        Adamantium = new Material.Builder(GTCEu.id("adamantium"))
                .ingot()
                .fluid()
                .plasma()
                .blastTemp(17700, HIGHER)
                .element(GTLElements.ADAMANTIUM)
                .color(0xefbe35)
                .iconSet(METALLIC)
                .flags(GENERATE_ROTOR, GENERATE_FRAME)
                .buildAndRegister();

        Quantanium = new Material.Builder(GTCEu.id("quantanium"))
                .ingot()
                .fluid()
                .blastTemp(12500, HIGHER)
                .element(GTLElements.QUANTANIUM)
                .color(0x0dff02)
                .iconSet(METALLIC)
                .flags(GENERATE_ROTOR, GENERATE_SMALL_GEAR, GENERATE_FRAME)
                .buildAndRegister();

        Vibranium = new Material.Builder(GTCEu.id("vibranium"))
                .ingot()
                .fluid()
                .plasma()
                .ore()
                .addOreByproducts(Plutonium239, Plutonium241)
                .blastTemp(18500, HIGHER)
                .element(GTLElements.VIBRANIUM)
                .color(0xff0000)
                .iconSet(METALLIC)
                .toolStats(ToolProperty.Builder.of(1.0F, 1.0F, 100, 10, GTToolType.DRILL_LV).build())
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_ROTOR, GENERATE_FRAME)
                .buildAndRegister();

        Indalloy140 = new Material.Builder(GTCEu.id("indalloy_140"))
                .ingot()
                .fluid()
                .color(0x6a5acd)
                .blastTemp(2600, LOW, GTValues.VA[GTValues.EV])
                .components(Bismuth, 47, Lead, 25, Tin, 13, Cadmium, 10, Indium, 5)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ArtheriumSn = new Material.Builder(GTCEu.id("artherium_sn"))
                .ingot()
                .fluid()
                .color(0x551a8b)
                .blastTemp(9800, HIGHER, GTValues.VA[GTValues.IV])
                .components(Tin, 12, Actinium, 7, EnrichedNaquadahTriniumEuropiumDuranide, 5, Caesium, 4, Osmiridium, 3)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Tairitsu = new Material.Builder(GTCEu.id("tairitsu"))
                .ingot()
                .fluid()
                .color(0x1c1c1c)
                .blastTemp(12100, HIGHER, GTValues.VA[GTValues.ZPM])
                .components(Tungsten, 8, Naquadria, 7, Trinium, 4, Carbon, 4, Vanadium, 3,
                        Plutonium239, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Draconium = new Material.Builder(GTCEu.id("draconium"))
                .ingot()
                .fluid()
                .blastTemp(19200)
                .element(GTLElements.DRACONIUM)
                .color(0xa300cc)
                .iconSet(RADIOACTIVE)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_ROTOR, GENERATE_FRAME, NO_SMELTING)
                .buildAndRegister();

        Chaos = new Material.Builder(GTCEu.id("chaos"))
                .ingot()
                .liquid(new FluidBuilder().temperature(1000000).customStill())
                .plasma()
                .blastTemp(28000, HIGHEST)
                .element(GTLElements.CHAOS)
                .iconSet(DULL)
                .color(0x000000)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        Cosmic = new Material.Builder(GTCEu.id("cosmic"))
                .ingot()
                .color(0x2d3e5e)
                .iconSet(new MaterialIconSet("cosmic"))
                .flags(GENERATE_FINE_WIRE, GENERATE_LONG_ROD)
                .buildAndRegister();

        Hypogen = new Material.Builder(GTCEu.id("hypogen"))
                .ingot()
                .fluid()
                .color(0xda916b)
                .secondaryColor(0x8f993b)
                .blastTemp(34000, HIGHEST)
                .element(GTLElements.HYPogen)
                .iconSet(RADIOACTIVE)
                .flags(GENERATE_PLATE)
                .cableProperties(Integer.MAX_VALUE, 32768, 0, true)
                .buildAndRegister();

        Shirabon = new Material.Builder(GTCEu.id("shirabon"))
                .ingot()
                .fluid()
                .color(0xc61361)
                .blastTemp(64000, HIGHEST, GTValues.VA[GTValues.OpV], 1200)
                .element(GTLElements.SHIRABON)
                .flags(GENERATE_FINE_WIRE)
                .iconSet(METALLIC)
                .buildAndRegister();

        Mithril = new Material.Builder(GTCEu.id("mithril"))
                .ingot()
                .fluid()
                .plasma()
                .ore()
                .addOreByproducts(Actinium, Technetium)
                .blastTemp(14800, HIGHER)
                .element(GTLElements.MITHRIL)
                .color(0x4da6ff)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, GENERATE_SPRING, GENERATE_FRAME, GENERATE_SPRING_SMALL)
                .cableProperties(GTValues.V[GTValues.UEV], 2, 64)
                .buildAndRegister();

        Taranium = new Material.Builder(GTCEu.id("taranium"))
                .ingot()
                .fluid()
                .blastTemp(16200, HIGHEST, GTValues.VA[GTValues.UIV], 1440)
                .element(GTLElements.TARANIUM)
                .color(0x000033)
                .iconSet(RADIOACTIVE)
                .flags(GENERATE_SPRING, GENERATE_SPRING_SMALL)
                .cableProperties(GTValues.V[GTValues.UXV], 2, 64)
                .buildAndRegister();

        Crystalmatrix = new Material.Builder(GTCEu.id("crystalmatrix"))
                .ingot()
                .fluid()
                .plasma()
                .blastTemp(19600, HIGHEST)
                .element(GTLElements.CRYSTALMATRIX)
                .color(0x33ffff)
                .iconSet(RADIOACTIVE)
                .flags(GENERATE_SPRING, GENERATE_SPRING_SMALL)
                .cableProperties(GTValues.V[GTValues.OpV], 2, 128)
                .buildAndRegister();

        CosmicNeutronium = new Material.Builder(GTCEu.id("cosmicneutronium"))
                .ingot()
                .liquid(new FluidBuilder().temperature(1000000).customStill())
                .blastTemp(24800, HIGHEST)
                .element(GTLElements.COSMICNEUTRONIUM)
                .color(0x000d1a)
                .iconSet(new MaterialIconSet("cosmicneutronium", BRIGHT))
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_SPRING, GENERATE_FINE_WIRE,
                        GENERATE_SPRING_SMALL)
                .cableProperties(Integer.MAX_VALUE, 2, 128)
                .buildAndRegister();

        Echoite = new Material.Builder(GTCEu.id("echoite"))
                .ingot()
                .fluid()
                .plasma()
                .blastTemp(17300, HIGHER)
                .element(GTLElements.ECHOITE)
                .color(0x26734d)
                .iconSet(METALLIC)
                .flags(GENERATE_ROD, GENERATE_FINE_WIRE)
                .cableProperties(GTValues.V[GTValues.UIV], 32, 0, true)
                .toolStats(ToolProperty.Builder.of(6.0F, 100.0F, 64, 6, GTLToolType.VAJRA).magnetic()
                        .unbreakable().build())
                .buildAndRegister();

        Legendarium = new Material.Builder(GTCEu.id("legendarium"))
                .ingot()
                .fluid()
                .plasma()
                .blastTemp(21400, HIGHEST)
                .element(GTLElements.LEGENDARIUM)
                .color(0x00ffff)
                .iconSet(RADIOACTIVE)
                .flags(GENERATE_FINE_WIRE)
                .cableProperties(GTValues.V[GTValues.UXV], 64, 0, true)
                .buildAndRegister();

        DraconiumAwakened = new Material.Builder(GTCEu.id("draconiumawakened"))
                .ingot()
                .fluid()
                .plasma()
                .blastTemp(22600, HIGHEST)
                .element(GTLElements.DRACONIUMAWAKENED)
                .color(0xcc6600)
                .iconSet(RADIOACTIVE)
                .flags(GENERATE_FINE_WIRE)
                .cableProperties(GTValues.V[GTValues.OpV], 64, 0, true)
                .buildAndRegister();

        Adamantine = new Material.Builder(GTCEu.id("adamantine"))
                .ingot()
                .fluid()
                .blastTemp(14400, HIGHER)
                .element(GTLElements.ADAMANTINE)
                .color(0xe6e600)
                .iconSet(METALLIC)
                .flags(GENERATE_FINE_WIRE)
                .cableProperties(GTValues.V[GTValues.UIV], 4, 128)
                .buildAndRegister();

        Starmetal = new Material.Builder(GTCEu.id("starmetal"))
                .ingot()
                .fluid()
                .plasma()
                .addOreByproducts(Sapphire, Polonium)
                .blastTemp(21800, HIGHEST)
                .element(GTLElements.STARMETAL)
                .color(0x0000e6)
                .iconSet(METALLIC)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_FINE_WIRE)
                .cableProperties(GTValues.V[GTValues.OpV], 4, 256)
                .buildAndRegister();

        Orichalcum = new Material.Builder(GTCEu.id("orichalcum"))
                .ingot()
                .fluid()
                .plasma()
                .ore()
                .blastTemp(15300, HIGHER)
                .element(GTLElements.ORICHALCUM)
                .color(0xff78c9)
                .iconSet(METALLIC)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_ROUND, GENERATE_ROTOR, GENERATE_GEAR,
                        GENERATE_SMALL_GEAR, GENERATE_LONG_ROD)
                .buildAndRegister();

        Infuscolium = new Material.Builder(GTCEu.id("infuscolium"))
                .ingot()
                .fluid()
                .plasma()
                .blastTemp(17500, HIGHER)
                .element(GTLElements.INFUSCOLIUM)
                .color(0xff77ff)
                .iconSet(RADIOACTIVE)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_ROUND, GENERATE_ROTOR, GENERATE_GEAR,
                        GENERATE_SMALL_GEAR, GENERATE_LONG_ROD)
                .buildAndRegister();

        Enderium = new Material.Builder(GTCEu.id("enderium"))
                .ingot()
                .fluid()
                .plasma()
                .ore()
                .fluidPipeProperties(100000, 100000, true, true, true, true)
                .addOreByproducts(Endstone, EnderPearl)
                .blastTemp(16800, HIGHER)
                .element(GTLElements.ENDERIUM)
                .color(0x75ede2)
                .iconSet(METALLIC)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_FINE_WIRE)
                .buildAndRegister();

        Eternity = new Material.Builder(GTCEu.id("eternity"))
                .ingot()
                .liquid(new FluidBuilder().customStill())
                .blastTemp(36000, null, GTValues.VA[GTValues.MAX], 3600)
                .element(GTLElements.ETERNITY)
                .iconSet(new MaterialIconSet("eternity"))
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_FOIL, GENERATE_FRAME)
                .buildAndRegister();

        Magmatter = new Material.Builder(GTCEu.id("magmatter"))
                .ingot()
                .liquid(new FluidBuilder().customStill())
                .element(GTLElements.MAGMATTER)
                .iconSet(new MaterialIconSet("magmatter"))
                .flags(GENERATE_LONG_ROD, NO_UNIFICATION)
                .buildAndRegister();

        DegenerateRhenium = new Material.Builder(GTCEu.id("degenerate_rhenium"))
                .dust()
                .plasma()
                .fluid()
                .color(0x4646ff)
                .element(GTLElements.DEGENERATE_REHENIUM)
                .iconSet(RADIOACTIVE)
                .flags(GENERATE_PLATE, NO_UNIFICATION)
                .buildAndRegister();

        HeavyQuarkDegenerateMatter = new Material.Builder(
                GTCEu.id("heavy_quark_degenerate_matter"))
                .ingot()
                .fluid()
                .plasma()
                .fluidPipeProperties(1000000, 1000000, true, true, true, true)
                .element(GTLElements.HEAVY_QUARK_DEGENERATE_MATTER)
                .blastTemp(178000, HIGHER)
                .color(0x52a733)
                .iconSet(BRIGHT)
                .flags(GENERATE_PLATE, GENERATE_FINE_WIRE)
                .buildAndRegister();

        MetastableHassium = new Material.Builder(GTCEu.id("metastable_hassium"))
                .plasma()
                .fluid()
                .components(Hassium, 1)
                .color(0x78766f)
                .iconSet(RADIOACTIVE)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Inconel625 = new Material.Builder(GTCEu.id("inconel_625"))
                .ingot()
                .fluid()
                .color(0x00cd66)
                .blastTemp(4850, HIGH, GTValues.VA[GTValues.IV])
                .components(Nickel, 8, Chromium, 6, Molybdenum, 4, Niobium, 4, Titanium, 3, Iron, 2,
                        Aluminium, 2)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION, GENERATE_PLATE, GENERATE_GEAR, GENERATE_SMALL_GEAR,
                        GENERATE_BOLT_SCREW)
                .buildAndRegister();

        HastelloyN75 = new Material.Builder(GTCEu.id("hastelloy_n_75"))
                .ingot()
                .fluid()
                .color(0x8b6914)
                .blastTemp(4550, HIGH, GTValues.VA[GTValues.EV])
                .components(Nickel, 15, Molybdenum, 9, Chromium, 4, Titanium, 2, Erbium, 2)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION, GENERATE_BOLT_SCREW, GENERATE_GEAR, GENERATE_SMALL_GEAR,
                        GENERATE_PLATE)
                .buildAndRegister();

        MetastableOganesson = new Material.Builder(GTCEu.id("metastable_oganesson"))
                .fluid()
                .color(0x8b000e)
                .components(Oganesson, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        QuantumChromodynamicallyConfinedMatter = new Material.Builder(
                GTCEu.id("quantumchromodynamically_confined_matter"))
                .ingot()
                .fluid()
                .plasma()
                .element(GTLElements.QUANTUMCHROMODYNAMICALLY_CONFINED_MATTER)
                .blastTemp(13100, HIGHER)
                .color(0xd08c38)
                .iconSet(RADIOACTIVE)
                .flags(GENERATE_FRAME, GENERATE_PLATE)
                .buildAndRegister();

        TranscendentMetal = new Material.Builder(GTCEu.id("transcendentmetal"))
                .ingot()
                .fluid()
                .element(GTLElements.TRANSCENDENTMETAL)
                .color(0xffffff)
                .iconSet(GTLMaterialIconSet.CUSTOM_TRANSCENDENT_MENTAL)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_ROUND, GENERATE_ROTOR, GENERATE_GEAR,
                        GENERATE_SMALL_GEAR, GENERATE_LONG_ROD)
                .buildAndRegister();

        Uruium = new Material.Builder(GTCEu.id("uruium"))
                .ingot()
                .fluid()
                .ore()
                .addOreByproducts(Europium)
                .blastTemp(14600, HIGHER, GTValues.VA[GTValues.UIV], 1200)
                .element(GTLElements.URUIUM)
                .color(0x87ceeb)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM)
                .iconSet(METALLIC)
                .cableProperties(Integer.MAX_VALUE, 16, 536870912)
                .buildAndRegister();

        MagnetohydrodynamicallyConstrainedStarMatter = new Material.Builder(GTCEu.id("magnetohydrodynamicallyconstrainedstarmatter"))
                .ingot()
                .liquid(new FluidBuilder().temperature(100).customStill())
                .element(GTLElements.RAW_STAR_MATTER)
                .iconSet(new MaterialIconSet("magnetohydrodynamicallyconstrainedstarmatter"))
                .flags(GENERATE_FRAME, GENERATE_FOIL, NO_UNIFICATION)
                .buildAndRegister();

        WhiteDwarfMatter = new Material.Builder(GTCEu.id("white_dwarf_mtter"))
                .ingot()
                .fluid()
                .element(GTLElements.STAR_MATTER)
                .color(0xffffff)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_FINE_WIRE)
                .iconSet(BRIGHT)
                .buildAndRegister();

        BlackDwarfMatter = new Material.Builder(GTCEu.id("black_dwarf_mtter"))
                .ingot()
                .fluid()
                .element(GTLElements.STAR_MATTER)
                .color(0x000000)
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, GENERATE_FINE_WIRE)
                .iconSet(BRIGHT)
                .buildAndRegister();

        AstralTitanium = new Material.Builder(GTCEu.id("astraltitanium"))
                .ingot()
                .fluid()
                .plasma()
                .element(GTLElements.ASTRALTITANIUM)
                .color(0xf6cbf6)
                .flags(GENERATE_GEAR)
                .iconSet(BRIGHT)
                .buildAndRegister();

        CelestialTungsten = new Material.Builder(GTCEu.id("celestialtungsten"))
                .ingot()
                .fluid()
                .plasma()
                .element(GTLElements.CELESTIALTUNGSTEN)
                .color(0x303030)
                .flags(GENERATE_GEAR)
                .iconSet(BRIGHT)
                .buildAndRegister();

        Enderite = new Material.Builder(GTCEu.id("enderite"))
                .ingot()
                .fluid()
                .blastTemp(14400, HIGHER, GTValues.VA[GTValues.UEV], 600)
                .components(Enderium, 3, EnderPearl, 2, ManganesePhosphide, 1, MagnesiumDiboride, 1,
                        MercuryBariumCalciumCuprate, 1, UraniumTriplatinum, 1,
                        SamariumIronArsenicOxide, 1, IndiumTinBariumTitaniumCuprate, 1)
                .color(0x006699)
                .iconSet(METALLIC)
                .flags(GENERATE_FINE_WIRE, DISABLE_DECOMPOSITION)
                .cableProperties(GTValues.V[GTValues.UEV], 32, 0, true)
                .buildAndRegister();

        NaquadriaticTaranium = new Material.Builder(GTCEu.id("naquadriatictaranium"))
                .ingot()
                .fluid()
                .blastTemp(16200, HIGHEST, GTValues.VA[GTValues.UXV], 1400)
                .components(Naquadria, 1, Taranium, 1)
                .color(0x000d1a)
                .iconSet(RADIOACTIVE)
                .flags(GENERATE_ROD, GENERATE_FINE_WIRE, DISABLE_DECOMPOSITION)
                .cableProperties(GTValues.V[GTValues.UXV], 4, 128)
                .buildAndRegister();

        AbyssalAlloy = new Material.Builder(GTCEu.id("abyssalalloy"))
                .ingot()
                .fluid()
                .blastTemp(10800, HIGHER, GTValues.VA[GTValues.UV], 1800)
                .components(StainlessSteel, 5, TungstenCarbide, 5, Nichrome, 5, Bronze, 5,
                        IncoloyMA956, 5, Iodine, 1, Germanium, 1, Radon, 1, Hafnium, 1,
                        BarnardaAir, 1)
                .color(0x9e706a)
                .iconSet(METALLIC)
                .flags(GENERATE_FINE_WIRE, DISABLE_DECOMPOSITION)
                .cableProperties(GTValues.V[GTValues.UHV], 4, 64)
                .buildAndRegister();

        TitanSteel = new Material.Builder(GTCEu.id("titansteel"))
                .ingot()
                .fluid()
                .blastTemp(12600, HIGHER, GTValues.VA[GTValues.UHV], 1200)
                .components(TitaniumTungstenCarbide, 4, Plutonium241, 1, Einsteinium, 2, Rhenium, 1, Erbium, 1, Jasper, 3, UuAmplifier, 1)
                .color(0xe60000)
                .iconSet(METALLIC)
                .flags(GENERATE_FINE_WIRE, DISABLE_DECOMPOSITION)
                .cableProperties(GTValues.V[GTValues.UEV], 4, 64)
                .buildAndRegister();

        Highurabilityompoundteel = new Material.Builder(GTCEu.id("highurabilityompoundteel"))
                .ingot()
                .fluid()
                .blastTemp(12600, HIGHER, GTValues.VA[GTValues.UV], 1600)
                .components(TungstenSteel, 12, HSSS, 9, HSSG, 6, Ruridit, 3, MagnetoResonatic, 2, Plutonium239, 1)
                .color(0x2d3c2d)
                .iconSet(METALLIC)
                .flags(GENERATE_BOLT_SCREW, GENERATE_PLATE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GermaniumTungstenNitride = new Material.Builder(GTCEu.id("germaniumtungstennitride"))
                .ingot()
                .fluid()
                .blastTemp(8200, HIGHER, GTValues.VA[GTValues.LuV], 800)
                .components(Germanium, 3, Tungsten, 3, Nitrogen, 10)
                .color(0x7070a2)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        BlackTitanium = new Material.Builder(GTCEu.id("black_titanium"))
                .ingot()
                .fluid()
                .blastTemp(18900, HIGHEST, GTValues.VA[GTValues.UXV], 600)
                .components(Titanium, 26, Lanthanum, 6, Tungsten, 4, Cobalt, 3, Manganese, 2,
                        Phosphorus, 2, Palladium, 2, Niobium, 1, Argon, 5)
                .color(0x3d0021)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, GENERATE_FRAME, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TriniumTitanium = new Material.Builder(GTCEu.id("trinium_titanium"))
                .ingot()
                .fluid()
                .blastTemp(14400, HIGHER, GTValues.VA[GTValues.UIV], 800)
                .components(Trinium, 2, Titanium, 1)
                .color(0x856f91)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, GENERATE_FINE_WIRE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Cinobite = new Material.Builder(GTCEu.id("cinobite"))
                .ingot()
                .fluid()
                .blastTemp(15400, HIGHER, GTValues.VA[GTValues.UIV], 1300)
                .components(Zeron100, 8, Naquadria, 4, Terbium, 3, Aluminium, 2, Mercury, 1, Tin, 1, Titanium, 6, Osmiridium, 1)
                .color(0x000000)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, GENERATE_FINE_WIRE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HastelloyX78 = new Material.Builder(GTCEu.id("hastelloyx_78"))
                .ingot()
                .fluid()
                .blastTemp(14400, HIGHER, GTValues.VA[GTValues.UEV], 1200)
                .components(NaquadahAlloy, 10, Rhenium, 5, Naquadria, 4, Tritanium, 4, TungstenCarbide, 1,
                        Promethium, 1, Mendelevium, 1, Praseodymium, 1)
                .color(0x3c5b7f)
                .iconSet(METALLIC)
                .flags(GENERATE_ROUND, GENERATE_ROTOR, GENERATE_GEAR, GENERATE_SMALL_GEAR, GENERATE_LONG_ROD,
                        GENERATE_FINE_WIRE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HastelloyK243 = new Material.Builder(GTCEu.id("hastelloyk_243"))
                .ingot()
                .fluid()
                .blastTemp(17200, HIGHEST, GTValues.VA[GTValues.UXV], 1500)
                .components(HastelloyX78, 5, NiobiumNitride, 2, Tritanium, 4, TungstenCarbide, 4,
                        Promethium, 1, Mendelevium, 1, Praseodymium, 1, Holmium, 1)
                .color(0x92d959)
                .iconSet(METALLIC)
                .flags(GENERATE_ROUND, GENERATE_ROTOR, GENERATE_GEAR, GENERATE_SMALL_GEAR, GENERATE_LONG_ROD,
                        DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Vibramantium = new Material.Builder(GTCEu.id("vibramantium"))
                .ingot()
                .fluid()
                .blastTemp(18800, HIGHER, GTValues.VA[GTValues.UXV], 1800)
                .components(Vibranium, 1, Adamantium, 3)
                .color(0xff009c)
                .iconSet(METALLIC)
                .flags(GENERATE_ROUND, GENERATE_ROTOR, GENERATE_GEAR, GENERATE_SMALL_GEAR, GENERATE_LONG_ROD)
                .buildAndRegister();

        EglinSteel = new Material.Builder(GTCEu.id("eglin_steel"))
                .ingot()
                .fluid()
                .blastTemp(1048, LOW)
                .components(Iron, 4, Kanthal, 1, Invar, 5, Sulfur, 1, Silicon, 1, Carbon, 1)
                .color(0x4e270b)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, GENERATE_GEAR, DECOMPOSITION_BY_CENTRIFUGING)
                .buildAndRegister();

        Inconel792 = new Material.Builder(GTCEu.id("inconel_792"))
                .ingot()
                .fluid()
                .blastTemp(5200, HIGH)
                .components(Nickel, 2, Niobium, 1, Aluminium, 2, Nichrome, 1)
                .color(0x44974a)
                .iconSet(METALLIC)
                .flags(GENERATE_BOLT_SCREW, GENERATE_FRAME, GENERATE_GEAR, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Pikyonium = new Material.Builder(GTCEu.id("pikyonium"))
                .ingot()
                .fluid()
                .blastTemp(10400, HIGHER, GTValues.VA[GTValues.ZPM], 800)
                .components(Inconel792, 8, EglinSteel, 5, NaquadahEnriched, 4, Cerium, 3,
                        Antimony, 2, Platinum, 2, Ytterbium, 1, TungstenSteel, 4)
                .color(0x244780)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HastelloyN = new Material.Builder(GTCEu.id("hastelloy_n"))
                .ingot()
                .fluid()
                .blastTemp(4350, HIGHER, 1920)
                .components(Iridium, 2, Molybdenum, 4, Chromium, 2, Titanium, 2, Nickel, 15)
                .color(0xaaaaaa)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, GENERATE_FRAME, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AluminiumBronze = new Material.Builder(GTCEu.id("aluminium_bronze"))
                .ingot()
                .fluid()
                .blastTemp(1200, LOW)
                .components(Aluminium, 1, Bronze, 6)
                .color(0xffdead)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, GENERATE_FRAME)
                .buildAndRegister();

        Lafium = new Material.Builder(GTCEu.id("lafium"))
                .ingot()
                .fluid()
                .blastTemp(9865, HIGHER, 1920)
                .components(HastelloyN, 8, Naquadah, 4, Samarium, 2, Tungsten, 4,
                        Aluminium, 6, Nickel, 2, Carbon, 2)
                .color(0x235497)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Grisium = new Material.Builder(GTCEu.id("grisium"))
                .ingot()
                .fluid()
                .blastTemp(4850, HIGH)
                .components(Titanium, 9, Carbon, 9, Potassium, 9, Lithium, 9, Sulfur, 9,
                        Hydrogen, 5)
                .color(0x355d6a)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE)
                .buildAndRegister();

        Stellite = new Material.Builder(GTCEu.id("stellite"))
                .ingot()
                .fluid()
                .blastTemp(4310, HIGH, 1920)
                .components(Cobalt, 9, Chromium, 9, Manganese, 5, Titanium, 2)
                .color(0x888192)
                .iconSet(METALLIC)
                .flags(GENERATE_GEAR)
                .buildAndRegister();

        SiliconCarbide = new Material.Builder(GTCEu.id("silicon_carbide"))
                .ingot()
                .fluid()
                .blastTemp(4800, HIGH, 480)
                .components(Silicon, 1, Carbon, 1)
                .color(0x34adb6)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE)
                .buildAndRegister();

        Quantum = new Material.Builder(GTCEu.id("quantum"))
                .ingot()
                .fluid()
                .blastTemp(11400, HIGHER, 1920)
                .components(Stellite, 15, Quantanium, 3, Jasper, 2, Gallium, 5, Americium, 5,
                        Palladium, 5, Germanium, 5, SiliconCarbide, 5)
                .color(0x0d0d0d)
                .iconSet(METALLIC)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        FluxedElectrum = new Material.Builder(GTCEu.id("fluxed_electrum"))
                .ingot()
                .fluid()
                .blastTemp(10400, HIGHER, 1920)
                .components(SolderingAlloy, 1, InfusedGold, 1, Naquadah, 1, AstralSilver, 1,
                        RedSteel, 1, BlueSteel, 1, SterlingSilver, 1, RoseGold, 1)
                .color(0xf8f8d6)
                .iconSet(METALLIC)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        Tanmolyium = new Material.Builder(GTCEu.id("tanmolyium"))
                .ingot()
                .fluid()
                .blastTemp(4300, HIGH, 1920)
                .components(Titanium, 5, Molybdenum, 5, Vanadium, 2, Chromium, 3, Aluminium, 1)
                .color(0x97249a)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE)
                .buildAndRegister();

        Dalisenite = new Material.Builder(GTCEu.id("dalisenite"))
                .ingot()
                .fluid()
                .blastTemp(12400, HIGHER, 7680)
                .components(Erbium, 3, Tungsten, 10, Naquadah, 1, NiobiumTitanium, 9, Quantanium, 7,
                        RhodiumPlatedPalladium, 14, Tanmolyium, 1)
                .color(0xa4ac11)
                .iconSet(METALLIC)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        ArceusAlloy2B = new Material.Builder(GTCEu.id("arceusalloy2b"))
                .ingot()
                .fluid()
                .blastTemp(14200, HIGHER, 122880)
                .components(Trinium, 3, MaragingSteel300, 4, Orichalcum, 1, NetherStar, 2,
                        TungstenSteel, 2, Osmiridium, 1, Strontium, 2)
                .color(0x79740e)
                .iconSet(METALLIC)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        TitanPrecisionSteel = new Material.Builder(GTCEu.id("titan_precision_steel"))
                .ingot()
                .fluid()
                .blastTemp(16000, HIGHER, 491520)
                .components(TitanSteel, 3, Ytterbium, 1, PerditioCrystal, 1, EarthCrystal, 1,
                        IgnisCrystal, 1)
                .color(0x595137)
                .iconSet(METALLIC)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        Lumiium = new Material.Builder(GTCEu.id("lumiium"))
                .ingot()
                .fluid()
                .blastTemp(5400, HIGH)
                .components(SterlingSilver, 2, TinAlloy, 4, Luminessence, 2)
                .color(0xd9e222)
                .iconSet(METALLIC)
                .buildAndRegister();

        Hikarium = new Material.Builder(GTCEu.id("hikarium"))
                .ingot()
                .fluid()
                .blastTemp(17800, HIGHER, GTValues.VA[GTValues.UHV])
                .components(Lumiium, 18, Silver, 8, Sunnarium, 4)
                .color(0xe2bede)
                .iconSet(BRIGHT)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        SuperheavyLAlloy = new Material.Builder(GTCEu.id("superheavy_l_alloy"))
                .ingot()
                .fluid()
                .blastTemp(10600, HIGHER)
                .components(Rutherfordium, 1, Dubnium, 1, Seaborgium, 1, Bohrium, 1, Hassium, 1,
                        Meitnerium, 1, Darmstadtium, 1, Roentgenium, 1)
                .color(0x2b45df)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SuperheavyHAlloy = new Material.Builder(GTCEu.id("superheavy_h_alloy"))
                .ingot()
                .fluid()
                .blastTemp(10600, HIGHER)
                .components(Copernicium, 1, Nihonium, 1, Flerovium, 1, Moscovium, 1, Livermorium, 1,
                        Tennessine, 1, Oganesson, 1)
                .color(0xe84b36)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ZirconiumCarbide = new Material.Builder(GTCEu.id("zirconium_carbide"))
                .ingot()
                .fluid()
                .blastTemp(6800, HIGH, 1920, 1200)
                .components(Zirconium, 1, Carbon, 1)
                .color(0xd2bfaa)
                .iconSet(METALLIC)
                .flags(GENERATE_PLATE)
                .buildAndRegister();

        MarM200Steel = new Material.Builder(GTCEu.id("mar_m_200_steel"))
                .ingot()
                .fluid()
                .blastTemp(4600, HIGH, GTValues.VA[GTValues.IV], 100)
                .components(Niobium, 2, Chromium, 9, Aluminium, 5, Titanium, 2, Cobalt, 10,
                        Tungsten, 13, Nickel, 18)
                .color(0x515151)
                .iconSet(METALLIC)
                .flags(GENERATE_GEAR, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Tantalloy61 = new Material.Builder(GTCEu.id("tantalloy_61"))
                .ingot()
                .fluid()
                .blastTemp(6900, HIGHER, GTValues.VA[GTValues.LuV])
                .components(Tantalum, 13, Tungsten, 12, Titanium, 6, Yttrium, 4)
                .color(0x363636)
                .iconSet(METALLIC)
                .flags(GENERATE_BOLT_SCREW, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ReactorSteel = new Material.Builder(GTCEu.id("reactor_steel"))
                .ingot()
                .fluid()
                .blastTemp(3800, HIGH, GTValues.VA[GTValues.HV], 700)
                .components(Iron, 15, Niobium, 1, Vanadium, 4, Carbon, 2)
                .color(0xb4b3b0)
                .iconSet(SHINY)
                .flags(GENERATE_DENSE, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Lanthanoids1 = new Material.Builder(GTCEu.id("lanthanoids_1"))
                .dust()
                .color(0xef1133)
                .components(Lanthanum, 1, Cerium, 1, Praseodymium, 1, Neodymium, 1, Promethium, 1,
                        Samarium, 1, Europium, 1, Gadolinium, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Lanthanoids2 = new Material.Builder(GTCEu.id("lanthanoids_2"))
                .dust()
                .color(0xef1133)
                .components(Terbium, 1, Dysprosium, 1, Holmium, 1, Erbium, 1, Thulium, 1,
                        Ytterbium, 1, Lutetium, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        RAREEARTH = new Material.Builder(GTCEu.id("rareearth"))
                .fluid()
                .ingot()
                .color(0xa52a2a)
                .blastTemp(12400, HIGHER, GTValues.VA[GTValues.UHV], 800)
                .components(Scandium, 1, Yttrium, 1, Lanthanoids1, 1, Lanthanoids2, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Actinoids1 = new Material.Builder(GTCEu.id("actinoids_1"))
                .dust()
                .color(0x80eb33)
                .components(Actinium, 1, Thorium, 1, Protactinium, 1, Uranium238, 1, Neptunium, 1,
                        Plutonium239, 1, Americium, 1, Curium, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Actinoids2 = new Material.Builder(GTCEu.id("actinoids_2"))
                .dust()
                .color(0x80eb33)
                .components(Berkelium, 1, Californium, 1, Einsteinium, 1, Fermium, 1, Mendelevium, 1,
                        Nobelium, 1, Lawrencium, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Actinoids = new Material.Builder(GTCEu.id("actinoids"))
                .dust()
                .color(0x72d22e)
                .components(Actinoids1, 1, Actinoids2, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Alkaline = new Material.Builder(GTCEu.id("alkaline"))
                .dust()
                .color(0xdd186b)
                .components(Lithium, 1, Sodium, 1, Potassium, 1, Rubidium, 1, Caesium, 1,
                        Francium, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AlkalineEarth = new Material.Builder(GTCEu.id("alkaline_earth"))
                .dust()
                .color(0xc1155e)
                .components(Beryllium, 1, Magnesium, 1, Calcium, 1, Strontium, 1, Barium, 1,
                        Radium, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Transition1 = new Material.Builder(GTCEu.id("transition_1"))
                .dust()
                .color(0xa19e9d)
                .components(Titanium, 1, Vanadium, 1, Chromium, 1, Manganese, 1, Iron, 1, Cobalt, 1,
                        Nickel, 1, Copper, 1, Zinc, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Transition2 = new Material.Builder(GTCEu.id("transition_2"))
                .dust()
                .color(0x908d8c)
                .components(Zirconium, 1, Niobium, 1, Molybdenum, 1, Technetium, 1, Ruthenium, 1,
                        Rhodium, 1, Palladium, 1, Silver, 1, Cadmium, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Transition3 = new Material.Builder(GTCEu.id("transition_3"))
                .dust()
                .color(0x838180)
                .components(Hafnium, 1, Tantalum, 1, Tungsten, 1, Rhenium, 1, Osmium, 1, Iridium, 1,
                        Platinum, 1, Gold, 1, Mercury, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Transition = new Material.Builder(GTCEu.id("transition"))
                .fluid()
                .ingot()
                .color(0x989594)
                .blastTemp(13600, HIGHER, GTValues.VA[GTValues.UHV], 900)
                .components(Transition1, 1, Transition2, 1, Transition3, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Poor = new Material.Builder(GTCEu.id("poor"))
                .dust()
                .color(0x916d12)
                .components(Aluminium, 1, Gallium, 1, Indium, 1, Tin, 1, Thallium, 1, Lead, 1,
                        Bismuth, 1, Polonium, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Metalloid = new Material.Builder(GTCEu.id("metalloid"))
                .dust()
                .color(0x916d12)
                .components(Boron, 1, Silicon, 1, Germanium, 1, Arsenic, 1, Antimony, 1,
                        Tellurium, 1, Astatine, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        NotFound = new Material.Builder(GTCEu.id("not_found"))
                .fluid()
                .color(0x1e0ebe)
                .components(Hydrogen, 1, Carbon, 1, Nitrogen, 1, Oxygen, 1, Fluorine, 1,
                        Phosphorus, 1, Sulfur, 1, Chlorine, 1, Selenium, 1, Bromine, 1,
                        Iodine, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        NobleGas = new Material.Builder(GTCEu.id("noble_gas"))
                .fluid()
                .color(0xed8dea)
                .components(Helium, 1, Neon, 1, Argon, 1, Krypton, 1, Xenon, 1, Radon, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Periodicium = new Material.Builder(GTCEu.id("periodicium"))
                .ingot()
                .fluid()
                .blastTemp(15200, HIGHEST, GTValues.VA[GTValues.UEV], 1200)
                .components(NotFound, 1, NobleGas, 1, Metalloid, 1, Poor, 1, Transition, 1,
                        AlkalineEarth, 1, RAREEARTH, 1, Alkaline, 1, Actinoids, 1)
                .color(0x3d4bf6)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        FallKing = new Material.Builder(GTCEu.id("fall_king"))
                .ingot()
                .fluid()
                .blastTemp(5400, HIGH)
                .components(Helium, 1, Lithium, 1, Cobalt, 1, Platinum, 1, Erbium, 1)
                .color(0xffcf6b)
                .iconSet(BRIGHT)
                .buildAndRegister();

        WoodsGlass = new Material.Builder(GTCEu.id("woods_glass"))
                .ingot()
                .fluid()
                .blastTemp(3600)
                .components(SodaAsh, 6, SiliconDioxide, 3, Garnierite, 3, BariumSulfide, 1)
                .color(0x730099)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Polyetheretherketone = new Material.Builder(GTCEu.id("polyetheretherketone"))
                .polymer()
                .fluid()
                .components(Carbon, 20, Hydrogen, 12, Oxygen, 3)
                .color(0x33334d)
                .iconSet(DULL)
                .flags(GENERATE_FINE_WIRE)
                .buildAndRegister();

        CarbonNanotubes = new Material.Builder(GTCEu.id("carbon_nanotubes"))
                .polymer()
                .fluid()
                .color(0x000000)
                .iconSet(DULL)
                .flags(GENERATE_FOIL, GENERATE_FINE_WIRE)
                .buildAndRegister();

        FullerenePolymerMatrixPulp = new Material.Builder(
                GTCEu.id("fullerene_polymer_matrix_pulp"))
                .polymer()
                .fluid()
                .fluidPipeProperties(5000, 5000, true, true, true, true)
                .color(0x23221e)
                .iconSet(DULL)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        Zylon = new Material.Builder(GTCEu.id("zylon"))
                .polymer()
                .fluid()
                .components(Carbon, 14, Hydrogen, 6, Nitrogen, 2, Oxygen, 2)
                .color(0xd2b800)
                .iconSet(DULL)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        Kevlar = new Material.Builder(GTCEu.id("kevlar"))
                .polymer()
                .fluid()
                .color(0x9f9f53)
                .iconSet(DULL)
                .flags(GENERATE_FOIL)
                .buildAndRegister();

        Radox = new Material.Builder(GTCEu.id("radox"))
                .polymer()
                .fluid()
                .components(Carbon, 14, Osmium, 11, Oxygen, 7, Silver, 3, Concrete, 1, Water, 1)
                .color(0x680064)
                .iconSet(DULL)
                .flags(GENERATE_FOIL, DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AdamantineCompounds = new Material.Builder(GTCEu.id("adamantine_compounds"))
                .ore()
                .color(0xdaa520)
                .components(Adamantine, 1, Concrete, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        // Dust
        RawTengam = new Material.Builder(GTCEu.id("raw_tengam"))
                .dust()
                .color(0x819a4e)
                .iconSet(DULL)
                .buildAndRegister();

        CleanRawTengam = new Material.Builder(GTCEu.id("clean_raw_tengam"))
                .dust()
                .color(0x819a4e)
                .iconSet(SHINY)
                .buildAndRegister();

        PurifiedTengam = new Material.Builder(GTCEu.id("purified_tengam"))
                .dust()
                .element(GTLElements.TENGAM)
                .color(0x819a4e)
                .iconSet(METALLIC)
                .buildAndRegister();

        AttunedTengam = new Material.Builder(GTCEu.id("attuned_tengam"))
                .ingot()
                .element(GTLElements.TENGAM)
                .color(0x819a4e)
                .iconSet(MAGNETIC)
                .flags(IS_MAGNETIC, GENERATE_LONG_ROD)
                .buildAndRegister();

        PreZylon = new Material.Builder(GTCEu.id("pre_zylon"))
                .dust()
                .color(0x562050)
                .components(Carbon, 20, Hydrogen, 22, Nitrogen, 2, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Terephthalaldehyde = new Material.Builder(GTCEu.id("terephthalaldehyde"))
                .dust()
                .color(0x4a7454)
                .components(Carbon, 8, Hydrogen, 6, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumOxide = new Material.Builder(GTCEu.id("sodium_oxide"))
                .dust()
                .color(0x036dee)
                .components(Sodium, 2, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Bedrock = new Material.Builder(GTCEu.id("bedrock"))
                .dust()
                .color(0x808080)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        CompressedStone = new Material.Builder(GTCEu.id("compressed_stone"))
                .dust()
                .color(0x696969)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        GermaniumContainingPrecipitate = new Material.Builder(
                GTCEu.id("germanium_containing_precipitate"))
                .dust()
                .color(0x666699)
                .components(Germanium, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GermaniumAsh = new Material.Builder(GTCEu.id("germanium_ash"))
                .dust()
                .color(0x706699)
                .components(Germanium, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GermaniumDioxide = new Material.Builder(GTCEu.id("germanium_dioxide"))
                .dust()
                .color(0xffffff)
                .components(Germanium, 1, Oxygen, 2)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Durene = new Material.Builder(GTCEu.id("durene"))
                .dust()
                .components(Carbon, 10, Hydrogen, 14)
                .color(0x708090)
                .iconSet(DULL)
                .buildAndRegister();

        PyromelliticDianhydride = new Material.Builder(GTCEu.id("pyromellitic_dianhydride"))
                .dust()
                .components(Carbon, 10, Hydrogen, 2, Oxygen, 6)
                .color(0x708090)
                .iconSet(DULL)
                .buildAndRegister();

        CoAcAbCatalyst = new Material.Builder(GTCEu.id("co_ac_ab_catalyst"))
                .dust()
                .color(0x544422)
                .iconSet(DULL)
                .buildAndRegister();

        ZnFeAlClCatalyst = new Material.Builder(GTCEu.id("znfealcl_catalyst"))
                .dust()
                .color(0xcf51aa)
                .iconSet(DULL)
                .buildAndRegister();

        CalciumCarbide = new Material.Builder(GTCEu.id("calcium_carbide"))
                .dust()
                .components(Calcium, 1, Carbon, 2)
                .color(0x47443e)
                .iconSet(DULL)
                .buildAndRegister();

        Difluorobenzophenone = new Material.Builder(GTCEu.id("difluorobenzophenone"))
                .dust()
                .components(Fluorine, 2, Carbon, 13, Hydrogen, 8, Oxygen, 1)
                .color(0xcf51ae)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumFluoride = new Material.Builder(GTCEu.id("sodium_fluoride"))
                .dust()
                .components(Sodium, 1, Fluorine, 1)
                .color(0x460011)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumSeaborgate = new Material.Builder(GTCEu.id("sodium_seaborgate"))
                .dust()
                .components(Sodium, 2, Seaborgium, 1, Oxygen, 4)
                .color(0x55bbd4)
                .iconSet(DULL)
                .buildAndRegister();

        GoldDepletedMolybdenite = new Material.Builder(GTCEu.id("gold_depleted_molybdenite"))
                .dust()
                .color(0x757587)
                .components(Molybdenum, 1, Sulfur, 2)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        MolybdenumTrioxide = new Material.Builder(GTCEu.id("molybdenum_trioxide"))
                .dust()
                .components(Molybdenum, 1, Oxygen, 3)
                .color(0x757587)
                .iconSet(DULL)
                .buildAndRegister();

        Dichlorocyclooctadieneplatinum = new Material.Builder(
                GTCEu.id("dichlorocyclooctadieneplatinium"))
                .dust()
                .color(0xd4e982)
                .components(Carbon, 8, Hydrogen, 12, Chlorine, 2, Platinum, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Diiodobiphenyl = new Material.Builder(GTCEu.id("diiodobiphenyl"))
                .dust()
                .color(0x000a42)
                .components(Carbon, 12, Hydrogen, 8, Iodine, 2)
                .iconSet(DULL)
                .buildAndRegister();

        MolybdenumConcentrate = new Material.Builder(GTCEu.id("molybdenum_concentrate"))
                .dust()
                .color(0x47443e)
                .components(Molybdenum, 1, Sulfur, 2, Concrete, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        BoronTrioxide = new Material.Builder(GTCEu.id("boron_trioxide"))
                .dust()
                .components(Boron, 2, Oxygen, 3)
                .color(0x8fa6b5)
                .iconSet(DULL)
                .buildAndRegister();

        LithiumNiobateNanoparticles = new Material.Builder(
                GTCEu.id("lithium_niobate_nanoparticles"))
                .dust()
                .color(0xc1c12d)
                .components(Lithium, 2, Niobium, 1, Oxygen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        Hexanitrohexaaxaisowurtzitane = new Material.Builder(
                GTCEu.id("hexanitrohexaaxaisowurtzitane"))
                .dust()
                .components(Carbon, 6, Hydrogen, 6, Nitrogen, 12, Oxygen, 12)
                .color(0x3d464b)
                .iconSet(DULL)
                .buildAndRegister();

        SilicaGel = new Material.Builder(GTCEu.id("silica_gel"))
                .dust()
                .color(0x57c3e4)
                .iconSet(DULL)
                .buildAndRegister();

        CrudeHexanitrohexaaxaisowurtzitane = new Material.Builder(
                GTCEu.id("crude_hexanitrohexaaxaisowurtzitane"))
                .dust()
                .color(0x19586d)
                .components(Carbon, 6, Hydrogen, 6, Nitrogen, 12, Oxygen, 12)
                .iconSet(DULL)
                .buildAndRegister();

        Tetraacetyldinitrosohexaazaisowurtzitane = new Material.Builder(
                GTCEu.id("tetraacetyldinitrosohexaazaisowurtzitane"))
                .dust()
                .color(0x500449)
                .components(Carbon, 14, Hydrogen, 18, Nitrogen, 8, Oxygen, 6)
                .iconSet(DULL)
                .buildAndRegister();

        NitroniumTetrafluoroborate = new Material.Builder(
                GTCEu.id("nitronium_tetrafluoroborate"))
                .dust()
                .color(0x3c3f40)
                .components(Nitrogen, 1, Oxygen, 2, Boron, 1, Fluorine, 4)
                .iconSet(DULL)
                .buildAndRegister();

        NitrosoniumTetrafluoroborate = new Material.Builder(
                GTCEu.id("nitrosonium_tetrafluoroborate"))
                .dust()
                .color(0x485054)
                .components(Nitrogen, 1, Oxygen, 1, Boron, 1, Fluorine, 4)
                .iconSet(DULL)
                .buildAndRegister();

        Dibenzyltetraacetylhexaazaisowurtzitane = new Material.Builder(
                GTCEu.id("dibenzyltetraacetylhexaazaisowurtzitane"))
                .dust()
                .color(0x64704d)
                .components(Carbon, 28, Hydrogen, 32, Nitrogen, 6, Oxygen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        SuccinamidylAcetate = new Material.Builder(GTCEu.id("succinimidyl_acetate"))
                .dust()
                .color(0x64704d)
                .components(Carbon, 6, Hydrogen, 7, Nitrogen, 1, Oxygen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        Hexabenzylhexaazaisowurtzitane = new Material.Builder(
                GTCEu.id("hexabenzylhexaazaisowurtzitane"))
                .dust()
                .color(0x64704d)
                .components(Carbon, 48, Hydrogen, 48, Nitrogen, 6)
                .iconSet(DULL)
                .buildAndRegister();

        NHydroxysuccinimide = new Material.Builder(GTCEu.id("n_hydroxysuccinimide"))
                .dust()
                .color(0x7b717f)
                .components(Carbon, 4, Hydrogen, 5, Nitrogen, 1, Oxygen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        SuccinicAnhydride = new Material.Builder(GTCEu.id("succinic_anhydride"))
                .dust()
                .color(0x401116)
                .components(Carbon, 4, Hydrogen, 4, Oxygen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        SuccinicAcid = new Material.Builder(GTCEu.id("succinic_acid"))
                .dust()
                .color(0x104e5c)
                .components(Carbon, 4, Hydrogen, 6, Oxygen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        Acetonitrile = new Material.Builder(GTCEu.id("acetonitrile"))
                .dust()
                .color(0x5a6161)
                .components(Carbon, 2, Hydrogen, 3, Nitrogen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Hexamethylenetetramine = new Material.Builder(GTCEu.id("hexamethylenetetramine"))
                .dust()
                .color(0x5a6261)
                .components(Carbon, 6, Hydrogen, 12, Nitrogen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        RareEarthOxide = new Material.Builder(GTCEu.id("rare_earth_oxide"))
                .dust()
                .color(0x808000)
                .components(Concrete, 1, Oxygen, 1)
                .iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        RareEarthMetal = new Material.Builder(GTCEu.id("rare_earth_metal"))
                .dust()
                .ore()
                .addOreByproducts(RareEarth, Monazite)
                .color(0x737373)
                .iconSet(METALLIC)
                .buildAndRegister().setFormula("?");

        NaquadahContainRareEarth = new Material.Builder(GTCEu.id("naquadah_contain_rare_earth"))
                .dust()
                .color(0xffd700)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        NaquadahContainRareEarthFluoride = new Material.Builder(
                GTCEu.id("naquadah_contain_rare_earth_fluoride"))
                .dust()
                .color(0xb3b300)
                .components(Concrete, 1, Fluorine, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        MetalResidue = new Material.Builder(GTCEu.id("metal_residue"))
                .dust()
                .color(0x652118)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        PalladiumFullereneMatrix = new Material.Builder(GTCEu.id("palladium_fullerene_matrix"))
                .dust()
                .color(0x96b4b4)
                .components(Palladium, 1, Carbon, 73, Hydrogen, 15, Nitrogen, 1, Iron, 1)
                .iconSet(BRIGHT)
                .buildAndRegister();

        Fullerene = new Material.Builder(GTCEu.id("fullerene"))
                .dust()
                .color(0x86c2b8)
                .components(Carbon, 60)
                .iconSet(DULL)
                .buildAndRegister();

        UnfoldedFullerene = new Material.Builder(GTCEu.id("unfolded_fullerene"))
                .dust()
                .color(0x587f83)
                .components(Carbon, 60, Hydrogen, 30)
                .iconSet(DULL)
                .buildAndRegister();

        Methylbenzophenanthrene = new Material.Builder(GTCEu.id("methylbenzophenanthrene"))
                .dust()
                .color(0x79260c)
                .components(Carbon, 19, Hydrogen, 14)
                .iconSet(DULL)
                .buildAndRegister();

        Sarcosine = new Material.Builder(GTCEu.id("sarcosine"))
                .dust()
                .color(0x809324)
                .components(Carbon, 3, Hydrogen, 7, Nitrogen, 1, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        DiphenylmethaneDiisocyanate = new Material.Builder(
                GTCEu.id("diphenylmethane_diisocyanate"))
                .dust()
                .color(0x8e801c)
                .components(Carbon, 15, Hydrogen, 10, Nitrogen, 2, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Pentaerythritol = new Material.Builder(GTCEu.id("pentaerythritol"))
                .dust()
                .color(0xacacac)
                .components(Carbon, 5, Hydrogen, 12, Oxygen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        KelpSlurry = new Material.Builder(GTCEu.id("kelp_slurry"))
                .fluid()
                .color(0x336600)
                .iconSet(DULL)
                .buildAndRegister();

        Quasifissioning = new Material.Builder(GTCEu.id("quasifissioning"))
                .plasma()
                .color(0xcdbe35)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        ExcitedDtec = new Material.Builder(GTCEu.id("exciteddtec"))
                .fluid()
                .color(0xb36a6b)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        ExcitedDtsc = new Material.Builder(GTCEu.id("exciteddtsc"))
                .fluid()
                .color(0xb36a6b)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        DimensionallyTranscendentResplendentCatalyst = new Material.Builder(
                GTCEu.id("dimensionallytranscendentresplendentcatalyst"))
                .fluid()
                .color(0x081010)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        DimensionallyTranscendentProsaicCatalyst = new Material.Builder(
                GTCEu.id("dimensionallytranscendentprosaiccatalyst"))
                .fluid()
                .color(0x081010)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        DimensionallyTranscendentExoticCatalyst = new Material.Builder(
                GTCEu.id("dimensionallytranscendentexoticcatalyst"))
                .fluid()
                .color(0x081010)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        DimensionallyTranscendentStellarCatalyst = new Material.Builder(
                GTCEu.id("dimensionallytranscendentstellarcatalyst"))
                .fluid()
                .color(0x081010)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        DimensionallyTranscendentCrudeCatalyst = new Material.Builder(
                GTCEu.id("dimensionallytranscendentcrudecatalyst"))
                .fluid()
                .color(0x081010)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        Ytterbium178 = new Material.Builder(GTCEu.id("ytterbium_178"))
                .fluid()
                .element(GTLElements.YITTERBIUM178)
                .color(0xffffff)
                .iconSet(DULL)
                .buildAndRegister();

        Flyb = new Material.Builder(GTCEu.id("flyb"))
                .plasma()
                .components(Flerovium, 1, Ytterbium178, 1)
                .color(0x890a95)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        EnrichedPotassiumIodideSlurry = new Material.Builder(
                GTCEu.id("enriched_potassium_iodide_slurry"))
                .fluid()
                .color(0x00ffcc)
                .components(Potassium, 1, Iodine, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        IodineContainingSlurry = new Material.Builder(GTCEu.id("iodine_containing_slurry"))
                .fluid()
                .color(0x666633)
                .components(Iodine, 1, RockSalt, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AshLeachingSolution = new Material.Builder(GTCEu.id("ash_leaching_solution"))
                .fluid()
                .color(0x666699)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        Tannic = new Material.Builder(GTCEu.id("tannic"))
                .fluid()
                .components(Carbon, 76, Hydrogen, 52, Oxygen, 46)
                .color(0xffff66)
                .iconSet(DULL)
                .buildAndRegister();

        GermaniumTetrachlorideSolution = new Material.Builder(
                GTCEu.id("germanium_tetrachloride_solution"))
                .fluid()
                .color(0x66ffcc)
                .components(Germanium, 1, Chlorine, 4)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Polyimide = new Material.Builder(GTCEu.id("polyimide"))
                .fluid()
                .components(Carbon, 22, Hydrogen, 12, Nitrogen, 2, Oxygen, 6)
                .color(0xff6730)
                .iconSet(DULL)
                .buildAndRegister();

        Aniline = new Material.Builder(GTCEu.id("aniline"))
                .fluid()
                .components(Carbon, 6, Hydrogen, 7, Nitrogen, 1)
                .color(0x3d7517)
                .iconSet(DULL)
                .buildAndRegister();

        Oxydianiline = new Material.Builder(GTCEu.id("oxydianiline"))
                .fluid()
                .components(Carbon, 12, Hydrogen, 12, Nitrogen, 2, Oxygen, 1)
                .color(0xffd700)
                .iconSet(DULL)
                .buildAndRegister();

        BoricAcid = new Material.Builder(GTCEu.id("boric_acide"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .components(Hydrogen, 3, Boron, 1, Oxygen, 3)
                .color(0x8fbc8f)
                .iconSet(DULL)
                .buildAndRegister();

        FluoroboricAcid = new Material.Builder(GTCEu.id("fluoroboric_acide"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .components(Hydrogen, 1, Boron, 1, Fluorine, 4)
                .color(0x8fbc8f)
                .iconSet(DULL)
                .buildAndRegister();

        BenzenediazoniumTetrafluoroborate = new Material.Builder(
                GTCEu.id("benzenediazonium_tetrafluoroborate"))
                .fluid()
                .components(Carbon, 6, Hydrogen, 5, Boron, 1, Fluorine, 4, Nitrogen, 2)
                .color(0x8fbc8f)
                .iconSet(DULL)
                .buildAndRegister();

        FluoroBenzene = new Material.Builder(GTCEu.id("fluoro_benzene"))
                .fluid()
                .components(Carbon, 6, Hydrogen, 5, Fluorine, 1)
                .color(0x8fbc8f)
                .iconSet(DULL)
                .buildAndRegister();

        Fluorotoluene = new Material.Builder(GTCEu.id("fluorotoluene"))
                .fluid()
                .components(Carbon, 7, Hydrogen, 7, Fluorine, 1)
                .color(0xdad386)
                .iconSet(DULL)
                .buildAndRegister();

        Hydroquinone = new Material.Builder(GTCEu.id("hydroquinone"))
                .fluid()
                .components(Carbon, 6, Hydrogen, 6, Oxygen, 2)
                .color(0x8e2518)
                .iconSet(DULL)
                .buildAndRegister();

        Resorcinol = new Material.Builder(GTCEu.id("resorcinol"))
                .fluid()
                .components(Carbon, 6, Hydrogen, 6, Oxygen, 2)
                .color(0x8e2518)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumNitrate = new Material.Builder(GTCEu.id("sodium_nitrate"))
                .dust()
                .color(0x4e2a40).iconSet(SAND)
                .components(Sodium, 1, Nitrogen, 1, Oxygen, 3)
                .buildAndRegister();

        SodiumNitrateSolution = new Material.Builder(GTCEu.id("sodium_nitrate_solution"))
                .fluid()
                .components(SodiumNitrate, 1, Water, 1)
                .color(0x2b387e)
                .iconSet(DULL)
                .buildAndRegister();

        Acetylene = new Material.Builder(GTCEu.id("acetylene"))
                .fluid()
                .components(Carbon, 2, Hydrogen, 2)
                .color(0x7f8552)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumCyanide = new Material.Builder(GTCEu.id("sodium_cyanide"))
                .fluid()
                .components(Sodium, 1, Carbon, 1, Nitrogen, 1)
                .color(0x4f6774)
                .iconSet(DULL)
                .buildAndRegister();

        GoldCyanide = new Material.Builder(GTCEu.id("gold_cyanide"))
                .fluid()
                .components(Gold, 1, Carbon, 1, Nitrogen, 1)
                .color(0x736f50)
                .iconSet(DULL)
                .buildAndRegister();

        MolybdenumFlue = new Material.Builder(GTCEu.id("molybdenum_flue"))
                .gas()
                .color(0x4b626f)
                .components(Sulfur, 2, Concrete, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        RheniumSulfuricSolution = new Material.Builder(GTCEu.id("rhenium_sulfuric_solution"))
                .fluid()
                .color(0xc0c0ea)
                .components(Rhenium, 1, Sulfur, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AmmoniumPerrhenate = new Material.Builder(GTCEu.id("ammonium_perrhenate"))
                .fluid()
                .color(0x161637)
                .components(Ammonia, 1, Rhenium, 1, Oxygen, 4)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Cycloparaphenylene = new Material.Builder(GTCEu.id("cycloparaphenylene"))
                .fluid()
                .color(0x000000)
                .iconSet(DULL)
                .buildAndRegister();

        TrimethylTinChloride = new Material.Builder(GTCEu.id("trimethyltin_chloride"))
                .fluid()
                .color(0x72685f)
                .components(Tin, 1, Carbon, 3, Hydrogen, 9, Chlorine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        SilverTetrafluoroborate = new Material.Builder(GTCEu.id("silver_tetrafluoroborate"))
                .fluid()
                .color(0x76750f)
                .components(Silver, 1, Boron, 1, Fluorine, 4)
                .iconSet(DULL)
                .buildAndRegister();

        BoronFluoride = new Material.Builder(GTCEu.id("boron_fluoride"))
                .fluid()
                .components(Boron, 1, Fluorine, 3)
                .color(0xcecad0)
                .iconSet(DULL)
                .buildAndRegister();

        OneOctene = new Material.Builder(GTCEu.id("1_octene"))
                .fluid()
                .components(Carbon, 8, Hydrogen, 16)
                .color(0x666d61)
                .iconSet(DULL)
                .buildAndRegister();

        Pyridine = new Material.Builder(GTCEu.id("pyridine"))
                .fluid()
                .color(0x555642)
                .components(Carbon, 5, Hydrogen, 5, Nitrogen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Acetaldehyde = new Material.Builder(GTCEu.id("acetaldehyde"))
                .fluid()
                .components(Carbon, 2, Hydrogen, 4, Oxygen, 1)
                .color(0x666d61)
                .iconSet(DULL)
                .buildAndRegister();

        Cyclooctadiene = new Material.Builder(GTCEu.id("cyclooctadiene"))
                .fluid()
                .color(0xd4e982)
                .components(Carbon, 8, Hydrogen, 12)
                .iconSet(DULL)
                .buildAndRegister();

        SeaborgiumDopedNanotubes = new Material.Builder(GTCEu.id("seaborgium_doped_nanotubes"))
                .fluid()
                .color(0x242473)
                .iconSet(DULL)
                .buildAndRegister();

        Ethylenediamine = new Material.Builder(GTCEu.id("ethylenediamine"))
                .fluid()
                .color(0x1b5d74)
                .components(Carbon, 2, Hydrogen, 8, Nitrogen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Ethanolamine = new Material.Builder(GTCEu.id("ethanolamine"))
                .fluid()
                .color(0x1b5d74)
                .components(Carbon, 2, Hydrogen, 7, Nitrogen, 1, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        EthyleneOxide = new Material.Builder(GTCEu.id("ethylene_oxide"))
                .fluid()
                .color(0x8eb7d8)
                .components(Carbon, 2, Hydrogen, 4, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Benzaldehyde = new Material.Builder(GTCEu.id("benzaldehyde"))
                .fluid()
                .color(0x905a1b)
                .components(Carbon, 7, Hydrogen, 6, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        HydroxylamineHydrochloride = new Material.Builder(
                GTCEu.id("hydroxylamine_hydrochloride"))
                .fluid()
                .color(0x433217)
                .components(Hydrogen, 4, Nitrogen, 1, Oxygen, 1, Chlorine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        MaleicAnhydride = new Material.Builder(GTCEu.id("maleic_anhydride"))
                .fluid()
                .color(0x321b90)
                .components(Carbon, 4, Hydrogen, 2, Oxygen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        Benzylamine = new Material.Builder(GTCEu.id("benzylamine"))
                .fluid()
                .color(0x5b6363)
                .components(Carbon, 7, Hydrogen, 9, Nitrogen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Glyoxal = new Material.Builder(GTCEu.id("glyoxal"))
                .fluid()
                .color(0xf0ed4d)
                .components(Carbon, 2, Hydrogen, 2, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        BenzylChloride = new Material.Builder(GTCEu.id("benzyl_chloride"))
                .fluid()
                .color(0x9ff6fb)
                .components(Carbon, 7, Hydrogen, 7, Chlorine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Mana = new Material.Builder(GTCEu.id("mana"))
                .gas()
                .color(0x9400d3)
                .element(GTLElements.MANA)
                .iconSet(DULL)
                .buildAndRegister();

        RareEarthHydroxides = new Material.Builder(GTCEu.id("rare_earth_hydroxides"))
                .fluid()
                .color(0x808000)
                .components(Concrete, 1, Oxygen, 1, Hydrogen, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        QuantumDots = new Material.Builder(GTCEu.id("quantum_dots"))
                .fluid()
                .color(0xda0000)
                .iconSet(DULL)
                .buildAndRegister();

        StearicAcid = new Material.Builder(GTCEu.id("stearic_acid"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .color(0x239791)
                .components(Carbon, 18, Hydrogen, 36, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Soap = new Material.Builder(GTCEu.id("soap"))
                .fluid()
                .color(0xff9d1b)
                .iconSet(DULL)
                .buildAndRegister();

        Tricotylphosphine = new Material.Builder(GTCEu.id("tricotylphosphine"))
                .fluid()
                .color(0xe7d510)
                .components(Carbon, 24, Hydrogen, 51, Phosphorus, 1)
                .iconSet(DULL)
                .buildAndRegister();

        IridiumTrichlorideSolution = new Material.Builder(
                GTCEu.id("iridium_trichloride_solution"))
                .fluid()
                .color(0x776715)
                .components(Iridium, 1, Chlorine, 3)
                .iconSet(DULL)
                .buildAndRegister();

        LiquidHydrogen = new Material.Builder(GTCEu.id("liquid_hydrogen"))
                .fluid()
                .color(0x4fc4a2)
                .components(Hydrogen, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        BedrockSmoke = new Material.Builder(GTCEu.id("bedrock_smoke"))
                .gas()
                .color(0xa9a9a9)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        BedrockSootSolution = new Material.Builder(GTCEu.id("bedrock_soot_solution"))
                .fluid()
                .color(0x191970)
                .iconSet(DULL)
                .buildAndRegister().setFormula("Nq?");

        CleanBedrockSolution = new Material.Builder(GTCEu.id("clean_bedrock_solution"))
                .fluid()
                .color(0x778899)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister().setFormula("?");

        BedrockGas = new Material.Builder(GTCEu.id("bedrock_gas"))
                .gas()
                .color(0xc0c0c0)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        VibraniumUnstable = new Material.Builder(GTCEu.id("vibranium_unstable"))
                .fluid()
                .color(0xfa8072)
                .components(Vibranium, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TaraniumEnrichedLiquidHelium3 = new Material.Builder(
                GTCEu.id("taranium_enriched_liquid_helium_3"))
                .fluid()
                .color(0x57f26d)
                .components(Taranium, 1, Helium3, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TaraniumRichLiquidHelium4 = new Material.Builder(
                GTCEu.id("taranium_rich_liquid_helium_4"))
                .fluid()
                .plasma()
                .color(0x57f26d)
                .components(Taranium, 1, Helium, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        FreeElectronGas = new Material.Builder(GTCEu.id("free_electron_gas"))
                .gas()
                .color(0x033c3c)
                .element(GTLElements.ELECTRON)
                .iconSet(DULL)
                .buildAndRegister();

        FreeAlphaGas = new Material.Builder(GTCEu.id("free_alpha_gas"))
                .gas()
                .color(0xb5ab06)
                .element(GTLElements.ALPHA)
                .iconSet(DULL)
                .buildAndRegister();

        FreeProtonGas = new Material.Builder(GTCEu.id("free_proton_gas"))
                .gas()
                .color(0x2089aa)
                .element(GTLElements.PROTON)
                .iconSet(DULL)
                .buildAndRegister();

        QuarkGluon = new Material.Builder(GTCEu.id("quark_gluon"))
                .plasma()
                .color(0x7a00da)
                .element(GTLElements.QUARK_GLUON)
                .iconSet(DULL)
                .buildAndRegister();

        HeavyQuarks = new Material.Builder(GTCEu.id("heavy_quarks"))
                .gas()
                .color(0x007000)
                .element(GTLElements.HEAVY_QUARKS)
                .iconSet(DULL)
                .buildAndRegister();

        LightQuarks = new Material.Builder(GTCEu.id("light_quarks"))
                .gas()
                .color(0x0000ce)
                .element(GTLElements.LIGHT_QUARKS)
                .iconSet(DULL)
                .buildAndRegister();

        Gluons = new Material.Builder(GTCEu.id("gluons"))
                .gas()
                .color(0xfbfbf9)
                .element(GTLElements.GLUONS)
                .iconSet(DULL)
                .buildAndRegister();

        OganessonBreedingBase = new Material.Builder(GTCEu.id("oganesson_breeding_base"))
                .fluid()
                .color(0xb8676c)
                .iconSet(DULL)
                .buildAndRegister();

        TitaniumTetrafluoride = new Material.Builder(GTCEu.id("titanium_tetrafluoride"))
                .fluid()
                .color(0xd68fed)
                .components(Titanium, 1, Fluorine, 4)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Titanium50 = new Material.Builder(GTCEu.id("titanium_50"))
                .ingot()
                .fluid()
                .element(GTLElements.TITANIUM50)
                .blastTemp(1942)
                .color(0xd58eed)
                .iconSet(METALLIC)
                .buildAndRegister();

        Titanium50Tetrafluoride = new Material.Builder(GTCEu.id("titanium_50_tetrafluoride"))
                .fluid()
                .color(0xd68fed)
                .components(Titanium50, 1, Fluorine, 4)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Titanium50Tetrachloride = new Material.Builder(GTCEu.id("titanium_50_tetrachloride"))
                .fluid()
                .color(0xafeeee)
                .components(Titanium50, 1, Chlorine, 4)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HotOganesson = new Material.Builder(GTCEu.id("hot_oganesson"))
                .fluid()
                .color(0x42145d)
                .components(Oganesson, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Ferrocene = new Material.Builder(GTCEu.id("ferrocene"))
                .fluid()
                .color(0x7373c9)
                .components(Carbon, 10, Hydrogen, 10, Iron, 1)
                .iconSet(DULL)
                .buildAndRegister();

        ScandiumTitanium50Mixture = new Material.Builder(
                GTCEu.id("scandium_titanium_50_mixture"))
                .fluid()
                .color(0xeb315f)
                .components(Scandium, 1, Titanium50, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DragonBreath = new Material.Builder(GTCEu.id("dragon_breath"))
                .fluid()
                .color(0xff00ff)
                .components(Draconium, 1, Concrete, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        EnrichedDragonBreath = new Material.Builder(GTCEu.id("enriched_dragon_breath"))
                .fluid()
                .color(0xff00ff)
                .components(Draconium, 1, Concrete, 1)
                .iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DragonBlood = new Material.Builder(GTCEu.id("dragon_blood"))
                .fluid()
                .color(0x9932cc)
                .components(Draconium, 1, Concrete, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TurbidDragonBlood = new Material.Builder(GTCEu.id("turbid_dragon_blood"))
                .fluid()
                .color(0x4d0099)
                .components(Draconium, 1, Concrete, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DragonElement = new Material.Builder(GTCEu.id("dragon_element"))
                .fluid()
                .color(0x9933ff)
                .iconSet(DULL)
                .components(Draconium, 1)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HeavyLeptonMixture = new Material.Builder(GTCEu.id("heavy_lepton_mixture"))
                .gas()
                .color(0x3ad931)
                .element(GTLElements.HEAVY_LEPTON_MIXTURE)
                .iconSet(DULL)
                .buildAndRegister();

        HeavyQuarkEnrichedMixture = new Material.Builder(
                GTCEu.id("heavy_quark_enriched_mixture"))
                .gas()
                .color(0xececec)
                .element(GTLElements.HEAVY_QUARK_ENRICHED_MIXTURE)
                .iconSet(DULL)
                .buildAndRegister();

        CosmicComputingMixture = new Material.Builder(GTCEu.id("cosmic_computing_mixture"))
                .gas()
                .color(0x8b8925)
                .components(Gluons, 1, HeavyQuarks, 1, HeavyLeptonMixture, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LiquidStarlight = new Material.Builder(GTCEu.id("liquid_starlight"))
                .liquid(new FluidBuilder().customStill())
                .element(GTLElements.STARLIGHT)
                .buildAndRegister();

        Starlight = new Material.Builder(GTCEu.id("starlight"))
                .liquid(new FluidBuilder().customStill())
                .element(GTLElements.STARLIGHT)
                .buildAndRegister();

        DenseNeutron = new Material.Builder(GTCEu.id("dense_neutron"))
                .plasma()
                .color(0x9ce89c)
                .element(GTLElements.DENSE_NEUTRON)
                .iconSet(DULL)
                .buildAndRegister();

        HighEnergyQuarkGluon = new Material.Builder(GTCEu.id("high_energy_quark_gluon"))
                .plasma()
                .color(0x7400ce)
                .element(GTLElements.HEAVY_QUARK_ENRICHED_MIXTURE)
                .iconSet(BRIGHT)
                .buildAndRegister();

        NeutroniumDopedNanotubes = new Material.Builder(GTCEu.id("neutronium_doped_nanotubes"))
                .fluid()
                .color(0x5bf5f5)
                .iconSet(DULL)
                .buildAndRegister();

        AmmoniumNitrateSolution = new Material.Builder(GTCEu.id("ammonium_nitrate_solution"))
                .fluid()
                .color(0xe1ffff)
                .components(Nitrogen, 2, Hydrogen, 4, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        NaquadahSolution = new Material.Builder(GTCEu.id("naquadah_solution"))
                .fluid()
                .color(0x00ff00)
                .iconSet(DULL)
                .buildAndRegister();

        FluorineCrackedAquadah = new Material.Builder(GTCEu.id("fluorine_cracked_aquadah"))
                .fluid()
                .color(0x424d4b)
                .iconSet(DULL)
                .buildAndRegister();

        RadonCrackedEnrichedAquadah = new Material.Builder(
                GTCEu.id("radon_cracked_enriched_aquadah"))
                .fluid()
                .color(0x424d4b)
                .iconSet(DULL)
                .buildAndRegister();

        NaquadahFuel = new Material.Builder(GTCEu.id("naquadah_fuel"))
                .fluid()
                .color(0x292929)
                .components(Naquadah, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        EnrichedNaquadahFuel = new Material.Builder(GTCEu.id("enriched_naquadah_fuel"))
                .fluid()
                .color(0x292929)
                .components(NaquadahEnriched, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HyperFuel1 = new Material.Builder(GTCEu.id("hyper_fuel_1"))
                .fluid()
                .color(0xf9ff3d)
                .components(Naquadah, 1, NaquadahEnriched, 1, Naquadria, 1, Thorium, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HyperFuel2 = new Material.Builder(GTCEu.id("hyper_fuel_2"))
                .fluid()
                .color(0xd1d54d)
                .components(HyperFuel1, 1, Uranium235, 1, Dubnium, 1, Fermium, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HyperFuel3 = new Material.Builder(GTCEu.id("hyper_fuel_3"))
                .fluid()
                .color(0x7a7c3c)
                .components(HyperFuel2, 1, Plutonium241, 1, Adamantine, 1, Lawrencium, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HyperFuel4 = new Material.Builder(GTCEu.id("hyper_fuel_4"))
                .fluid()
                .color(0x3f4028)
                .components(HyperFuel3, 1, Nobelium, 1, Neutronium, 1, Taranium, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ConcentrationMixingHyperFuel1 = new Material.Builder(
                GTCEu.id("concentration_mixing_hyper_fuel_1"))
                .fluid()
                .color(0x000000)
                .components(HyperFuel4, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ConcentrationMixingHyperFuel2 = new Material.Builder(
                GTCEu.id("concentration_mixing_hyper_fuel_2"))
                .fluid()
                .color(0x000000)
                .components(HyperFuel4, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CosmicElement = new Material.Builder(GTCEu.id("cosmic_element"))
                .gas()
                .color(0xa366ff)
                .components(FreeElectronGas, 1, FreeAlphaGas, 1, FreeProtonGas, 1, QuarkGluon, 1,
                        HeavyQuarks, 1, LightQuarks, 1, Gluons, 1, HeavyLeptonMixture, 1,
                        HeavyQuarkEnrichedMixture, 1, DenseNeutron, 1,
                        HighEnergyQuarkGluon, 1)
                .iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DimensionallyTranscendentResidue = new Material.Builder(
                GTCEu.id("dimensionallytranscendentresidue"))
                .liquid(new FluidBuilder().customStill())
                .buildAndRegister().setFormula("?");

        PrimordialMatter = new Material.Builder(GTCEu.id("primordialmatter"))
                .liquid(new FluidBuilder().customStill())
                .buildAndRegister().setFormula("?");

        SpatialFluid = new Material.Builder(GTCEu.id("spatialfluid"))
                .liquid(new FluidBuilder().customStill())
                .element(GTLElements.TIME)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TemporalFluid = new Material.Builder(GTCEu.id("temporalfluid"))
                .liquid(new FluidBuilder().customStill())
                .element(GTLElements.TIME)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Isochloropropane = new Material.Builder(GTCEu.id("isochloropropane"))
                .fluid()
                .color(0xcdd681)
                .components(Carbon, 3, Hydrogen, 7, Chlorine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Dinitrodipropanyloxybenzene = new Material.Builder(
                GTCEu.id("dinitrodipropanyloxybenzene"))
                .fluid()
                .color(0x6a784d)
                .components(Carbon, 12, Hydrogen, 16, Nitrogen, 2, Oxygen, 6)
                .iconSet(DULL)
                .buildAndRegister();

        RadoxGas = new Material.Builder(GTCEu.id("radox_gas"))
                .gas()
                .components(Carbon, 14, Osmium, 11, Silver, 3, Concrete, 1, Water, 1)
                .color(0xab57ab)
                .iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CrackedRadox = new Material.Builder(GTCEu.id("crackedradox"))
                .fluid()
                .components(Carbon, 14, Osmium, 11, Silver, 3, Concrete, 1, Water, 1)
                .color(0xab57ab)
                .iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SuperLightRadox = new Material.Builder(GTCEu.id("superlightradox"))
                .fluid()
                .components(Carbon, 14, Osmium, 11, Silver, 2, Concrete, 1, Water, 1)
                .color(0x6c006c)
                .iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LightRadox = new Material.Builder(GTCEu.id("lightradox"))
                .fluid()
                .components(Carbon, 14, Osmium, 11, Silver, 1, Concrete, 1, Water, 1)
                .color(0x6c006c)
                .iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SuperHeavyRadox = new Material.Builder(GTCEu.id("superheavyradox"))
                .fluid()
                .components(Carbon, 14, Osmium, 11, Concrete, 1, Water, 1)
                .color(0x6c006c)
                .iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HeavyRadox = new Material.Builder(GTCEu.id("heavyradox"))
                .fluid()
                .components(Carbon, 14, Osmium, 11, Concrete, 1, Water, 1)
                .color(0x6c006c)
                .iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        RawRadox = new Material.Builder(GTCEu.id("rawradox"))
                .fluid()
                .color(0x391539)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        Xenoxene = new Material.Builder(GTCEu.id("xenoxene"))
                .fluid()
                .color(0x5c5a58)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        XenoxeneCrystal = new Material.Builder(GTCEu.id("xenoxene_crystal"))
                .dust()
                .color(0x5c5a58)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        XenoxeneMixture = new Material.Builder(GTCEu.id("xenoxene_mixture"))
                .fluid()
                .color(0x5c7a58)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        EnrichedXenoxene = new Material.Builder(GTCEu.id("enriched_xenoxene"))
                .fluid()
                .color(0x5c5a58)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        PurifiedXenoxene = new Material.Builder(GTCEu.id("purified_xenoxene"))
                .fluid()
                .color(0x5c7a58)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        DilutedXenoxene = new Material.Builder(GTCEu.id("dilutedxenoxene"))
                .fluid()
                .color(0x8b8784)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("?");

        Dibromomethylbenzene = new Material.Builder(GTCEu.id("dibromomethylbenzene"))
                .fluid()
                .components(Carbon, 7, Hydrogen, 6, Bromine, 2)
                .color(0x6b584)
                .iconSet(DULL)
                .buildAndRegister();

        RawStarMatter = new Material.Builder(GTCEu.id("raw_star_matter"))
                .element(GTLElements.RAW_STAR_MATTER)
                .plasma(new FluidBuilder().customStill())
                .buildAndRegister();

        XpJuice = new Material.Builder(GTCEu.id("xpjuice"))
                .liquid(new FluidBuilder().customStill())
                .buildAndRegister();

        BiomediumRaw = new Material.Builder(GTCEu.id("biomediumraw"))
                .fluid()
                .color(0x42641f)
                .iconSet(DULL)
                .buildAndRegister();

        BiohmediumSterilized = new Material.Builder(GTCEu.id("biohmediumsterilized"))
                .fluid()
                .color(0x72b125)
                .iconSet(DULL)
                .buildAndRegister();

        UnknowWater = new Material.Builder(GTCEu.id("unknowwater"))
                .fluid()
                .color(0x3322aa)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        UnknownNutrientAgar = new Material.Builder(GTCEu.id("unknownnutrientagar"))
                .fluid()
                .color(0x916e00)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        SeaweedBroth = new Material.Builder(GTCEu.id("seaweedbroth"))
                .fluid()
                .color(0x2c9400)
                .iconSet(DULL)
                .buildAndRegister().setFormula("?");

        LiquidCrystalKevlar = new Material.Builder(GTCEu.id("liquidcrystalkevlar"))
                .fluid()
                .color(0x9f9f50)
                .iconSet(DULL)
                .buildAndRegister();

        PolyurethaneResin = new Material.Builder(GTCEu.id("polyurethaneresin"))
                .fluid()
                .color(0x9a9a51)
                .iconSet(DULL)
                .buildAndRegister();

        DiphenylmethanediisocyanateMixture = new Material.Builder(
                GTCEu.id("diphenylmethanediisocyanatemixture"))
                .fluid()
                .color(0x94851d)
                .iconSet(DULL)
                .buildAndRegister();

        Phosgene = new Material.Builder(GTCEu.id("phosgene"))
                .fluid()
                .components(Carbon, 1, Oxygen, 1, Chlorine, 2)
                .color(0x0e6c11)
                .iconSet(DULL)
                .buildAndRegister();

        DiaminodiphenylmethanMixture = new Material.Builder(GTCEu.id("diaminodiphenylmethanmixture"))
                .fluid()
                .color(0x94851d)
                .iconSet(DULL)
                .buildAndRegister();

        SiliconOil = new Material.Builder(GTCEu.id("siliconoil"))
                .fluid()
                .color(0xadadad)
                .iconSet(DULL)
                .buildAndRegister();

        EthyleneGlycol = new Material.Builder(GTCEu.id("ethyleneglycol"))
                .fluid()
                .color(0xadadad)
                .components(Carbon, 2, Hydrogen, 6, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        PCBs = new Material.Builder(GTCEu.id("pcbs"))
                .fluid()
                .color(0x758a61)
                .components(Carbon, 80, Hydrogen, 21, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        DMAP = new Material.Builder(GTCEu.id("dmap"))
                .dust()
                .color(0x758a61)
                .components(Carbon, 7, Hydrogen, 10, Nitrogen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        PhenylPentanoicAcid = new Material.Builder(GTCEu.id("phenylpentanoic_acid"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .color(0x4d3833)
                .components(Carbon, 11, Hydrogen, 14, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Dichloromethane = new Material.Builder(GTCEu.id("dichloromethane"))
                .fluid()
                .color(0x832663)
                .components(Carbon, 1, Hydrogen, 2, Chlorine, 2)
                .iconSet(DULL)
                .buildAndRegister();

        DimethylSulfide = new Material.Builder(GTCEu.id("dimethyl_sulfide"))
                .fluid()
                .color(0xa02e06)
                .components(Carbon, 2, Hydrogen, 6, Sulfur, 1)
                .iconSet(DULL)
                .buildAndRegister();

        CosmicMesh = new Material.Builder(GTCEu.id("cosmic_mesh"))
                .plasma()
                .fluid()
                .color(0x181878)
                .element(GTLElements.COSMIC_MESH)
                .iconSet(DULL)
                .buildAndRegister();

        HydrobromicAcid = new Material.Builder(GTCEu.id("hydrobromic_acid"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .components(Hydrogen, 1, Bromine, 1)
                .color(0xa2573f)
                .iconSet(DULL)
                .buildAndRegister();

        BenzophenanthrenylAcetonitrile = new Material.Builder(
                GTCEu.id("benzophenanthrenylacetonitrile"))
                .dust()
                .components(Carbon, 20, Hydrogen, 13, Nitrogen, 1)
                .color(0x9222c7)
                .iconSet(DULL)
                .buildAndRegister();

        BromoSuccinamide = new Material.Builder(GTCEu.id("bromo_succinimide"))
                .dust()
                .components(Carbon, 4, Hydrogen, 4, Bromine, 1, Nitrogen, 1, Oxygen, 2)
                .color(0x546a21)
                .iconSet(DULL)
                .buildAndRegister();

        PotassiumBromide = new Material.Builder(GTCEu.id("potassium_bromide"))
                .dust()
                .components(Potassium, 1, Bromine, 1)
                .color(0xa34a76)
                .iconSet(GEM_HORIZONTAL)
                .buildAndRegister();

        Succinimide = new Material.Builder(GTCEu.id("succinimide"))
                .dust()
                .components(Carbon, 4, Hydrogen, 5, Nitrogen, 1, Oxygen, 2)
                .color(0x21a7c5)
                .iconSet(DULL)
                .buildAndRegister();

        FissionedUranium235 = new Material.Builder(GTCEu.id("fissioned_uranium_235"))
                .dust()
                .color(0x39c9b7)
                .iconSet(METALLIC)
                .buildAndRegister();

        FranciumCaesiumCadmiumBromide = new Material.Builder(
                GTCEu.id("francium_caesium_cadmium_bromide"))
                .dust()
                .components(Francium, 1, Caesium, 1, Cadmium, 2, Bromine, 6)
                .color(0xa34d1a)
                .iconSet(BRIGHT)
                .buildAndRegister();

        StrontiumEuropiumAluminate = new Material.Builder(
                GTCEu.id("strontium_europium_aluminate"))
                .dust()
                .components(Strontium, 1, Europium, 1, Aluminium, 2, Oxygen, 4)
                .color(0xb62d78)
                .iconSet(BRIGHT)
                .buildAndRegister();

        UraniumSulfateWasteSolution = new Material.Builder(
                GTCEu.id("uranium_sulfate_waste_solution"))
                .fluid()
                .color(0xb1b113)
                .iconSet(DULL)
                .buildAndRegister();

        DibismuthHydroborate = new Material.Builder(GTCEu.id("dibismuthhydroborat"))
                .dust()
                .components(Bismuth, 2, Hydrogen, 1, Boron, 1)
                .color(0x007d32)
                .iconSet(BRIGHT)
                .buildAndRegister();

        CircuitCompound = new Material.Builder(GTCEu.id("circuit_compound"))
                .dust()
                .components(IndiumGalliumPhosphide, 1, DibismuthHydroborate, 3, BismuthTellurite, 2)
                .color(0x00210e)
                .iconSet(BRIGHT)
                .buildAndRegister();

        CaesiumIodide = new Material.Builder(GTCEu.id("caesium_iodide"))
                .dust()
                .components(Caesium, 1, Iodine, 1)
                .color(0xeeeee2)
                .iconSet(DULL)
                .buildAndRegister();

        ThalliumThuliumDopedCaesiumIodide = new Material.Builder(
                GTCEu.id("thallium_thulium_doped_caesium_iodide"))
                .dust()
                .color(0xe8b97f)
                .iconSet(BRIGHT)
                .buildAndRegister();

        Photoresist = new Material.Builder(GTCEu.id("photoresist"))
                .fluid()
                .color(0x2f4f4f)
                .iconSet(BRIGHT)
                .buildAndRegister();

        EuvPhotoresist = new Material.Builder(GTCEu.id("euv_photoresist"))
                .fluid()
                .color(0x4b0082)
                .iconSet(BRIGHT)
                .buildAndRegister();

        GammaRaysPhotoresist = new Material.Builder(GTCEu.id("gamma_rays_photoresist"))
                .fluid()
                .color(0x556b2f)
                .iconSet(BRIGHT)
                .buildAndRegister();

        AcrylicAcid = new Material.Builder(GTCEu.id("acrylic_acid"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .color(0xb41558)
                .components(Carbon, 3, Hydrogen, 4, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        EthylAcrylate = new Material.Builder(GTCEu.id("ethyl_acrylate"))
                .fluid()
                .color(0x947d15)
                .components(Carbon, 5, Hydrogen, 8, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Trichloroflerane = new Material.Builder(GTCEu.id("trichloroflerane"))
                .fluid()
                .color(0x42145d)
                .components(Flerovium, 1, Chlorine, 3)
                .iconSet(DULL)
                .buildAndRegister();

        BisethylenedithiotetraselenafulvalenePerrhenate = new Material.Builder(
                GTCEu.id("bisethylenedithiotetraselenafulvalene_perrhenate"))
                .dust()
                .color(0x72cb00)
                .components(Rhenium, 1, Carbon, 10, Hydrogen, 8, Sulfur, 4, Selenium, 4, Oxygen, 4)
                .iconSet(BRIGHT)
                .buildAndRegister();

        Bisethylenedithiotetraselenafulvalene = new Material.Builder(
                GTCEu.id("bisethylenedithiotetraselenafulvalene"))
                .dust()
                .color(0x72cb00)
                .components(Carbon, 10, Hydrogen, 8, Sulfur, 4, Selenium, 4)
                .iconSet(BRIGHT)
                .buildAndRegister();

        LithiumThiinediselenide = new Material.Builder(GTCEu.id("lithiumthiinediselenide"))
                .dust()
                .color(0x72cb00)
                .components(Carbon, 4, Hydrogen, 4, Sulfur, 2, Lithium, 2, Selenium, 2)
                .iconSet(DULL)
                .buildAndRegister();

        CyclopentadienylTitaniumTrichloride = new Material.Builder(
                GTCEu.id("cyclopentadienyl_titanium_trichloride"))
                .dust()
                .color(0xb22db2)
                .components(Carbon, 10, Hydrogen, 10, Chlorine, 2, Titanium, 1)
                .iconSet(BRIGHT)
                .buildAndRegister();

        ButylLithium = new Material.Builder(GTCEu.id("butyl_lithium"))
                .fluid()
                .color(0x9f6af6)
                .components(Carbon, 4, Hydrogen, 9, Lithium, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Bromodihydrothiine = new Material.Builder(GTCEu.id("bromodihydrothiine"))
                .fluid()
                .color(0x50c44d)
                .components(Carbon, 4, Hydrogen, 4, Sulfur, 2, Bromine, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Dibromoacrolein = new Material.Builder(GTCEu.id("dibromoacrolein"))
                .fluid()
                .color(0x3e3e3e)
                .components(Carbon, 2, Hydrogen, 2, Bromine, 2, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumThiosulfate = new Material.Builder(GTCEu.id("sodium_thiosulfate"))
                .dust()
                .color(0x145a9d)
                .components(Sodium, 2, Sulfur, 2, Oxygen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        LithiumFluoride = new Material.Builder(GTCEu.id("lithium_fluoride"))
                .dust()
                .color(0x6d6d6d)
                .components(Lithium, 1, Fluorine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        HighPurityCalciumCarbonate = new Material.Builder(
                GTCEu.id("high_purity_calcium_carbonate"))
                .dust()
                .color(0xeeeee0)
                .components(Calcium, 1, Carbon, 1, Oxygen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        Bromobutane = new Material.Builder(GTCEu.id("bromobutane"))
                .fluid()
                .color(0xff0c0c)
                .components(Carbon, 4, Hydrogen, 9, Bromine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Propadiene = new Material.Builder(GTCEu.id("propadiene"))
                .fluid()
                .color(0x323b0a)
                .components(Carbon, 3, Hydrogen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        AstatideSolution = new Material.Builder(GTCEu.id("astatide_solution"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .color(0x4ff417)
                .components(Astatine, 1, SulfuricAcid, 1)
                .iconSet(DULL)
                .buildAndRegister();

        MixedAstatideSalts = new Material.Builder(GTCEu.id("mixed_astatide_salts"))
                .dust()
                .color(0x67e83c)
                .components(Holmium, 1, Thulium, 1, Copernicium, 1, Flerovium, 1, Astatine, 3)
                .iconSet(DULL)
                .buildAndRegister();

        BoronFranciumCarbide = new Material.Builder(GTCEu.id("boron_francium_carbide"))
                .dust()
                .color(0x797979)
                .components(Francium, 4, Boron, 4, Carbon, 7)
                .iconSet(DULL)
                .buildAndRegister();

        Borocarbide = new Material.Builder(GTCEu.id("borocarbide"))
                .dust()
                .color(0x7e7e22)
                .components(MixedAstatideSalts, 1, BoronFranciumCarbide, 1)
                .iconSet(DULL)
                .buildAndRegister();

        FranciumCarbide = new Material.Builder(GTCEu.id("francium_carbide"))
                .dust()
                .color(0xa1a1a1)
                .components(Francium, 2, Carbon, 2)
                .iconSet(DULL)
                .buildAndRegister();

        BoronCarbide = new Material.Builder(GTCEu.id("boron_carbide"))
                .dust()
                .color(0x1e1e1e)
                .components(Boron, 4, Carbon, 3)
                .iconSet(DULL)
                .buildAndRegister();

        LanthanumEmbeddedFullerene = new Material.Builder(
                GTCEu.id("lanthanum_embedded_fullerene"))
                .dust()
                .color(0x91c100)
                .components(Lanthanum, 1, Fullerene, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LanthanumFullereneMix = new Material.Builder(GTCEu.id("lanthanum_fullerene_mix"))
                .dust()
                .color(0xd3bfec)
                .components(Lanthanum, 1, UnfoldedFullerene, 1)
                .iconSet(DULL)
                .buildAndRegister();

        CaliforniumTrichloride = new Material.Builder(GTCEu.id("californium_trichloride"))
                .dust()
                .color(0x286224)
                .components(Californium, 1, Chlorine, 3)
                .iconSet(DULL)
                .buildAndRegister();

        FullereneDopedNanotubes = new Material.Builder(GTCEu.id("fullerene_doped_nanotubes"))
                .fluid()
                .color(0x562356)
                .iconSet(DULL)
                .buildAndRegister();

        CaliforniumCyclopentadienide = new Material.Builder(
                GTCEu.id("californium_cyclopentadienide"))
                .fluid()
                .color(0x78374a)
                .components(Carbon, 15, Hydrogen, 15, Californium, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Cyclopentadiene = new Material.Builder(GTCEu.id("cyclopentadiene"))
                .fluid()
                .color(0x2aa62a)
                .components(Carbon, 5, Hydrogen, 6)
                .iconSet(DULL)
                .buildAndRegister();

        LithiumCyclopentadienide = new Material.Builder(GTCEu.id("lithium_cyclopentadienide"))
                .fluid()
                .color(0x7b4657)
                .components(Lithium, 1, Carbon, 5, Hydrogen, 5)
                .iconSet(DULL)
                .buildAndRegister();

        Dimethylether = new Material.Builder(GTCEu.id("dimethylether"))
                .fluid()
                .color(0xbda90e)
                .components(Carbon, 2, Hydrogen, 6, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Dimethoxyethane = new Material.Builder(GTCEu.id("dimethoxyethane"))
                .fluid()
                .color(0x23a996)
                .components(Carbon, 4, Hydrogen, 10, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Photopolymer = new Material.Builder(GTCEu.id("photopolymer"))
                .fluid()
                .color(0x73445b)
                .components(Carbon, 149, Hydrogen, 97, Nitrogen, 10, Oxygen, 2, Titanium, 1,
                        Boron, 1, Fluorine, 20)
                .iconSet(DULL)
                .buildAndRegister();

        SilverPerchlorate = new Material.Builder(GTCEu.id("silver_perchlorate"))
                .dust()
                .color(0xededed)
                .components(Silver, 1, Chlorine, 1, Oxygen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        SilverChloride = new Material.Builder(GTCEu.id("silver_chloride"))
                .dust()
                .color(0x8d8d8d)
                .components(Silver, 1, Chlorine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumBromide = new Material.Builder(GTCEu.id("sodium_bromide"))
                .dust()
                .color(0xc588c4)
                .components(Sodium, 1, Bromine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        SilverOxide = new Material.Builder(GTCEu.id("silver_oxide"))
                .dust()
                .color(0x2b2b2b)
                .components(Silver, 2, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        PhthalicAnhydride = new Material.Builder(GTCEu.id("phthalic_anhydride"))
                .dust()
                .color(0x8c8c8c)
                .components(Carbon, 8, Hydrogen, 4, Oxygen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumHypochlorite = new Material.Builder(GTCEu.id("sodium_hypochlorite"))
                .dust()
                .color(0x66f14c)
                .components(Sodium, 1, Chlorine, 1, Oxygen, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Ethylanthraquinone = new Material.Builder(GTCEu.id("ethylanthraquinone"))
                .fluid()
                .color(0xffff24)
                .components(Carbon, 16, Hydrogen, 12, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Ethylanthrahydroquinone = new Material.Builder(GTCEu.id("ethylanthrahydroquinone"))
                .fluid()
                .color(0xcece00)
                .components(Ethylanthraquinone, 1, Hydrogen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Anthracene = new Material.Builder(GTCEu.id("anthracene"))
                .fluid()
                .color(0x929e92)
                .components(Carbon, 14, Hydrogen, 10)
                .iconSet(DULL)
                .buildAndRegister();

        Phenylsodium = new Material.Builder(GTCEu.id("phenylsodium"))
                .fluid()
                .color(0x2626ab)
                .components(Carbon, 6, Hydrogen, 5, Sodium, 1)
                .iconSet(DULL)
                .buildAndRegister();

        NDifluorophenylpyrrole = new Material.Builder(GTCEu.id("n_difluorophenylpyrrole"))
                .fluid()
                .color(0x2f7e8a)
                .components(Carbon, 10, Hydrogen, 7, Fluorine, 2, Nitrogen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Difluoroaniline = new Material.Builder(GTCEu.id("difluoroaniline"))
                .fluid()
                .color(0x348f3e)
                .components(Carbon, 6, Hydrogen, 5, Fluorine, 2, Nitrogen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Succinaldehyde = new Material.Builder(GTCEu.id("succinaldehyde"))
                .fluid()
                .color(0x63577d)
                .components(Carbon, 4, Hydrogen, 6, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        TetraethylammoniumBromide = new Material.Builder(GTCEu.id("tetraethylammonium_bromide"))
                .fluid()
                .color(0xc20cff)
                .components(Carbon, 8, Hydrogen, 20, Nitrogen, 1, Bromine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        RhodiumRheniumNaquadahCatalyst = new Material.Builder(
                GTCEu.id("rhodium_rhenium_naquadah_catalyst"))
                .dust()
                .color(0x944648)
                .components(Rhodium, 1, Rhenium, 1, Naquadah, 1)
                .iconSet(DULL)
                .buildAndRegister();

        IodineMonochloride = new Material.Builder(GTCEu.id("iodine_monochloride"))
                .fluid()
                .color(0x004141)
                .components(Iodine, 1, Chlorine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Dimethylnaphthalene = new Material.Builder(GTCEu.id("dimethylnaphthalene"))
                .fluid()
                .color(0xde2da1)
                .components(Carbon, 12, Hydrogen, 12)
                .iconSet(DULL)
                .buildAndRegister();

        Dihydroiodotetracene = new Material.Builder(GTCEu.id("dihydroiodotetracene"))
                .fluid()
                .color(0xde2da1)
                .components(Carbon, 18, Hydrogen, 13, Iodine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        AcetylatingReagent = new Material.Builder(GTCEu.id("acetylating_reagent"))
                .fluid()
                .color(0x724c50)
                .components(Carbon, 9, Hydrogen, 12, Silicon, 1, Magnesium, 2, Bromine, 2)
                .iconSet(DULL)
                .buildAndRegister();

        MagnesiumChlorideBromide = new Material.Builder(GTCEu.id("magnesium_chloride_bromide"))
                .dust()
                .color(0x99234d)
                .components(Magnesium, 1, Chlorine, 1, Bromine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        IsopropylAlcohol = new Material.Builder(GTCEu.id("isopropyl_alcohol"))
                .fluid()
                .color(0x51b31f)
                .components(Carbon, 3, Hydrogen, 8, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        Dichlorodicyanobenzoquinone = new Material.Builder(
                GTCEu.id("dichlorodicyanobenzoquinone"))
                .fluid()
                .color(0x302399)
                .components(Carbon, 8, Chlorine, 2, Nitrogen, 2, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Dichlorodicyanohydroquinone = new Material.Builder(
                GTCEu.id("dichlorodicyanohydroquinone"))
                .fluid()
                .color(0x302399)
                .components(Carbon, 8, Chlorine, 2, Nitrogen, 2, Oxygen, 2, Hydrogen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Tetracene = new Material.Builder(GTCEu.id("tetracene"))
                .dust()
                .color(0x8f7718)
                .components(Carbon, 18, Hydrogen, 12)
                .iconSet(DULL)
                .buildAndRegister();

        PolycyclicAromaticMixture = new Material.Builder(GTCEu.id("polycyclic_aromatic_mixture"))
                .dust()
                .color(0x8f7718)
                .components(Carbon, 18, Hydrogen, 12)
                .iconSet(DULL)
                .buildAndRegister();

        RheniumHassiumThalliumIsophtaloylbisdiethylthioureaHexaf = new Material.Builder(
                GTCEu.id("rhenium_hassium_thallium_isophtaloylbisdiethylthiourea_hexaf"))
                .dust()
                .color(0x8f7718)
                .components(Rhenium, 1, Hassium, 1, Thallium, 1, Carbon, 60, Phosphorus, 1,
                        Nitrogen, 12, Hydrogen, 84, Sulfur, 6, Oxygen, 12, Fluorine, 6)
                .iconSet(DULL)
                .buildAndRegister();

        ThalliumChloride = new Material.Builder(GTCEu.id("thallium_chloride"))
                .dust()
                .color(0xcc5350)
                .components(Thallium, 1, Chlorine, 1)
                .iconSet(DULL)
                .buildAndRegister();

        HassiumChloride = new Material.Builder(GTCEu.id("hassium_chloride"))
                .dust()
                .color(0x5828b2)
                .components(Hassium, 1, Chlorine, 4)
                .iconSet(DULL)
                .buildAndRegister();

        RheniumChloride = new Material.Builder(GTCEu.id("rhenium_chloride"))
                .dust()
                .color(0x392857)
                .components(Rhenium, 1, Chlorine, 5)
                .iconSet(DULL)
                .buildAndRegister();

        IsophthaloylBis = new Material.Builder(GTCEu.id("isophthaloylbis"))
                .fluid()
                .color(0x76677e)
                .components(Carbon, 18, Hydrogen, 26, Nitrogen, 4, Oxygen, 2, Sulfur, 2)
                .iconSet(DULL)
                .buildAndRegister();

        HexafluorophosphoricAcid = new Material.Builder(GTCEu.id("hexafluorophosphoric_acid"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .color(0xd5d54b)
                .components(Hydrogen, 1, Phosphorus, 1, Fluorine, 6)
                .iconSet(DULL)
                .buildAndRegister();

        Diethylthiourea = new Material.Builder(GTCEu.id("diethylthiourea"))
                .fluid()
                .color(0x23a687)
                .components(Carbon, 5, Hydrogen, 12, Nitrogen, 2, Sulfur, 1)
                .iconSet(DULL)
                .buildAndRegister();

        ThionylChloride = new Material.Builder(GTCEu.id("thionyl_chloride"))
                .fluid()
                .color(0xf8f5e0)
                .components(Sulfur, 1, Oxygen, 1, Chlorine, 2)
                .iconSet(DULL)
                .buildAndRegister();

        PhenylenedioxydiaceticAcid = new Material.Builder(
                GTCEu.id("phenylenedioxydiacetic_acid"))
                .liquid(new FluidBuilder().attribute(FluidAttributes.ACID))
                .color(0x7c4456)
                .components(Carbon, 10, Hydrogen, 10, Oxygen, 6)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumThiocyanate = new Material.Builder(GTCEu.id("sodium_thiocyanate"))
                .fluid()
                .color(0x7c4456)
                .components(Sodium, 1, Sulfur, 1, Carbon, 1, Nitrogen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        PhosphorusTrichloride = new Material.Builder(GTCEu.id("phosphorus_trichloride"))
                .fluid()
                .color(0xd5d54b)
                .components(Phosphorus, 1, Chlorine, 3)
                .iconSet(DULL)
                .buildAndRegister();

        AntimonyPentafluoride = new Material.Builder(GTCEu.id("antimony_pentafluoride"))
                .fluid()
                .color(0xd5d5bd)
                .components(Antimony, 1, Fluorine, 5)
                .iconSet(DULL)
                .buildAndRegister();

        AntimonyTrichloride = new Material.Builder(GTCEu.id("antimony_trichloride"))
                .dust()
                .color(0xbcbcbc)
                .components(Antimony, 1, Chlorine, 3)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ChargedCaesiumCeriumCobaltIndium = new Material.Builder(
                GTCEu.id("charged_caesium_cerium_cobalt_indium"))
                .dust()
                .color(0x4da323)
                .components(Caesium, 1, Cerium, 1, Cobalt, 2, Indium, 10, CosmicComputingMixture, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ActiniumSuperhydride = new Material.Builder(GTCEu.id("actinium_superhydride"))
                .plasma()
                .dust()
                .color(0x52b051)
                .components(Actinium, 1, Hydrogen, 12)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CosmicSuperconductor = new Material.Builder(GTCEu.id("cosmic_superconductor"))
                .fluid()
                .color(0x00ffff)
                .components(RheniumHassiumThalliumIsophtaloylbisdiethylthioureaHexaf, 1,
                        ActiniumSuperhydride, 1, ChargedCaesiumCeriumCobaltIndium, 1)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Ethylamine = new Material.Builder(GTCEu.id("ethylamine"))
                .fluid()
                .color(0x5a656d)
                .components(Carbon, 2, Hydrogen, 5, Nitrogen, 1, Hydrogen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        TolueneDiisocyanate = new Material.Builder(GTCEu.id("toluene_diisocyanate"))
                .fluid()
                .color(0xacf4bf)
                .components(Carbon, 9, Hydrogen, 6, Nitrogen, 2, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Polyurethane = new Material.Builder(GTCEu.id("polyurethane"))
                .fluid()
                .color(0xacf4bf)
                .components(Carbon, 17, Hydrogen, 16, Nitrogen, 2, Oxygen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        ViscoelasticPolyurethane = new Material.Builder(GTCEu.id("viscoelastic_polyurethane"))
                .fluid()
                .color(0xecfbec)
                .components(Polyurethane, 1, EthyleneGlycol, 1, Calcite, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ViscoelasticPolyurethaneFoam = new Material.Builder(
                GTCEu.id("viscoelastic_polyurethane_foam"))
                .fluid()
                .color(0xecfbec)
                .components(ViscoelasticPolyurethane, 1, Air, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Glucose = new Material.Builder(GTCEu.id("glucose"))
                .dust()
                .color(0xcacace)
                .components(Carbon, 6, Hydrogen, 12, Oxygen, 6)
                .iconSet(DULL)
                .buildAndRegister();

        GlucoseIronSolution = new Material.Builder(GTCEu.id("glucose_iron_solution"))
                .fluid()
                .color(0xc9c9c9)
                .components(Glucose, 1, Iron3Chloride, 1)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GrapheneOxide = new Material.Builder(GTCEu.id("graphene_oxide"))
                .dust()
                .color(0x535353)
                .components(Carbon, 1, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        GrapheneGelSuspension = new Material.Builder(GTCEu.id("graphene_gel_suspension"))
                .dust()
                .color(0x535353)
                .components(Carbon, 1)
                .iconSet(DULL)
                .buildAndRegister();

        DryGrapheneGel = new Material.Builder(GTCEu.id("dry_graphene_gel"))
                .dust()
                .color(0x202079)
                .components(Carbon, 1)
                .iconSet(DULL)
                .buildAndRegister();

        SupercriticalCarbonDioxide = new Material.Builder(
                GTCEu.id("supercritical_carbon_dioxide"))
                .fluid()
                .color(0x9ac8f3)
                .components(Carbon, 1, Oxygen, 2)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        PotassiumBisulfite = new Material.Builder(GTCEu.id("potassium_bisulfite"))
                .dust()
                .color(0x807d72)
                .components(Potassium, 1, Sulfur, 1, Hydrogen, 1, Oxygen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        PotassiumHydroxylaminedisulfonate = new Material.Builder(
                GTCEu.id("potassium_hydroxylaminedisulfonate"))
                .dust()
                .color(0x4c6226)
                .components(Potassium, 2, Nitrogen, 1, Hydrogen, 1, Sulfur, 2, Oxygen, 7)
                .iconSet(DULL)
                .buildAndRegister();

        HydroxylammoniumSulfate = new Material.Builder(GTCEu.id("hydroxylammonium_sulfate"))
                .dust()
                .color(0x848075)
                .components(Nitrogen, 2, Hydrogen, 8, Sulfur, 1, Oxygen, 6)
                .iconSet(DULL)
                .buildAndRegister();

        BariumChloride = new Material.Builder(GTCEu.id("barium_chloride"))
                .dust()
                .color(0xe9705f)
                .components(Barium, 1, Chlorine, 2)
                .iconSet(DULL)
                .buildAndRegister();

        NitrousAcid = new Material.Builder(GTCEu.id("nitrous_acid"))
                .fluid()
                .color(0xa0c8fd)
                .components(Hydrogen, 1, Nitrogen, 1, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        ActiniumHydride = new Material.Builder(GTCEu.id("atinium_hydride"))
                .dust()
                .color(0xb8c6f1)
                .components(Actinium, 1, Hydrogen, 3)
                .iconSet(DULL)
                .buildAndRegister();

        GradePurifiedWater1 = new Material.Builder(GTCEu.id("grade_1_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater2 = new Material.Builder(GTCEu.id("grade_2_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater3 = new Material.Builder(GTCEu.id("grade_3_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater4 = new Material.Builder(GTCEu.id("grade_4_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater5 = new Material.Builder(GTCEu.id("grade_5_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater6 = new Material.Builder(GTCEu.id("grade_6_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater7 = new Material.Builder(GTCEu.id("grade_7_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater8 = new Material.Builder(GTCEu.id("grade_8_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater9 = new Material.Builder(GTCEu.id("grade_9_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater10 = new Material.Builder(GTCEu.id("grade_10_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater11 = new Material.Builder(GTCEu.id("grade_11_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater12 = new Material.Builder(GTCEu.id("grade_12_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater13 = new Material.Builder(GTCEu.id("grade_13_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater14 = new Material.Builder(GTCEu.id("grade_14_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater15 = new Material.Builder(GTCEu.id("grade_15_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GradePurifiedWater16 = new Material.Builder(GTCEu.id("grade_16_purified_water"))
                .fluid()
                .components(Water, 1)
                .color(0x0058cd)
                .iconSet(FLUID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Antimatter = new Material.Builder(GTCEu.id("antimatter"))
                .fluid()
                .color(0x9932cc)
                .iconSet(DULL)
                .buildAndRegister();

        PositiveElectron = new Material.Builder(GTCEu.id("positive_electron"))
                .fluid()
                .color(0x59764c)
                .iconSet(DULL)
                .buildAndRegister().setFormula("e+");

        Antiproton = new Material.Builder(GTCEu.id("antiproton"))
                .fluid()
                .color(0x4945af)
                .iconSet(DULL)
                .buildAndRegister().setFormula("p-");

        Antineutron = new Material.Builder(GTCEu.id("antineutron"))
                .fluid()
                .color(0xe6e6fa)
                .iconSet(DULL)
                .buildAndRegister().setFormula("n-bar");

        Antihydrogen = new Material.Builder(GTCEu.id("antihydrogen"))
                .fluid()
                .color(0x6a5acd)
                .iconSet(DULL)
                .buildAndRegister().setFormula("Ah");

        Kerosene = new Material.Builder(GTCEu.id("kerosene"))
                .fluid()
                .components(Carbon, 12, Hydrogen, 24)
                .color(0xce57ce)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Rp1 = new Material.Builder(GTCEu.id("rp_1"))
                .fluid()
                .color(0xff523e)
                .iconSet(DULL)
                .buildAndRegister();

        RocketFuelRp1 = new Material.Builder(GTCEu.id("rocket_fuel_rp_1"))
                .fluid()
                .color(0xff321b)
                .iconSet(DULL)
                .buildAndRegister();

        Hydrazine = new Material.Builder(GTCEu.id("hydrazine"))
                .fluid()
                .color(0xffffff)
                .components(Nitrogen, 2, Hydrogen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        DenseHydrazineFuelMixture = new Material.Builder(
                GTCEu.id("dense_hydrazine_fuel_mixture"))
                .fluid()
                .color(0x4a223b)
                .iconSet(DULL)
                .buildAndRegister();

        Monomethylhydrazine = new Material.Builder(GTCEu.id("monomethylhydrazine"))
                .fluid()
                .color(0xffffff)
                .components(Carbon, 1, Hydrogen, 6, Nitrogen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        RocketFuelCn3h7o3 = new Material.Builder(GTCEu.id("rocket_fuel_cn3h7o3"))
                .fluid()
                .color(0xa636ac)
                .iconSet(DULL)
                .buildAndRegister();

        RocketFuelH8n4c2o4 = new Material.Builder(GTCEu.id("rocket_fuel_h8n4c2o4"))
                .fluid()
                .color(0x4aa11b)
                .iconSet(DULL)
                .buildAndRegister();

        StellarEnergyRocketFuel = new Material.Builder(GTCEu.id("stellar_energy_rocket_fuel"))
                .fluid()
                .color(0xdf362d)
                .iconSet(DULL)
                .buildAndRegister();

        ExplosiveHydrazine = new Material.Builder(GTCEu.id("explosivehydrazine"))
                .fluid()
                .color(0x3b0c5c)
                .iconSet(DULL)
                .buildAndRegister();

        HmxExplosive = new Material.Builder(GTCEu.id("hmxexplosive"))
                .dust()
                .color(0xf3ffdb)
                .iconSet(BRIGHT)
                .buildAndRegister();

        Desh = new Material.Builder(GTCEu.id("desh"))
                .dust()
                .ore()
                .color(0xf2a057)
                .iconSet(METALLIC)
                .flags(EXCLUDE_BLOCK_CRAFTING_BY_HAND_RECIPES)
                .buildAndRegister();

        Ostrum = new Material.Builder(GTCEu.id("ostrum"))
                .dust()
                .ore()
                .color(0xe5939b)
                .iconSet(METALLIC)
                .flags(EXCLUDE_BLOCK_CRAFTING_BY_HAND_RECIPES)
                .buildAndRegister();

        Calorite = new Material.Builder(GTCEu.id("calorite"))
                .dust()
                .ore()
                .color(0xe65757)
                .iconSet(METALLIC)
                .flags(EXCLUDE_BLOCK_CRAFTING_BY_HAND_RECIPES)
                .buildAndRegister();

        LaNdOxidesSolution = new Material.Builder(GTCEu.id("la_nd_oxides_solution"))
                .fluid()
                .color(0x9ce3db)
                .iconSet(DULL)
                .buildAndRegister().setFormula("(La2O3)(Pr2O3)(Nd2O3)(Ce2O3)");

        SmGdOxidesSolution = new Material.Builder(GTCEu.id("sm_gd_oxides_solution"))
                .fluid()
                .color(0xffff99)
                .iconSet(DULL)
                .buildAndRegister().setFormula("(Sc2O3)(Eu2O3)(Gd2O3)(Sm2O3)");

        TbHoOxidesSolution = new Material.Builder(GTCEu.id("tb_ho_oxides_solution"))
                .fluid()
                .color(0x99ff99)
                .iconSet(DULL)
                .buildAndRegister().setFormula("(Y2O3)(Tb2O3)(Dy2O3)(Ho2O3)");

        ErLuOxidesSolution = new Material.Builder(GTCEu.id("er_lu_oxides_solution"))
                .fluid()
                .color(0xffb3ff)
                .iconSet(DULL)
                .buildAndRegister().setFormula("(Er2O3)(Tm2O3)(Yb2O3)(Lu2O3)");

        LanthanumOxide = new Material.Builder(GTCEu.id("lanthanum_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Lanthanum, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        PraseodymiumOxide = new Material.Builder(GTCEu.id("praseodymium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Praseodymium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        NeodymiumOxide = new Material.Builder(GTCEu.id("neodymium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Neodymium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CeriumOxide = new Material.Builder(GTCEu.id("cerium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Cerium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        EuropiumOxide = new Material.Builder(GTCEu.id("europium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Europium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GadoliniumOxide = new Material.Builder(GTCEu.id("gadolinium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Gadolinium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SamariumOxide = new Material.Builder(GTCEu.id("samarium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Samarium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TerbiumOxide = new Material.Builder(GTCEu.id("terbium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Terbium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DysprosiumOxide = new Material.Builder(GTCEu.id("dysprosium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Dysprosium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HolmiumOxide = new Material.Builder(GTCEu.id("holmium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Holmium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ErbiumOxide = new Material.Builder(GTCEu.id("erbium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Erbium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ThuliumOxide = new Material.Builder(GTCEu.id("thulium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Thulium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        YtterbiumOxide = new Material.Builder(GTCEu.id("ytterbium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Ytterbium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LutetiumOxide = new Material.Builder(GTCEu.id("lutetium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Lutetium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ScandiumOxide = new Material.Builder(GTCEu.id("scandium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Scandium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        YttriumOxide = new Material.Builder(GTCEu.id("yttrium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Yttrium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        PromethiumOxide = new Material.Builder(GTCEu.id("promethium_oxide"))
                .dust()
                .color(0xcfcfcf)
                .components(Promethium, 2, Oxygen, 3)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ZirconChlorinatingResidue = new Material.Builder(GTCEu.id("zircon_chlorinating_residue"))
                .fluid()
                .color(0x33ca33)
                .components(Silicon, 1, Chlorine, 4)
                .iconSet(DULL)
                .buildAndRegister();

        ZirconiumHafniumChloride = new Material.Builder(GTCEu.id("zirconium_hafnium_chloride"))
                .fluid()
                .color(0x33ca33)
                .iconSet(DULL)
                .buildAndRegister().setFormula("ZrHfCl₄");

        ZirconiumHafniumOxychloride = new Material.Builder(
                GTCEu.id("zirconiu_hafnium_oxychloride"))
                .fluid()
                .color(0x33ca33)
                .iconSet(DULL)
                .buildAndRegister().setFormula("Cl₂HfOZr");

        HafniumOxide = new Material.Builder(GTCEu.id("hafnium_oxide"))
                .dust()
                .color(0x3c3c3c)
                .components(Hafnium, 1, Oxygen, 2)
                .iconSet(GLASS)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ZirconiumOxide = new Material.Builder(GTCEu.id("zirconium_oxide"))
                .dust()
                .color(0x3c3c3c)
                .components(Zirconium, 1, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        HafniumChloride = new Material.Builder(GTCEu.id("hafnium_chloride"))
                .dust()
                .color(0x5c5c69)
                .components(Hafnium, 1, Chlorine, 4)
                .iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TelluriumOxide = new Material.Builder(GTCEu.id("tellurium_oxide"))
                .dust()
                .color(0xd0d0d0)
                .components(Tellurium, 1, Oxygen, 2)
                .iconSet(GLASS)
                .buildAndRegister();

        SodiumEthylate = new Material.Builder(GTCEu.id("sodium_ethylate"))
                .dust()
                .color(0x9bcd9b)
                .components(Carbon, 2, Hydrogen, 5, Oxygen, 1, Sodium, 1)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumEthylxanthate = new Material.Builder(GTCEu.id("sodium_ethylxanthate"))
                .dust()
                .color(0xcdad00)
                .components(Carbon, 3, Hydrogen, 5, Sodium, 1, Oxygen, 1, Sulfur, 2)
                .iconSet(DULL)
                .buildAndRegister();

        PotassiumEthylxanthate = new Material.Builder(GTCEu.id("potassium_ethylxanthate"))
                .dust()
                .color(0xcdc8b1)
                .components(Carbon, 3, Hydrogen, 5, Potassium, 1, Oxygen, 1, Sulfur, 2)
                .iconSet(DULL)
                .buildAndRegister();

        PotassiumEthylate = new Material.Builder(GTCEu.id("potassium_ethylate"))
                .dust()
                .color(0xcd661d)
                .components(Carbon, 2, Hydrogen, 5, Oxygen, 1, Potassium, 1)
                .iconSet(DULL)
                .buildAndRegister();

        NMPyrolidone = new Material.Builder(GTCEu.id("nmethylpyrolidone"))
                .fluid()
                .color(0xd0d0d0)
                .components(Carbon, 5, Hydrogen, 9, Nitrogen, 1, Oxygen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        GammaButyrolactone = new Material.Builder(GTCEu.id("gammabutyrolactone"))
                .fluid()
                .color(0xcccca1)
                .components(Carbon, 4, Hydrogen, 6, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Butane14Diol = new Material.Builder(GTCEu.id("butane_1_4_diol"))
                .fluid()
                .color(0xc4534c)
                .components(Carbon, 4, Hydrogen, 10, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Methylamine = new Material.Builder(GTCEu.id("methylamine"))
                .fluid()
                .color(0x45486f)
                .components(Carbon, 1, Hydrogen, 5, Nitrogen, 1)
                .iconSet(DULL)
                .buildAndRegister();

        PPhenylenediamine = new Material.Builder(GTCEu.id("p_phenylenediamine"))
                .dust()
                .color(0xdccf52)
                .components(Carbon, 6, Hydrogen, 8, Nitrogen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        PNitroaniline = new Material.Builder(GTCEu.id("p_nitroaniline"))
                .fluid()
                .color(0xcc9037)
                .components(Carbon, 6, Hydrogen, 8, Nitrogen, 2, Oxygen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        TerephthalicAcid = new Material.Builder(GTCEu.id("terephthalicacid"))
                .fluid()
                .color(0xd6d6d6)
                .components(Carbon, 8, Hydrogen, 6, Hydrogen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        DimethylTerephthalate = new Material.Builder(GTCEu.id("dimethylterephthalate"))
                .fluid()
                .color(0xd1d1d1)
                .components(Carbon, 10, Hydrogen, 10, Hydrogen, 4)
                .iconSet(DULL)
                .buildAndRegister();

        TerephthaloylChloride = new Material.Builder(GTCEu.id("terephthaloyl_chloride"))
                .dust()
                .color(0x00e60b)
                .components(Carbon, 8, Hydrogen, 4, Chlorine, 2, Nitrogen, 2)
                .iconSet(DULL)
                .buildAndRegister();

        Rhugnor = new Material.Builder(GTCEu.id("rhugnor"))
                .fluid()
                .color(0xa800e2)
                .element(GTLElements.RHUGNOR)
                .iconSet(METALLIC)
                .buildAndRegister();

        Force = new Material.Builder(GTCEu.id("force"))
                .dust()
                .fluid()
                .ore()
                .addOreByproducts(Lanthanum)
                .color(0xdede00)
                .iconSet(BRIGHT)
                .buildAndRegister();

        Tartarite = new Material.Builder(GTCEu.id("tartarite"))
                .dust()
                .fluid()
                .ore()
                .addOreByproducts(Americium)
                .color(0xd36232)
                .iconSet(BRIGHT)
                .buildAndRegister();

        HotSodiumPotassium = new Material.Builder(GTCEu.id("hot_sodium_potassium"))
                .fluid()
                .color(0x64fcb4)
                .components(Sodium, 1, Potassium, 1)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(SAND)
                .buildAndRegister();

        SupercriticalSodiumPotassium = new Material.Builder(
                GTCEu.id("supercritical_sodium_potassium"))
                .fluid()
                .color(0x64fcb4)
                .components(Sodium, 1, Potassium, 1)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(LAPIS)
                .buildAndRegister();

        Copper76 = new Material.Builder(GTCEu.id("copper76"))
                .dust()
                .element(GTLElements.COPPER76)
                .color(0xe77c56)
                .iconSet(BRIGHT)
                .buildAndRegister();

        CadmiumSulfide = new Material.Builder(GTCEu.id("cadmium_sulfide"))
                .dust()
                .components(Cadmium, 1, Sulfur, 1)
                .color(0xd4ba19)
                .iconSet(DULL)
                .buildAndRegister();

        CadmiumTungstate = new Material.Builder(GTCEu.id("cadmium_tungstate"))
                .dust()
                .components(Cadmium, 1, Tungsten, 1, Oxygen, 4)
                .color(0x757770)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(DULL)
                .buildAndRegister();

        BismuthGermanate = new Material.Builder(GTCEu.id("bismuth_germanate"))
                .dust()
                .components(Bismuth, 12, Germanium, 1, Oxygen, 20)
                .color(0x4ea839)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(DULL)
                .buildAndRegister();

        BismuthNitrateSolution = new Material.Builder(GTCEu.id("bismuth_nitrate_solution"))
                .fluid()
                .components(Water, 1, Bismuth, 1, Nitrogen, 3, Oxygen, 9)
                .color(0xa4a7a8)
                .iconSet(DULL)
                .buildAndRegister();

        Paa = new Material.Builder(GTCEu.id("paa"))
                .fluid()
                .components(Carbon, 22, Hydrogen, 14, Nitrogen, 2, Oxygen, 7)
                .color(0xead05e)
                .iconSet(DULL)
                .buildAndRegister();

        SilicaGelBase = new Material.Builder(GTCEu.id("silica_gel_base"))
                .fluid()
                .color(0x39967a)
                .iconSet(DULL)
                .buildAndRegister();

        DeglyceratedSoap = new Material.Builder(GTCEu.id("deglycerated_soap"))
                .fluid()
                .color(0xffb000)
                .iconSet(DULL)
                .buildAndRegister();

        Turpentine = new Material.Builder(GTCEu.id("turpentine"))
                .fluid()
                .components(Carbon, 10, Hydrogen, 16)
                .color(0x9acd32)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        SteamCrackedTurpentine = new Material.Builder(GTCEu.id("steam_cracked_turpentine"))
                .fluid()
                .color(0x8b6914)
                .iconSet(FLUID)
                .buildAndRegister();

        LeachedTurpentine = new Material.Builder(GTCEu.id("leached_turpentine"))
                .fluid()
                .components(Carbon, 10, Hydrogen, 16, Concrete, 1)
                .color(0xcd9b1d)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        AlmandineFront = new Material.Builder(GTCEu.id("almandine_front"))
                .fluid()
                .components(Almandine, 1)
                .color(0xb22222)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        ChalcopyriteFront = new Material.Builder(GTCEu.id("chalcopyrite_front"))
                .fluid()
                .components(Chalcopyrite, 1)
                .color(0xcdaa7d)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        GrossularFront = new Material.Builder(GTCEu.id("grossular_front"))
                .fluid()
                .components(Grossular, 1)
                .color(0xd2691e)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        MonaziteFront = new Material.Builder(GTCEu.id("monazite_front"))
                .fluid()
                .components(Monazite, 1)
                .color(0x838b83)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        NickelFront = new Material.Builder(GTCEu.id("nickel_front"))
                .fluid()
                .components(Nickel, 1)
                .color(0xc1cdcd)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        PlatinumFront = new Material.Builder(GTCEu.id("platinum_front"))
                .fluid()
                .components(Platinum, 1)
                .color(0xcdc9a5)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        PyropeFront = new Material.Builder(GTCEu.id("pyrope_front"))
                .fluid()
                .components(Pyrope, 1)
                .color(0x8b0000)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        RedstoneFront = new Material.Builder(GTCEu.id("redstone_front"))
                .fluid()
                .components(Redstone, 1)
                .color(0xee0000)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        SpessartineFront = new Material.Builder(GTCEu.id("spessartine_front"))
                .fluid()
                .components(Spessartine, 1)
                .color(0xee5c42)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        SphaleriteFront = new Material.Builder(GTCEu.id("sphalerite_front"))
                .fluid()
                .components(Sphalerite, 1)
                .color(0xeee9e9)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        PentlanditeFront = new Material.Builder(GTCEu.id("pentlandite_front"))
                .fluid()
                .components(Pentlandite, 1)
                .color(0xcdaa7d)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        EnrichedNaquadahFront = new Material.Builder(GTCEu.id("enriched_naquadah_front"))
                .fluid()
                .components(NaquadahEnriched, 1)
                .color(0x58d00f)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        CarbonDisulfide = new Material.Builder(GTCEu.id("carbon_disulfide"))
                .fluid()
                .components(Carbon, 1, Sulfur, 2)
                .color(0x104e8b)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(FLUID)
                .buildAndRegister();

        SpecialCeramics = new Material.Builder(GTCEu.id("special_ceramics"))
                .dust()
                .color(0x5c5909)
                .iconSet(DULL)
                .buildAndRegister();

        HydroiodicAcid = new Material.Builder(GTCEu.id("hydroiodic_acid"))
                .fluid()
                .components(Hydrogen, 1, Iodine, 1)
                .color(0x0382e2)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        Acrylonitrile = new Material.Builder(GTCEu.id("acrylonitrile"))
                .fluid()
                .components(Carbon, 3, Hydrogen, 3, Nitrogen, 1)
                .color(0xa4a4e1)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        LithiumIodide = new Material.Builder(GTCEu.id("lithium_iodide"))
                .dust()
                .components(Lithium, 1, Iodine, 1)
                .color(0xc10014)
                .iconSet(DULL)
                .buildAndRegister();

        SilicaAluminaGel = new Material.Builder(GTCEu.id("silica_alumina_gel"))
                .dust()
                .color(0x0c849f)
                .iconSet(DULL)
                .buildAndRegister();

        ZeoliteSievingPellets = new Material.Builder(GTCEu.id("zeolite_sieving_pellets"))
                .dust()
                .color(0x4d3e9f)
                .iconSet(DULL)
                .buildAndRegister();

        WetZeoliteSievingPellets = new Material.Builder(GTCEu.id("wet_zeolite_sieving_pellets"))
                .dust()
                .color(0x1d173c)
                .iconSet(DULL)
                .buildAndRegister();

        TertButanol = new Material.Builder(GTCEu.id("tert_butanol"))
                .fluid()
                .components(Carbon, 4, Hydrogen, 10, Oxygen, 1)
                .color(0xacb500)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        DitertbutylDicarbonate = new Material.Builder(GTCEu.id("ditertbutyl_dicarbonate"))
                .dust()
                .components(Carbon, 10, Hydrogen, 18, Oxygen, 5)
                .color(0x7e96b5)
                .iconSet(DULL)
                .buildAndRegister();

        Tertbuthylcarbonylazide = new Material.Builder(GTCEu.id("tertbuthylcarbonylazide"))
                .fluid()
                .components(Carbon, 5, Hydrogen, 9, Nitrogen, 3, Oxygen, 2)
                .color(0xacb500)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        SodiumToluenesulfonate = new Material.Builder(GTCEu.id("sodium_toluenesulfonate"))
                .fluid()
                .components(Carbon, 7, Hydrogen, 7, Sulfur, 3, Oxygen, 3, Sodium, 1)
                .color(0xb5b41d)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        SodiumAzide = new Material.Builder(GTCEu.id("sodium_azide"))
                .dust()
                .components(Sodium, 1, Nitrogen, 3)
                .color(0x1018f0)
                .iconSet(DULL)
                .buildAndRegister();

        SodiumAzanide = new Material.Builder(GTCEu.id("sodium_azanide"))
                .dust()
                .components(Sodium, 1, Nitrogen, 1, Hydrogen, 2)
                .color(0x2381b3)
                .iconSet(DULL)
                .buildAndRegister();

        NitrogenPentoxide = new Material.Builder(GTCEu.id("nitrogen_pentoxide"))
                .fluid()
                .components(Nitrogen, 2, Oxygen, 5)
                .color(0x162bb3)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        AminatedFullerene = new Material.Builder(GTCEu.id("aminated_fullerene"))
                .fluid()
                .components(Carbon, 60, Hydrogen, 12, Nitrogen, 12)
                .color(0x3842f0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        Azafullerene = new Material.Builder(GTCEu.id("azafullerene"))
                .fluid()
                .components(Carbon, 60, Hydrogen, 12, Nitrogen, 12)
                .color(0xb3a500)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        AbsoluteEthanol = new Material.Builder(GTCEu.id("absolute_ethanol"))
                .fluid()
                .color(0xff4500)
                .components(Carbon, 2, Hydrogen, 6, Oxygen, 1)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        PiranhaSolution = new Material.Builder(GTCEu.id("piranha_solution"))
                .fluid()
                .color(0xac2fff)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        PotassiumPyrosulfate = new Material.Builder(GTCEu.id("potassium_pyrosulfate"))
                .dust()
                .components(Potassium, 2, Sulfur, 2, Oxygen, 7)
                .color(0xff9900).iconSet(METALLIC)
                .buildAndRegister();

        PlatinumSlag = new Material.Builder(GTCEu.id("platinum_slag"))
                .dust()
                .color(0x343318).iconSet(DULL)
                .buildAndRegister().setFormula("IrOsRhRu??");

        LeachResidue = new Material.Builder(GTCEu.id("leach_residue"))
                .dust()
                .color(0x644629).iconSet(DULL)
                .buildAndRegister().setFormula("IrOsRhRu?");

        ZincSulfate = new Material.Builder(GTCEu.id("zinc_sulfate"))
                .dust()
                .components(Zinc, 1, Sulfur, 1, Oxygen, 4)
                .color(0x533c1b).iconSet(SAND)
                .buildAndRegister();

        RhodiumNitrate = new Material.Builder(GTCEu.id("rhodium_nitrate"))
                .dust()
                .color(0x8C5A0C).iconSet(SAND)
                .flags(DISABLE_DECOMPOSITION)
                .components(Rhodium, 1, Nitrogen, 1, Oxygen, 3, Concrete, 1)
                .buildAndRegister();

        RoughlyRhodiumMetal = new Material.Builder(GTCEu.id("roughly_rhodium_metal"))
                .dust()
                .color(0x594C1A).iconSet(SAND)
                .buildAndRegister().setFormula("?Rh?");

        ReprecipitatedRhodium = new Material.Builder(GTCEu.id("reprecipitated_rhodium"))
                .dust()
                .color(0xD40849).iconSet(SAND)
                .flags(DISABLE_DECOMPOSITION)
                .components(Rhodium, 1, Nitrogen, 1, Hydrogen, 4)
                .buildAndRegister();

        RhodiumSalt = new Material.Builder(GTCEu.id("rhodium_salt"))
                .dust()
                .color(0x61200A).iconSet(SAND)
                .buildAndRegister().setFormula("NaRhCl?");

        RhodiumSaltSolution = new Material.Builder(GTCEu.id("rhodium_salt_solution"))
                .fluid()
                .color(0x61200A).iconSet(SAND)
                .buildAndRegister().setFormula("Rh(NaCl)2Cl");

        RhodiumFilterCake = new Material.Builder(GTCEu.id("rhodium_filter_cake"))
                .dust()
                .color(0x87350C).iconSet(ROUGH)
                .buildAndRegister().setFormula("?Rh?");

        RhodiumFilterCakeSolution = new Material.Builder(GTCEu.id("rhodium_filter_cake_solution"))
                .fluid()
                .color(0x87350C).iconSet(ROUGH)
                .buildAndRegister().setFormula("?Rh?");

        SodiumRutheniate = new Material.Builder(GTCEu.id("sodium_rutheniate"))
                .dust()
                .color(0x282C8C).iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .components(Sodium, 2, Oxygen, 4, Ruthenium, 1)
                .buildAndRegister();

        IridiumDioxide = new Material.Builder(GTCEu.id("iridium_dioxide"))
                .dust()
                .color(0xA2BFFF).iconSet(METALLIC)
                .flags(DISABLE_DECOMPOSITION)
                .components(Iridium, 1, Oxygen, 2)
                .buildAndRegister();

        RutheniumTetroxideLQ = new Material.Builder(GTCEu.id("ruthenium_tetroxide_lq"))
                .fluid()
                .color(0xA8A8A8).iconSet(ROUGH)
                .buildAndRegister();

        SodiumFormate = new Material.Builder(GTCEu.id("sodium_formate"))
                .fluid()
                .color(0xf1939c).iconSet(ROUGH)
                .components(Sodium, 1, Carbon, 1, Oxygen, 2, Hydrogen, 1)
                .buildAndRegister();

        RhodiumSulfateGas = new Material.Builder(GTCEu.id("rhodium_sulfate_gas"))
                .gas()
                .color(0xBD8743).iconSet(ROUGH)
                .buildAndRegister();

        AcidicIridium = new Material.Builder(GTCEu.id("acidic_iridium"))
                .gas()
                .color(0x634E3A).iconSet(ROUGH)
                .buildAndRegister().setFormula("???");

        RutheniumTetroxideHot = new Material.Builder(GTCEu.id("ruthenium_tetroxide_hot"))
                .gas()
                .color(0x9B9B9B).iconSet(ROUGH)
                .buildAndRegister();

        SodiumSulfate = new Material.Builder(GTCEu.id("sodium_sulfate"))
                .dust()
                .components(Sodium, 2, Sulfur, 1, Oxygen, 4)
                .color(0xF9F6CF).iconSet(SAND)
                .buildAndRegister();

        MutatedLivingSolder = new Material.Builder(GTCEu.id("mutated_living_solder"))
                .fluid()
                .color(0xC18FCC).iconSet(METALLIC)
                .radioactiveHazard(1)
                .buildAndRegister();

        SuperMutatedLivingSolder = new Material.Builder(GTCEu.id("super_mutated_living_solder"))
                .fluid()
                .color(0xB662FF).iconSet(METALLIC)
                .radioactiveHazard(2)
                .buildAndRegister();

        HexafluorideEnrichedNaquadahSolution = new Material.Builder(GTCEu.id("hexafluoride_enriched_naquadah_solution"))
                .fluid()
                .color(0x868D7F)
                .components(NaquadahEnriched, 1, Fluorine, 6)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        XenonHexafluoroEnrichedNaquadate = new Material.Builder(GTCEu.id("xenon_hexafluoro_enriched_naquadate"))
                .fluid()
                .color(0x255A55)
                .components(Xenon, 1, NaquadahEnriched, 1, Fluorine, 6)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GoldTrifluoride = new Material.Builder(GTCEu.id("gold_trifluoride"))
                .dust()
                .color(0xE8C478)
                .iconSet(BRIGHT)
                .components(Gold, 1, Fluorine, 3)
                .buildAndRegister();

        XenoauricFluoroantimonicAcid = new Material.Builder(GTCEu.id("xenoauric_fluoroantimonic_acid"))
                .fluid(FluidStorageKeys.LIQUID, new FluidBuilder().attribute(FluidAttributes.ACID))
                .color(0xE0BD74)
                .components(Xenon, 1, Gold, 1, Antimony, 1, Fluorine, 6)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GoldChloride = new Material.Builder(GTCEu.id("gold_chloride"))
                .fluid()
                .color(0xCCCC66)
                .components(Gold, 2, Chlorine, 6)
                .buildAndRegister();

        BromineTrifluoride = new Material.Builder(GTCEu.id("bromine_trifluoride"))
                .fluid()
                .color(0xA88E57)
                .components(Bromine, 1, Fluorine, 3)
                .buildAndRegister();

        HexafluorideNaquadriaSolution = new Material.Builder(GTCEu.id("hexafluoride_naquadria_solution"))
                .fluid()
                .color(0x25C213)
                .components(Naquadria, 1, Fluorine, 6)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        RadonDifluoride = new Material.Builder(GTCEu.id("radon_difluoride"))
                .fluid()
                .color(0x8B7EFF)
                .components(Radon, 1, Fluorine, 2)
                .buildAndRegister();

        RadonNaquadriaOctafluoride = new Material.Builder(GTCEu.id("radon_naquadria_octafluoride"))
                .fluid()
                .color(0x85F378)
                .components(Radon, 1, Naquadria, 1, Fluorine, 8)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CaesiumFluoride = new Material.Builder(GTCEu.id("caesium_fluoride"))
                .fluid()
                .color(0xFF7A5F)
                .components(Caesium, 1, Fluorine, 1)
                .buildAndRegister();

        XenonTrioxide = new Material.Builder(GTCEu.id("xenon_trioxide"))
                .fluid()
                .color(0x359FC3)
                .components(Xenon, 1, Oxygen, 3)
                .buildAndRegister();

        CaesiumXenontrioxideFluoride = new Material.Builder(GTCEu.id("caesium_xenontrioxide_fluoride"))
                .fluid()
                .color(0x5067D7)
                .components(Caesium, 1, Xenon, 1, Oxygen, 3, Fluorine, 1)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        NaquadriaCaesiumXenonnonfluoride = new Material.Builder(GTCEu.id("naquadria_caesium_xenonnonfluoride"))
                .fluid()
                .color(0x54C248)
                .components(Naquadria, 1, Caesium, 1, Xenon, 1, Fluorine, 9)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        RadonTrioxide = new Material.Builder(GTCEu.id("radon_trioxide"))
                .fluid()
                .color(0x9A6DD7)
                .components(Radon, 1, Oxygen, 3)
                .buildAndRegister();

        NaquadriaCaesiumfluoride = new Material.Builder(GTCEu.id("naquadria_caesiumfluoride"))
                .fluid()
                .color(0xAAEB69)
                .components(Naquadria, 1, Fluorine, 3, Caesium, 1)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister().setFormula("*Nq*F2CsF", true);

        NitrosoniumOctafluoroxenate = new Material.Builder(GTCEu.id("nitrosonium_octafluoroxenate"))
                .fluid()
                .color(0x953D9F)
                .components(NitrogenDioxide, 2, Xenon, 1, Fluorine, 8)
                .buildAndRegister().setFormula("(NO2)2XeF8", true);

        NitrylFluoride = new Material.Builder(GTCEu.id("nitryl_fluoride"))
                .fluid()
                .color(0x8B7EFF)
                .components(Nitrogen, 1, Oxygen, 2, Fluorine, 1)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AcidicNaquadriaCaesiumfluoride = new Material.Builder(GTCEu.id("acidic_naquadria_caesiumfluoride"))
                .fluid()
                .color(0x75EB00)
                .components(Naquadria, 1, Fluorine, 3, Caesium, 1, Sulfur, 2, Oxygen, 8)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister().setFormula("*Nq*F2CsF(SO4)2", true);

        SupercriticalSteam = new Material.Builder(GTCEu.id("supercritical_steam"))
                .gas(new FluidBuilder().temperature(1000))
                .color(0xC4C4C4)
                .components(Water, 1)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        WaterAgarMix = new Material.Builder(GTCEu.id("water_agar_mix"))
                .fluid()
                .color(0x88FFC0)
                .buildAndRegister();

        TungstenTrioxide = new Material.Builder(GTCEu.id("tungsten_trioxide"))
                .dust()
                .components(Tungsten, 1, Oxygen, 3)
                .color(0x86FF75).iconSet(ROUGH)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SpaceTime = new Material.Builder(GTCEu.id("spacetime"))
                .ingot()
                .liquid(new FluidBuilder().temperature(1).customStill())
                .fluidPipeProperties(2147483647, ConfigHolder.INSTANCE.machines.spacetimePip, true, true, true, true)
                .element(GTLElements.SPACETIME)
                .iconSet(new MaterialIconSet("spacetime"))
                .flags(GTLMaterialFlags.GENERATE_NANOSWARM, NO_UNIFICATION)
                .cableProperties(Integer.MAX_VALUE, 524288, 0, true)
                .buildAndRegister();

        Infinity = new Material.Builder(GTCEu.id("infinity"))
                .ingot()
                .liquid(new FluidBuilder().temperature(1000000).customStill())
                .blastTemp(32000, HIGHEST)
                .element(GTLElements.INFINITY)
                .iconSet(new MaterialIconSet("infinity"))
                .flags(GENERATE_FRAME)
                .cableProperties(Integer.MAX_VALUE, 8192, 0, true)
                .buildAndRegister();

        CompoundTriniite = new Material.Builder(GTCEu.id("trinium_compound"))
                .dust()
                .ore()
                .components(Trinium, 3, Actinium, 3, Selenium, 4, Astatine, 4)
                .color(0x675989).iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CrystallineNitricAcid = new Material.Builder(GTCEu.id("crystalline_nitric_acid"))
                .dust()
                .components(Hydrogen, 1, Nitrogen, 1, Oxygen, 3)
                .color(0xCDBD14)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SodiumChlorate = new Material.Builder(GTCEu.id("sodium_chlorate"))
                .dust()
                .components(Sodium, 1, Chlorine, 1, Oxygen, 3)
                .color(0xA5A5A5)
                .buildAndRegister();

        SodiumPerchlorate = new Material.Builder(GTCEu.id("sodium_perchlorate"))
                .dust()
                .components(Sodium, 1, Chlorine, 1, Oxygen, 4)
                .color(0xF0F0F0)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ActiniumTriniumHydroxides = new Material.Builder(GTCEu.id("actinium_trinium_hydroxides"))
                .dust()
                .color(0xAD47CF).iconSet(ROUGH)
                .buildAndRegister().setFormula("Ke3Ac2(OH)12");

        SeleniumOxide = new Material.Builder(GTCEu.id("selenium_oxide"))
                .dust()
                .components(Selenium, 1, Oxygen, 2)
                .color(0xFFF71C).iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TriniumTetrafluoride = new Material.Builder(GTCEu.id("trinium_tetrafluoride"))
                .dust()
                .color(0x529E57)
                .components(Trinium, 1, Fluorine, 4)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Fluorocarborane = new Material.Builder(GTCEu.id("fluorocarborane"))
                .dust()
                .components(Hydrogen, 1, Carbon, 1, Hydrogen, 1, Boron, 11, Fluorine, 11)
                .color(0x00EC80).iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CaesiumNitrate = new Material.Builder(GTCEu.id("caesium_nitrate"))
                .dust()
                .components(Caesium, 1, Nitrogen, 1, Oxygen, 3)
                .color(0x821EC7).iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CesiumCarborane = new Material.Builder(GTCEu.id("cesium_carborane"))
                .dust()
                .components(Caesium, 1, Carbon, 1, Boron, 11, Hydrogen, 12)
                .color(0xAE6EDA).iconSet(SAND)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SilverIodide = new Material.Builder(GTCEu.id("silver_iodide"))
                .dust()
                .components(Silver, 1, Iodine, 1)
                .color(0xACA56A).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SilverNitrate = new Material.Builder(GTCEu.id("silver_nitrate"))
                .dust()
                .components(Silver, 1, Nitrogen, 1, Oxygen, 3)
                .color(0xFFFCE0).iconSet(BRIGHT)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TrifluoroaceticPhosphateEster = new Material.Builder(GTCEu.id("trifluoroacetic_phosphate_ester"))
                .dust()
                .components(Carbon, 8, Hydrogen, 5, Fluorine, 3, Oxygen, 2, Sulfur, 1)
                .color(0xA99F45).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        RadiumNitrate = new Material.Builder(GTCEu.id("radium_nitrate"))
                .dust()
                .color(0xCD40DA).iconSet(SAND)
                .buildAndRegister().setFormula("Ra(NO3)2");

        ActiniumNitrate = new Material.Builder(GTCEu.id("actinium_nitrate"))
                .dust()
                .color(0xDAE0EE).iconSet(DULL)
                .buildAndRegister().setFormula("Ac(NO3)3");

        PotassiumFluoride = new Material.Builder(GTCEu.id("potassium_fluoride"))
                .dust()
                .components(Potassium, 1, Fluorine, 1)
                .color(0xB9B9B9).iconSet(BRIGHT)
                .buildAndRegister();

        SodiumHydride = new Material.Builder(GTCEu.id("sodium_hydride"))
                .dust()
                .components(Sodium, 1, Hydrogen, 1)
                .color(0x757475).iconSet(DULL)
                .buildAndRegister();

        CesiumCarboranePrecursor = new Material.Builder(GTCEu.id("cesium_carborane_precursor"))
                .dust()
                .color(0xBA5C69).iconSet(DULL)
                .buildAndRegister().setFormula("CsB10H12CN(CH3)3Cl");

        LithiumAluminiumHydride = new Material.Builder(GTCEu.id("lithium_aluminium_hydride"))
                .dust()
                .components(Lithium, 1, Aluminium, 1, Hydrogen, 1)
                .color(0xABD7DF).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LithiumAluminiumFluoride = new Material.Builder(GTCEu.id("lithium_aluminium_fluoride"))
                .dust()
                .components(Aluminium, 1, Fluorine, 4, Lithium, 1)
                .color(0xAD1F58).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AluminiumTrifluoride = new Material.Builder(GTCEu.id("aluminium_trifluoride"))
                .dust()
                .components(Aluminium, 1, Fluorine, 3)
                .color(0xB8601A).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SodiumAluminiumHydride = new Material.Builder(GTCEu.id("sodium_aluminium_hydride"))
                .dust()
                .components(Sodium, 1, Aluminium, 1, Hydrogen, 4)
                .color(0x87BFBF).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AluminiumHydride = new Material.Builder(GTCEu.id("aluminium_hydride"))
                .dust()
                .components(Aluminium, 1, Hydrogen, 3)
                .color(0x315C6E).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Alumina = new Material.Builder(GTCEu.id("alumina"))
                .dust()
                .components(Aluminium, 2, Oxygen, 3)
                .color(0x1D4759).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CaesiumHydroxide = new Material.Builder(GTCEu.id("caesium_hydroxide"))
                .dust()
                .components(Caesium, 1, Oxygen, 1, Hydrogen, 1)
                .color(0xD7D7D7).iconSet(DULL)
                .buildAndRegister();

        Decaborane = new Material.Builder(GTCEu.id("decaborane"))
                .dust()
                .components(Boron, 10, Hydrogen, 14)
                .color(0x95B78F).iconSet(SAND)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SodiumTetrafluoroborate = new Material.Builder(GTCEu.id("sodium_tetrafluoroborate"))
                .dust()
                .components(Sodium, 1, Boron, 1, Fluorine, 4)
                .color(0xA6640F).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SodiumBorohydride = new Material.Builder(GTCEu.id("sodium_borohydride"))
                .dust()
                .components(Sodium, 1, Boron, 1, Hydrogen, 4)
                .color(0x9AB0B2).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        PhosphorusPentasulfide = new Material.Builder(GTCEu.id("phosphorus_pentasulfide"))
                .dust()
                .components(Phosphorus, 4, Sulfur, 10)
                .color(0xE7A123).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        StoneDustResidue = new Material.Builder(GTCEu.id("stone_dust_residue"))
                .dust()
                .color(0x585858).iconSet(DULL)
                .buildAndRegister();

        AmmoniumBifluoride = new Material.Builder(GTCEu.id("ammonium_bifluoride"))
                .dust()
                .components(Nitrogen, 1, Hydrogen, 4, Hydrogen, 1, Fluorine, 2)
                .color(0x26C6BB).iconSet(DULL)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        UncommonResidues = new Material.Builder(GTCEu.id("uncommon_residues"))
                .dust()
                .color(0x3F46BD).iconSet(SAND)
                .buildAndRegister();

        PartiallyOxidizedResidues = new Material.Builder(GTCEu.id("partially_oxidized_residues"))
                .dust()
                .color(0x8F1515).iconSet(SAND)
                .buildAndRegister();

        InertResidues = new Material.Builder(GTCEu.id("inert_residues"))
                .dust()
                .color(0x4F4863).iconSet(BRIGHT)
                .buildAndRegister();

        OxidizedResidues = new Material.Builder(GTCEu.id("oxidized_residues"))
                .dust()
                .color(0x330D4A).iconSet(SAND)
                .buildAndRegister();

        HeavyOxidizedResidues = new Material.Builder(GTCEu.id("heavy_oxidized_residues"))
                .dust()
                .color(0x310D48).iconSet(SAND)
                .buildAndRegister();

        CleanInertResidues = new Material.Builder(GTCEu.id("clean_inert_residues"))
                .dust()
                .color(0x187F4D).iconSet(BRIGHT)
                .buildAndRegister();

        MetallicResidues = new Material.Builder(GTCEu.id("metallic_residues"))
                .dust()
                .color(0x205A53).iconSet(SAND)
                .buildAndRegister();

        HeavyMetallicResidues = new Material.Builder(GTCEu.id("heavy_metallic_residues"))
                .dust()
                .color(0x1C0986).iconSet(SAND)
                .buildAndRegister();

        DiamagneticResidues = new Material.Builder(GTCEu.id("diamagnetic_residues"))
                .dust()
                .color(0x30845E).iconSet(SAND)
                .buildAndRegister();

        ParamagneticResidues = new Material.Builder(GTCEu.id("paramagnetic_residues"))
                .dust()
                .color(0x25788B).iconSet(SAND)
                .buildAndRegister();

        FerromagneticResidues = new Material.Builder(GTCEu.id("ferromagnetic_residues"))
                .dust()
                .color(0x1F5D46).iconSet(SAND)
                .buildAndRegister();

        HeavyDiamagneticResidues = new Material.Builder(GTCEu.id("heavy_diamagnetic_residues"))
                .dust()
                .color(0x22316A).iconSet(SAND)
                .buildAndRegister();

        HeavyParamagneticResidues = new Material.Builder(GTCEu.id("heavy_paramagnetic_residues"))
                .dust()
                .color(0x1A8E2F).iconSet(SAND)
                .buildAndRegister();

        HeavyFerromagneticResidues = new Material.Builder(GTCEu.id("heavy_ferromagnetic_residues"))
                .dust()
                .color(0x26743E).iconSet(SAND)
                .buildAndRegister();

        ExoticHeavyResidues = new Material.Builder(GTCEu.id("exotic_heavy_residues"))
                .dust()
                .color(0x3E8496).iconSet(BRIGHT)
                .buildAndRegister();

        FumingNitricAcid = new Material.Builder(GTCEu.id("fuming_nitric_acid"))
                .fluid()
                .components(Hydrogen, 1, Nitrogen, 1, Oxygen, 3)
                .color(0xB46800).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Perfluorobenzene = new Material.Builder(GTCEu.id("perfluorobenzene"))
                .fluid()
                .components(Carbon, 6, Fluorine, 6)
                .color(0x15752B).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Trimethylsilane = new Material.Builder(GTCEu.id("trimethylsilane"))
                .fluid()
                .components(Carbon, 3, Hydrogen, 10, Silicon, 1)
                .color(0x7D2FC3).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Trimethylchlorosilane = new Material.Builder(GTCEu.id("trimethylchlorosilane"))
                .fluid()
                .color(0x591399).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(CH3)3SiCl");

        NitratedTriniiteCompoundSolution = new Material.Builder(GTCEu.id("nitrated_triniite_compound_solution"))
                .fluid()
                .color(0x5E9699).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        ResidualTriniiteSolution = new Material.Builder(GTCEu.id("residual_triniite_solution"))
                .fluid()
                .color(0x68B59).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        ActiniumRadiumHydroxideSolution = new Material.Builder(GTCEu.id("actinium_radium_hydroxide_solution"))
                .fluid()
                .color(0xC3E0DC).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        ActiniumRadiumNitrateSolution = new Material.Builder(GTCEu.id("actinium_radium_nitrate_solution"))
                .fluid()
                .color(0x89C0B3).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        HeavilyFluorinatedTriniumSolution = new Material.Builder(GTCEu.id("heavily_fluorinated_trinium_solution"))
                .fluid()
                .color(0x169A33).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        EthyleneSulfide = new Material.Builder(GTCEu.id("ethylene_sulfide"))
                .fluid()
                .components(Carbon, 6, Hydrogen, 6, Sulfur, 1, Oxygen, 1)
                .color(0x868544).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        EthylTrifluoroacetate = new Material.Builder(GTCEu.id("ethyl_trifluoroacetate"))
                .fluid()
                .components(Carbon, 4, Hydrogen, 5, Fluorine, 3, Oxygen, 2)
                .color(0x93A658).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AcetylChloride = new Material.Builder(GTCEu.id("acetyl_chloride"))
                .fluid()
                .components(Carbon, 2, Hydrogen, 3, Oxygen, 1, Chlorine, 1)
                .color(0xD1B117).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        KryptonDifluoride = new Material.Builder(GTCEu.id("krypton_difluoride"))
                .fluid()
                .components(Krypton, 1, Fluorine, 2)
                .color(0x3FF12B).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        MoltenCalciumSalts = new Material.Builder(GTCEu.id("molten_calcium_salts"))
                .fluid()
                .color(0x7F478B)
                .iconSet(DULL)
                .buildAndRegister();

        Trimethylamine = new Material.Builder(GTCEu.id("trimethylamine"))
                .fluid()
                .color(0xCEA67D).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(CH3)3N");

        BoraneDimethylSulfide = new Material.Builder(GTCEu.id("borane_dimethyl_sulfide"))
                .fluid()
                .color(0xCEA67D).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(BH3)(CH3)2S");

        Tetrahydrofuran = new Material.Builder(GTCEu.id("tetrahydrofuran"))
                .fluid()
                .color(0x86E4CE).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(CH2)4O");

        Diborane = new Material.Builder(GTCEu.id("diborane"))
                .fluid()
                .color(0xBEEBCD).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(BH3)2");

        DiethylEther = new Material.Builder(GTCEu.id("diethyl_ether"))
                .fluid()
                .color(0x9AB0B2).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(C2H5)2O");

        BoronTrifluorideAcetate = new Material.Builder(GTCEu.id("boron_trifluoride_acetate"))
                .fluid()
                .color(0x991062).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(BF3)(C2H5)2O");

        SodiumHexafluoroaluminate = new Material.Builder(GTCEu.id("sodium_hexafluoroaluminate"))
                .fluid()
                .components(Sodium, 3, Aluminium, 1, Fluorine, 6)
                .color(0xA47732).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        DirtyHexafluorosilicicAcid = new Material.Builder(GTCEu.id("dirty_hexafluorosilicic_acid"))
                .fluid()
                .color(0xDA387D).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("H2SiF6?");

        DiluteHexafluorosilicicAcid = new Material.Builder(GTCEu.id("dilute_hexafluorosilicic_acid"))
                .fluid()
                .color(0x49DF68).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(H2O)2(H2SiF6)");

        FluorosilicicAcid = new Material.Builder(GTCEu.id("fluorosilicic_acid"))
                .fluid()
                .components(Hydrogen, 2, Silicon, 1, Fluorine, 6)
                .color(0x49BF61).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        AmmoniumFluoride = new Material.Builder(GTCEu.id("ammonium_fluoride"))
                .fluid()
                .components(Nitrogen, 1, Hydrogen, 4, Fluorine, 1)
                .color(0xB80926).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        AmmoniumBifluorideSolution = new Material.Builder(GTCEu.id("ammonium_bifluoride_solution"))
                .fluid()
                .color(0xCBC5B7).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(H2O)NH4FHF");

        SodiumHydroxideSolution = new Material.Builder(GTCEu.id("sodium_hydroxide_solution"))
                .fluid()
                .color(0x000791).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(H2O)NaOH");

        RedMud = new Material.Builder(GTCEu.id("red_mud"))
                .fluid()
                .color(0x972903).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("HCl?");

        NeutralisedRedMud = new Material.Builder(GTCEu.id("neutralised_red_mud"))
                .fluid()
                .color(0x972903).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("Fe??");

        RedSlurry = new Material.Builder(GTCEu.id("red_slurry"))
                .fluid()
                .color(0x982902).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("TiO2?");

        FerricReeChloride = new Material.Builder(GTCEu.id("ferric_ree_chloride"))
                .fluid()
                .color(0x68680D).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("Fe?");

        TitanylSulfate = new Material.Builder(GTCEu.id("titanyl_sulfate"))
                .fluid()
                .color(0xF82296).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("TiO(SO4)");

        DioxygenDifluoride = new Material.Builder(GTCEu.id("dioxygen_difluoride"))
                .fluid()
                .components(Fluorine, 1, Oxygen, 1, Oxygen, 1, Fluorine, 1)
                .color(0x18BFB9).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        OxidizedResidualSolution = new Material.Builder(GTCEu.id("oxidized_residual_solution"))
                .fluid()
                .color(0x2CD3CB).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        HeliumIIIHydride = new Material.Builder(GTCEu.id("helium_iii_hydride"))
                .fluid()
                .color(0x9F9F02).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("He_3H");

        UltraacidicResidueSolution = new Material.Builder(GTCEu.id("ultraacidic_residue_solution"))
                .fluid()
                .color(0x848C9A).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        XenicAcid = new Material.Builder(GTCEu.id("xenic_acid"))
                .fluid()
                .components(Hydrogen, 2, Xenon, 1, Oxygen, 4)
                .color(0x443A76).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DiluteHydrofluoricAcid = new Material.Builder(GTCEu.id("dilute_hydrofluoric_acid"))
                .fluid()
                .color(0x049821).iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister().setFormula("(H2O)(HF)");

        TritiumHydride = new Material.Builder(GTCEu.id("tritium_hydride"))
                .fluid()
                .components(Hydrogen, 1, Tritium, 1)
                .color(0xBA0202).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DustyLiquidHeliumIII = new Material.Builder(GTCEu.id("dusty_liquid_helium_iii"))
                .fluid()
                .components(Concrete, 1, Helium3, 1)
                .color(0x774060).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Ozone = new Material.Builder(GTCEu.id("ozone"))
                .fluid()
                .components(Oxygen, 3)
                .color(0x0176C4).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HydrogenPeroxide = new Material.Builder(GTCEu.id("hydrogen_peroxide"))
                .fluid()
                .components(Hydrogen, 2, Oxygen, 2)
                .color(0xC8FFFF).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        RareEarthChlorides = new Material.Builder(GTCEu.id("rare_earth_chlorides"))
                .fluid()
                .components(Concrete, 1, Chlorine, 1)
                .color(0xBDB76B).iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Fluorite = new Material.Builder(GTCEu.id("fluorite"))
                .fluid()
                .dust()
                .components(Calcium, 1, Fluorine, 2)
                .color(0x18B400).iconSet(DULL)
                .buildAndRegister();

        // wanggugu'sLanthanidetreatment
        SamariumRefinedPowder = new Material.Builder(GTCEu.id("samarium_refined_powder"))
                .dust()
                .color(0x8a6d7d)
                .iconSet(BRIGHT)
                .buildAndRegister().setFormula("??Sm??");

        SamariumRrareEearthTurbidLiquid = new Material.Builder(GTCEu.id("samarium_rrare_eearth_turbid_liquid"))
                .fluid()
                .color(0xcfc883)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        MonaziteRareEarthTurbidLiquid = new Material.Builder(GTCEu.id("monazite_rare_earth_turbid_liquid"))
                .fluid()
                .color(0x81624c)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        ThoritePowder = new Material.Builder(GTCEu.id("thorite_powder"))
                .dust()
                .components(Thorium, 1, Silicon, 1, Oxygen, 4)
                .color(0x7D7D7D)
                .iconSet(SAND)
                .buildAndRegister();

        DilutedMonaziteSlurry = new Material.Builder(GTCEu.id("diluted_monazite_slurry"))
                .fluid()
                .color(0xBDBDBD)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        RedZirconPowder = new Material.Builder(GTCEu.id("red_zircon_powder"))
                .dust()
                .components(Zirconium, 1, Silicon, 1, Oxygen, 4)
                .color(0xFF4500)
                .iconSet(SAND)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        MonaziteSulfatePowder = new Material.Builder(GTCEu.id("monazite_sulfate_powder"))
                .dust()
                .color(0xCCCCCC)
                .iconSet(SAND)
                .buildAndRegister();

        DilutedMonaziteSulfateSolution = new Material.Builder(GTCEu.id("diluted_monazite_sulfate_solution"))
                .fluid()
                .color(0xADD8E6)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        AcidicMonazitePowder = new Material.Builder(GTCEu.id("acidic_monazite_powder"))
                .dust()
                .color(0x9ACD32)
                .iconSet(SAND)
                .buildAndRegister();

        ThoriumPhosphateFilterCakePowder = new Material.Builder(GTCEu.id("thorium_phosphate_filter_cake_powder"))
                .dust()
                .color(0x808080)
                .iconSet(SAND)
                .buildAndRegister();

        ThoriumPhosphateRefinedPowder = new Material.Builder(GTCEu.id("thorium_phosphate_refined_powder"))
                .dust()
                .color(0x545454)
                .iconSet(SAND)
                .buildAndRegister().setFormula("??ThP??", true);

        MonaziteRareEarthFilterResiduePowder = new Material.Builder(GTCEu.id("monazite_rare_earth_filter_residue_powder"))
                .dust()
                .color(0x7F7F7F)
                .iconSet(SAND)
                .buildAndRegister();

        NeutralizedMonaziteRareEarthFilterResiduePowder = new Material.Builder(GTCEu.id("neutralized_monazite_rare_earth_filter_residue_powder"))
                .dust()
                .color(0x969696)
                .iconSet(SAND)
                .buildAndRegister();

        UraniumFilterResiduePowder = new Material.Builder(GTCEu.id("uranium_filter_residue_powder"))
                .dust()
                .color(0x545454)
                .iconSet(SAND)
                .buildAndRegister();

        NeutralizedUraniumFilterResiduePowder = new Material.Builder(GTCEu.id("neutralized_uranium_filter_residue_powder"))
                .dust()
                .color(0x7F7F7F)
                .iconSet(SAND)
                .buildAndRegister();

        ConcentratedMonaziteRareEarthHydroxidePowder = new Material.Builder(GTCEu.id("concentrated_monazite_rare_earth_hydroxide_powder"))
                .dust()
                .color(0xC0C0C0)
                .iconSet(SAND)
                .buildAndRegister();

        DriedConcentratedNitricMonaziteRareEarthPowder = new Material.Builder(GTCEu.id("dried_concentrated_nitric_monazite_rare_earth_powder"))
                .dust()
                .color(0x969696)
                .iconSet(SAND)
                .buildAndRegister();

        ConcentratedNitrideMonaziteRareEarthSolution = new Material.Builder(GTCEu.id("concentrated_nitride_monazite_rare_earth_solution"))
                .fluid()
                .color(0xADD8E6)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        CeriumRichMixturePowder = new Material.Builder(GTCEu.id("cerium_rich_mixture_powder"))
                .dust()
                .color(0x808080)
                .iconSet(SAND)
                .buildAndRegister().setFormula("??Ce??");

        CeriumChloridePowder = new Material.Builder(GTCEu.id("cerium_chloride_powder"))
                .dust()
                .components(Cerium, 1, Chlorine, 3)
                .color(0x808080)
                .iconSet(SAND)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        OxalicAcid = new Material.Builder(GTCEu.id("oxalic_acid"))
                .fluid()
                .components(Carbon, 2, Hydrogen, 2, Oxygen, 4)
                .color(0xA0FFA0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        VanadiumPentoxidePowder = new Material.Builder(GTCEu.id("vanadium_pentoxide_powder"))
                .dust()
                .components(Vanadium, 2, Oxygen, 5)
                .color(0x8B0000)
                .iconSet(SAND)
                .buildAndRegister();

        CeriumOxalatePowder = new Material.Builder(GTCEu.id("cerium_oxalate_powder"))
                .dust()
                .components(Cerium, 1, Carbon, 2, Hydrogen, 2, Oxygen, 4)
                .color(0x969696)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(SAND)
                .buildAndRegister();

        ConcentratedCeriumChlorideSolution = new Material.Builder(GTCEu.id("concentrated_cerium_chloride_solution"))
                .fluid()
                .components(Cerium, 1, Chlorine, 3)
                .color(0x00FFFF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        NitricLeachateFromMonazite = new Material.Builder(GTCEu.id("nitric_leachate_from_monazite"))
                .fluid()
                .color(0xADD8E6)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        ConcentratedNitricLeachateFromMonazite = new Material.Builder(GTCEu.id("concentrated_nitric_leachate_from_monazite"))
                .fluid()
                .color(0x00FFFF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        CoolingConcentratedNitricMonaziteRareEarthPowder = new Material.Builder(GTCEu.id("cooling_concentrated_nitric_monazite_rare_earth_powder"))
                .dust()
                .color(0xC0C0C0)
                .iconSet(SAND)
                .buildAndRegister();

        MonaziteRareEarthPrecipitatePowder = new Material.Builder(GTCEu.id("monazite_rare_earth_precipitate_powder"))
                .dust()
                .color(0x808080)
                .iconSet(SAND)
                .buildAndRegister();

        HeterogeneousHalideMonaziteRareEarthMixturePowder = new Material.Builder(GTCEu.id("heterogeneous_halide_monazite_rare_earth_mixture_powder"))
                .dust()
                .color(0x808080)
                .iconSet(SAND)
                .buildAndRegister();

        SaturatedMonaziteRareEarthPowder = new Material.Builder(GTCEu.id("saturated_monazite_rare_earth_powder"))
                .dust()
                .color(0xFFFFFF)
                .iconSet(SAND)
                .buildAndRegister();

        SamariumPrecipitatePowder = new Material.Builder(GTCEu.id("samarium_precipitate_powder"))
                .dust()
                .components(Samarium, 2, Gadolinium, 1)
                .color(0x969696)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(SAND)
                .buildAndRegister();

        ConcentratedRareEarthChlorideSolution = new Material.Builder(GTCEu.id("concentrated_rare_earth_chloride_solution"))
                .fluid()
                .color(0x00BFFF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        EnrichedRareEarthChlorideSolution = new Material.Builder(GTCEu.id("enriched_rare_earth_chloride_solution"))
                .fluid()
                .color(0x87CEFA)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        DilutedRareEarthChlorideSolution = new Material.Builder(GTCEu.id("diluted_rare_earth_chloride_solution"))
                .fluid()
                .color(0xADD8E6)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        SamariumRareEarthSlurry = new Material.Builder(GTCEu.id("samarium_rare_earth_slurry"))
                .fluid()
                .color(0x808080)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        NeodymiumRareEarthConcentratePowder = new Material.Builder(GTCEu.id("neodymium_rare_earth_concentrate_powder"))
                .dust()
                .color(0x969696)
                .iconSet(SAND)
                .buildAndRegister();

        SamariumRareEarthDilutedSolution = new Material.Builder(GTCEu.id("samarium_rare_earth_diluted_solution"))
                .fluid()
                .components(Samarium, 1, Chlorine, 1, Water, 2)
                .color(0xA9A9A9)
                .flags(DISABLE_DECOMPOSITION)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        SamariumOxalateWithImpurities = new Material.Builder(GTCEu.id("samarium_oxalate_with_impurities"))
                .dust()
                .components(Samarium, 1, Carbon, 2, Oxygen, 6)
                .color(0x808080)
                .iconSet(SAND)
                .buildAndRegister();

        SamariumChlorideWithImpurities = new Material.Builder(GTCEu.id("samarium_chloride_with_impurities"))
                .dust()
                .fluid()
                .components(Samarium, 1, Chlorine, 3)
                .color(0xB0B0B0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SamariumChlorideSodiumChlorideMixturePowder = new Material.Builder(GTCEu.id("samarium_chloride_sodium_chloride_mixture_powder"))
                .dust()
                .components(Samarium, 1, Chlorine, 3, Sodium, 1, Chlorine, 1)
                .color(0xC0C0C0)
                .iconSet(SAND)
                .buildAndRegister();

        PhosphorusFreeSamariumConcentratePowder = new Material.Builder(GTCEu.id("phosphorus_free_samarium_concentrate_powder"))
                .dust()
                .components(Samarium, 1)
                .flags(DISABLE_DECOMPOSITION)
                .color(0xD3D3D3)
                .iconSet(SAND)
                .buildAndRegister();

        SamariumChlorideConcentrateSolution = new Material.Builder(GTCEu.id("samarium_chloride_concentrate_solution"))
                .fluid()
                .components(Samarium, 1, Chlorine, 3, Water, 5)
                .color(0xB0C4DE)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        LanthanumChloride = new Material.Builder(GTCEu.id("lanthanum_chloride"))
                .dust()
                .components(Lanthanum, 1, Chlorine, 3)
                .color(0xFFFFFF)
                .iconSet(SAND)
                .buildAndRegister();

        LanthanumChlorideWithImpurities = new Material.Builder(GTCEu.id("lanthanum_chloride_with_impurities"))
                .dust()
                .color(0xE0E0E0)
                .iconSet(SAND)
                .buildAndRegister();

        FluoroCarbonLanthanideCeriumSolution = new Material.Builder(GTCEu.id("fluoro_carbon_lanthanide_cerium_solution"))
                .fluid()
                .color(0x3888d2)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        SteamCrackedFluoroCarbonLanthanideSlurry = new Material.Builder(GTCEu.id("steam_cracked_fluoro_carbon_lanthanide_slurry"))
                .fluid()
                .color(0x7F7FFF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        ModulatedFluoroCarbonLanthanideSlurry = new Material.Builder(GTCEu.id("modulated_fluoro_carbon_lanthanide_slurry"))
                .fluid()
                .color(0x5A5AFF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        SodiumFluorosilicate = new Material.Builder(GTCEu.id("sodium_fluorosilicate"))
                .dust()
                .fluid()
                .color(0x808080)
                .iconSet(SAND)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        DilutedFluoroCarbonLanthanideSlurry = new Material.Builder(GTCEu.id("diluted_fluoro_carbon_lanthanide_slurry"))
                .fluid()
                .color(0x9696FF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        FilteredFluoroCarbonLanthanideSlurry = new Material.Builder(GTCEu.id("filtered_fluoro_carbon_lanthanide_slurry"))
                .fluid()
                .color(0xA0A0FF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        FluoroCarbonLanthanideCeriumOxidePowder = new Material.Builder(GTCEu.id("fluoro_carbon_lanthanide_cerium_oxide_powder"))
                .dust()
                .color(0x7F7F7F)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        AcidLeachedFluoroCarbonLanthanideCeriumOxidePowder = new Material.Builder(GTCEu.id("acid_leached_fluoro_carbon_lanthanide_cerium_oxide_powder"))
                .dust()
                .color(0x555555)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        CalcinedRareEarthOxidePowder = new Material.Builder(GTCEu.id("calcined_rare_earth_oxide_powder"))
                .dust()
                .color(0x969696)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        WetRareEarthOxidePowder = new Material.Builder(GTCEu.id("wet_rare_earth_oxide_powder"))
                .dust()
                .color(0x6699CC)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        CeriumOxideRareEarthOxidePowder = new Material.Builder(GTCEu.id("cerium_oxide_rare_earth_oxide_powder"))
                .dust()
                .components(Cerium, 1, Oxygen, 2)
                .flags(DISABLE_DECOMPOSITION)
                .color(0xD2D2D2)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        FluoroCarbonLanthanideCeriumRareEarthOxidePowder = new Material.Builder(GTCEu.id("fluoro_carbon_lanthanide_cerium_rare_earth_oxide_powder"))
                .dust()
                .color(0x7F7F7F)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        NitridedFluoroCarbonLanthanideCeriumRareEarthOxideSolution = new Material.Builder(GTCEu.id("nitrided_fluoro_carbon_lanthanide_cerium_rare_earth_oxide_solution"))
                .fluid()
                .color(0x9999FF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        FluoroCarbonLanthanideCeriumRareEarthSuspension = new Material.Builder(GTCEu.id("fluoro_carbon_lanthanide_cerium_rare_earth_suspension"))
                .fluid()
                .color(0xBEBEBE)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        SamariumRareEarthConcentratePowder = new Material.Builder(GTCEu.id("samarium_rare_earth_concentrate_powder"))
                .dust()
                .color(0x808080)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        FluorinatedSamariumConcentratePowder = new Material.Builder(GTCEu.id("fluorinated_samarium_concentrate_powder"))
                .dust()
                .color(0x969696)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        SamariumTerbiumMixturePowder = new Material.Builder(GTCEu.id("samarium_terbium_mixture_powder"))
                .dust()
                .color(0x969696)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        NitridedSamariumTerbiumMixturePowder = new Material.Builder(GTCEu.id("nitrided_samarium_terbium_mixture_powder"))
                .dust()
                .color(0x7F7F7F)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        TerbiumNitratePowder = new Material.Builder(GTCEu.id("terbium_nitrate_powder"))
                .dust()
                .color(0x0080FF)
                .components(Terbium, 1, Nitrogen, 1, Oxygen, 3)
                .iconSet(GTLMaterialIconSet.BRIGHT)
                .buildAndRegister();

        CarbonTetrachloride = new Material.Builder(GTCEu.id("carbon_tetrachloride"))
                .fluid()
                .color(0x0E9000)
                .components(Carbon, 1, Chlorine, 4)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        ActiniumOxalate = new Material.Builder(GTCEu.id("actinium_oxalate"))
                .dust()
                .color(0xcfcfcf)
                .iconSet(GLASS)
                .buildAndRegister().setFormula("Ac(CO₂)₄");

        EthylHexanol = new Material.Builder(GTCEu.id("ethyl_hexanol"))
                .fluid()
                .components(Carbon, 8, Hydrogen, 18, Oxygen, 1)
                .color(0xFFFFCC)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .buildAndRegister();

        P507 = new Material.Builder(GTCEu.id("p507"))
                .fluid()
                .components(Carbon, 18, Hydrogen, 36, Phosphorus, 1, Oxygen, 3)
                .color(0xFFFF00)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LanthanumExtractionNanoResin = new Material.Builder(
                GTCEu.id("lanthanum_extraction_nano_resin"))
                .fluid()
                .color(0x808080)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CeriumExtractionNanoResin = new Material.Builder(
                GTCEu.id("cerium_extraction_nano_resin"))
                .fluid()
                .color(0x969696)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        PraseodymiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("praseodymium_extraction_nano_resin"))
                .fluid()
                .color(0xA0A0A0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        NeodymiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("neodymium_extraction_nano_resin"))
                .fluid()
                .color(0xB0B0B0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        PromethiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("promethium_extraction_nano_resin"))
                .fluid()
                .color(0xC0C0C0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SamariumExtractionNanoResin = new Material.Builder(
                GTCEu.id("samarium_extraction_nano_resin"))
                .fluid()
                .color(0xD0D0D0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        EuropiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("europium_extraction_nano_resin"))
                .fluid()
                .color(0xE0E0E0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GadoliniumExtractionNanoResin = new Material.Builder(
                GTCEu.id("gadolinium_extraction_nano_resin"))
                .fluid()
                .color(0xF0F0F0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TerbiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("terbium_extraction_nano_resin"))
                .fluid()
                .color(0x0080FF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DysprosiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("dysprosium_extraction_nano_resin"))
                .fluid()
                .color(0x00FFFF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HolmiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("holmium_extraction_nano_resin"))
                .fluid()
                .color(0x00FF00)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ErbiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("erbium_extraction_nano_resin"))
                .fluid()
                .color(0xFF00FF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ThuliumExtractionNanoResin = new Material.Builder(
                GTCEu.id("thulium_extraction_nano_resin"))
                .fluid()
                .color(0xFF0000)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        YtterbiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("ytterbium_extraction_nano_resin"))
                .fluid()
                .color(0xFFFF00)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LutetiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("lutetium_extraction_nano_resin"))
                .fluid()
                .color(0x800080)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ScandiumExtractionNanoResin = new Material.Builder(
                GTCEu.id("scandium_extraction_nano_resin"))
                .fluid()
                .color(0x808000)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        YttriumExtractionNanoResin = new Material.Builder(
                GTCEu.id("yttrium_extraction_nano_resin"))
                .fluid()
                .color(0x008000)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LanthanumExtractedNanoResin = new Material.Builder(
                GTCEu.id("lanthanum_extracted_nano_resin"))
                .fluid()
                .color(0x808080)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        CeriumExtractedNanoResin = new Material.Builder(
                GTCEu.id("cerium_extracted_nano_resin"))
                .fluid()
                .color(0x969696)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        PraseodymiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("praseodymium_extracted_nano_resin"))
                .fluid()
                .color(0xA0A0A0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        NeodymiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("neodymium_extracted_nano_resin"))
                .fluid()
                .color(0xB0B0B0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        PromethiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("promethium_extracted_nano_resin"))
                .fluid()
                .color(0xC0C0C0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        SamariumExtractedNanoResin = new Material.Builder(
                GTCEu.id("samarium_extracted_nano_resin"))
                .fluid()
                .color(0xD0D0D0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        EuropiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("europium_extracted_nano_resin"))
                .fluid()
                .color(0xE0E0E0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        GadoliniumExtractedNanoResin = new Material.Builder(
                GTCEu.id("gadolinium_extracted_nano_resin"))
                .fluid()
                .color(0xF0F0F0)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        TerbiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("terbium_extracted_nano_resin"))
                .fluid()
                .color(0x0080FF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        DysprosiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("dysprosium_extracted_nano_resin"))
                .fluid()
                .color(0x00FFFF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        HolmiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("holmium_extracted_nano_resin"))
                .fluid()
                .color(0x00FF00)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ErbiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("erbium_extracted_nano_resin"))
                .fluid()
                .color(0xFF00FF)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ThuliumExtractedNanoResin = new Material.Builder(
                GTCEu.id("thulium_extracted_nano_resin"))
                .fluid()
                .color(0xFF0000)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        YtterbiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("ytterbium_extracted_nano_resin"))
                .fluid()
                .color(0xFFFF00)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        LutetiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("lutetium_extracted_nano_resin"))
                .fluid()
                .color(0x800080)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        ScandiumExtractedNanoResin = new Material.Builder(
                GTCEu.id("scandium_extracted_nano_resin"))
                .fluid()
                .color(0x808000)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        YttriumExtractedNanoResin = new Material.Builder(
                GTCEu.id("yttrium_extracted_nano_resin"))
                .fluid()
                .color(0x008000)
                .iconSet(GTLMaterialIconSet.LIMPID)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        MiracleGas = new Material.Builder(
                GTCEu.id("miracle_gas"))
                .gas()
                .color(0xea8bd6)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        Miracle = new Material.Builder(
                GTCEu.id("miracle"))
                .fluid()
                .color(0xea8bd6)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();

        MiracleAdhesive = new Material.Builder(
                GTCEu.id("miracle_adhesive"))
                .fluid()
                .color(0xefb5f1)
                .flags(DISABLE_DECOMPOSITION)
                .buildAndRegister();
    }
}
