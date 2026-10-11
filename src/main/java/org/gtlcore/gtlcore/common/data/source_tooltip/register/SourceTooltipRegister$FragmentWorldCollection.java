package org.gtlcore.gtlcore.common.data.source_tooltip.register;

import org.gtlcore.gtlcore.api.event.SourceTooltipRegistrationEvent;
import org.gtlcore.gtlcore.common.data.source_tooltip.SourceTooltip;
import org.gtlcore.gtlcore.common.data.source_tooltip.SpecialComponent;
import org.gtlcore.gtlcore.config.ConfigHolder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class SourceTooltipRegister$FragmentWorldCollection {

    private static final Component component_fragment_world_collection = Component.translatable("gtceu.fragment_world_collection").withStyle(SpecialComponent.color_recipe_type);

    private static void of_fwc(SourceTooltip tooltip, Component... components) {
        if (tooltip == null) return;
        if (components == null || components.length == 0) return;
        tooltip.get_or_create$control(SpecialComponent.component_default$control)
                .add(component_fragment_world_collection)
                .add(components);
    }

    private static final Component component_default_custom$fragment_world_collection = SpecialComponent.component_default$custom("Ctrl", Component.translatable("gtlcore.source_tooltip.series.world_fragment_collection.default_custom"));

    private static void to_fwc(SourceTooltip tooltip, List<Component> components) {
        if (tooltip == null) return;
        if (components == null || components.isEmpty()) return;
        tooltip.get_or_create$control(component_default_custom$fragment_world_collection)
                .add(component_fragment_world_collection)
                .add(components);
    }

    private static Component as_fwc(int i, String cs) {
        return Component.empty().append(SpecialComponent.components_world_fragment[i]).append(Component.literal(cs).withStyle(SpecialComponent.color_circuits));
    }

    private static Component as_fwc(MutableComponent component, String cs) {
        return Component.empty().append(component).append(Component.literal(cs).withStyle(SpecialComponent.color_circuits));
    }

    public static void register(SourceTooltipRegistrationEvent event) {
        if (!ConfigHolder.INSTANCE.content.enableSkyBlokeMode) return;
        of_fwc(event.register$item("gtceu:raw_almandine"), as_fwc(0, " [ 14 ]"));
        of_fwc(event.register$item("gtceu:raw_aluminium"), as_fwc(2, " [ 2 ]"), as_fwc(9, " [ 3 ]"), as_fwc(13, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_alunite"), as_fwc(1, " [ 4 ]"), as_fwc(6, " [ 3 ]"), as_fwc(12, " [ 1 ]"), as_fwc(14, " [ 1 ]"));
        of_fwc(event.register$item("gtceu:raw_amethyst"), as_fwc(0, " [ 19 ]"));
        of_fwc(event.register$item("gtceu:raw_apatite"), as_fwc(0, " [ 10 ]"), as_fwc(3, " [ 3 ]"), as_fwc(11, " [ 3 ]"), as_fwc(15, " [ 7 ]"));
        of_fwc(event.register$item("gtceu:raw_asbestos"), as_fwc(0, " [ 22 ]"));
        of_fwc(event.register$item("gtceu:raw_barite"), as_fwc(1, " [ 2, 5 ]"), as_fwc(6, " [ 7 ]"), as_fwc(7, " [ 1 ]"), as_fwc(9, " [ 2 ]"), as_fwc(15, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_basaltic_mineral_sand"), as_fwc(0, " [ 20 ]"));
        of_fwc(event.register$item("gtceu:raw_bastnasite"), as_fwc(2, " [ 3 ]"), as_fwc(6, " [ 1 ]"), as_fwc(7, " [ 3 ]"), as_fwc(14, " [ 5 ]"));
        of_fwc(event.register$item("gtceu:raw_bauxite"), as_fwc(2, " [ 2 ]"), as_fwc(9, " [ 3 ]"), as_fwc(13, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_bentonite"), as_fwc(0, " [ 13 ]"), as_fwc(4, " [ 1 ]"), as_fwc(8, " [ 3 ]"), as_fwc(11, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_beryllium"), as_fwc(1, " [ 3 ]"), as_fwc(6, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_blue_topaz"), as_fwc(1, " [ 1 ]"), as_fwc(6, " [ 10 ]"), as_fwc(9, " [ 1 ]"), as_fwc(15, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_bornite"), as_fwc(3, " [ 1 ]"), as_fwc(6, " [ 10 ]"), as_fwc(11, " [ 1 ]"), as_fwc(13, " [ 5 ]"), as_fwc(14, " [ 6 ]"));
        of_fwc(event.register$item("gtceu:raw_calcite"), as_fwc(0, " [ 17 ]"));
        of_fwc(event.register$item("gtceu:raw_calorite"), as_fwc(5, " [ 1 ]"), as_fwc(14, " [ 8 ]"));
        of_fwc(event.register$item("gtceu:raw_cassiterite_sand"), as_fwc(0, " [ 22 ]"));
        of_fwc(event.register$item("gtceu:raw_cassiterite"), as_fwc(0, " [ 5, 8 ]"));
        of_fwc(event.register$item("gtceu:raw_celestine"), as_fwc(8, " [ 1 ]"), as_fwc(14, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_certus_quartz"), as_fwc(1, " [ 5 ]"), as_fwc(6, " [ 7 ]"), as_fwc(7, " [ 1 ]"), as_fwc(15, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_chalcocite"), as_fwc(6, " [ 10 ]"));
        of_fwc(event.register$item("gtceu:raw_chalcopyrite"), as_fwc(0, " [ 5, 6 ]"));
        of_fwc(event.register$item("gtceu:raw_cinnabar"), as_fwc(0, " [ 9 ]"), as_fwc(6, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_coal"), as_fwc(0, " [ 11, 15 ]"));
        of_fwc(event.register$item("gtceu:raw_cobalt"), as_fwc(5, " [ 1 ]"), as_fwc(14, " [ 8 ]"));
        of_fwc(event.register$item("gtceu:raw_cobaltite"), as_fwc(0, " [ 12 ]"), as_fwc(5, " [ 2 ]"), as_fwc(10, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_cooperite"), as_fwc(3, " [ 1 ]"), as_fwc(8, " [ 1 ]"), as_fwc(11, " [ 1 ]"), as_fwc(13, " [ 5 ]"), as_fwc(14, " [ 4, 6 ]"));
        of_fwc(event.register$item("gtceu:raw_desh"), as_fwc(4, " [ 3 ]"), as_fwc(12, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_diamond"), as_fwc(0, " [ 11 ]"));
        of_fwc(event.register$item("gtceu:raw_diatomite"), as_fwc(0, " [ 22 ]"), as_fwc(1, " [ 4 ]"), as_fwc(6, " [ 3 ]"), as_fwc(12, " [ 1 ]"), as_fwc(14, " [ 1 ]"));
        of_fwc(event.register$item("gtceu:raw_electrotine"), as_fwc(1, " [ 4 ]"), as_fwc(6, " [ 3 ]"), as_fwc(12, " [ 1 ]"), as_fwc(14, " [ 1 ]"));
        of_fwc(event.register$item("gtceu:raw_emerald"), as_fwc(1, " [ 3 ]"), as_fwc(6, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_fullers_earth"), as_fwc(0, " [ 20 ]"));
        of_fwc(event.register$item("gtceu:raw_galena"), as_fwc(0, " [ 7 ]"));
        of_fwc(event.register$item("gtceu:raw_garnet_sand"), as_fwc(0, " [ 22 ]"));
        of_fwc(event.register$item("gtceu:raw_garnierite"), as_fwc(0, " [ 12 ]"), as_fwc(5, " [ 2 ]"), as_fwc(10, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_glauconite_sand"), as_fwc(0, " [ 13, 3 ]"), as_fwc(4, " [ 1 ]"), as_fwc(8, " [ 3 ]"), as_fwc(11, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_goethite"), as_fwc(0, " [ 2 ]"), as_fwc(6, " [ 9 ]"));
        of_fwc(event.register$item("gtceu:raw_granitic_mineral_sand"), as_fwc(0, " [ 20 ]"));
        of_fwc(event.register$item("gtceu:raw_graphite"), as_fwc(0, " [ 11 ]"));
        of_fwc(event.register$item("gtceu:raw_green_sapphire"), as_fwc(0, " [ 14 ]"));
        of_fwc(event.register$item("gtceu:raw_grossular"), as_fwc(0, " [ 4 ]"), as_fwc(6, " [ 5 ]"), as_fwc(9, " [ 5 ]"), as_fwc(12, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_gypsum"), as_fwc(0, " [ 20 ]"));
        of_fwc(event.register$item("gtceu:raw_hematite"), as_fwc(0, " [ 2 ]"), as_fwc(6, " [ 9 ]"));
        of_fwc(event.register$item("gtceu:raw_ilmenite"), as_fwc(2, " [ 2 ]"), as_fwc(9, " [ 3 ]"), as_fwc(13, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_kyanite"), as_fwc(0, " [ 18 ]"));
        of_fwc(event.register$item("gtceu:raw_lapis"), as_fwc(0, " [ 17 ]"));
        of_fwc(event.register$item("gtceu:raw_lazurite"), as_fwc(0, " [ 17 ]"));
        of_fwc(event.register$item("gtceu:raw_lead"), as_fwc(0, " [ 7 ]"));
        of_fwc(event.register$item("gtceu:raw_lepidolite"), as_fwc(0, " [ 21 ]"));
        of_fwc(event.register$item("gtceu:raw_lithium"), as_fwc(3, " [ 2 ]"), as_fwc(13, " [ 3 ]"), as_fwc(14, " [ 7 ]"), as_fwc(15, " [ 5 ]"));
        of_fwc(event.register$item("gtceu:raw_magnesite"), as_fwc(4, " [ 3 ]"), as_fwc(5, " [ 1 ]"), as_fwc(12, " [ 3 ]"), as_fwc(14, " [ 8 ]"));
        of_fwc(event.register$item("gtceu:raw_magnetite"), as_fwc(0, " [ 13, 16 ]"), as_fwc(4, " [ 1 ]"), as_fwc(8, " [ 3 ]"), as_fwc(11, " [ 2 ]"), as_fwc(13, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_malachite"), as_fwc(0, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_mica"), as_fwc(0, " [ 18 ]"));
        of_fwc(event.register$item("gtceu:raw_molybdenite"), as_fwc(1, " [ 8 ]"), as_fwc(6, " [ 6 ]"), as_fwc(7, " [ 2 ]"), as_fwc(14, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_molybdenum"), as_fwc(1, " [ 8 ]"), as_fwc(6, " [ 6 ]"), as_fwc(7, " [ 2 ]"), as_fwc(14, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_monazite"), as_fwc(2, " [ 3 ]"), as_fwc(6, " [ 1 ]"), as_fwc(7, " [ 3 ]"), as_fwc(14, " [ 5 ]"));
        of_fwc(event.register$item("gtceu:raw_naquadah"), as_fwc(8, " [ 2 ]"), as_fwc(10, " [ 2 ]"), as_fwc(13, " [ 1 ]"), as_fwc(15, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_neodymium"), as_fwc(2, " [ 3 ]"), as_fwc(6, " [ 1 ]"), as_fwc(7, " [ 3 ]"), as_fwc(14, " [ 5 ]"));
        of_fwc(event.register$item("gtceu:raw_nether_quartz"), as_fwc(1, " [ 2 ]"), as_fwc(6, " [ 11 ]"), as_fwc(9, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_nickel"), as_fwc(0, " [ 12 ]"), as_fwc(5, " [ 2 ]"), as_fwc(10, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_oilsands"), as_fwc(0, " [ 23 ]"));
        of_fwc(event.register$item("gtceu:raw_olivine"), as_fwc(0, " [ 13 ]"), as_fwc(4, " [ 1 ]"), as_fwc(8, " [ 3 ]"), as_fwc(11, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_opal"), as_fwc(0, " [ 19 ]"));
        of_fwc(event.register$item("gtceu:raw_ostrum"), as_fwc(7, " [ 4 ]"), as_fwc(14, " [ 9 ]"));
        of_fwc(event.register$item("gtceu:raw_palladium"), as_fwc(3, " [ 1 ]"), as_fwc(11, " [ 1 ]"), as_fwc(13, " [ 5 ]"), as_fwc(14, " [ 6 ]"));
        of_fwc(event.register$item("gtceu:raw_pentlandite"), as_fwc(0, " [ 12, 3 ]"), as_fwc(5, " [ 2 ]"), as_fwc(10, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_pitchblende"), as_fwc(2, " [ 4 ]"), as_fwc(10, " [ 5 ]"), as_fwc(12, " [ 4 ]"), as_fwc(13, " [ 6 ]"));
        of_fwc(event.register$item("gtceu:raw_platinum"), as_fwc(3, " [ 1 ]"), as_fwc(11, " [ 1 ]"), as_fwc(13, " [ 5 ]"), as_fwc(14, " [ 6 ]"));
        of_fwc(event.register$item("gtceu:raw_plutonium"), as_fwc(2, " [ 1 ]"), as_fwc(8, " [ 2 ]"), as_fwc(10, " [ 1, 2 ]"), as_fwc(13, " [ 1 ]"), as_fwc(15, " [ 1, 4 ]"));
        of_fwc(event.register$item("gtceu:raw_pollucite"), as_fwc(0, " [ 18 ]"));
        of_fwc(event.register$item("gtceu:raw_powellite"), as_fwc(1, " [ 8 ]"), as_fwc(6, " [ 6 ]"), as_fwc(7, " [ 2 ]"), as_fwc(14, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_pyrite"), as_fwc(0, " [ 6 ]"), as_fwc(1, " [ 6 ]"), as_fwc(4, " [ 2 ]"), as_fwc(6, " [ 12 ]"), as_fwc(8, " [ 4 ]"), as_fwc(9, " [ 4 ]"), as_fwc(14, " [ 2 ]"), as_fwc(15, " [ 6 ]"));
        of_fwc(event.register$item("gtceu:raw_pyrochlore"), as_fwc(3, " [ 3 ]"), as_fwc(11, " [ 3 ]"), as_fwc(15, " [ 7 ]"));
        of_fwc(event.register$item("gtceu:raw_pyrolusite"), as_fwc(0, " [ 4 ]"), as_fwc(6, " [ 5 ]"), as_fwc(9, " [ 5 ]"), as_fwc(12, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_pyrope"), as_fwc(0, " [ 14 ]"));
        of_fwc(event.register$item("gtceu:raw_quartzite"), as_fwc(1, " [ 2, 5 ]"), as_fwc(6, " [ 11, 7 ]"), as_fwc(7, " [ 1 ]"), as_fwc(9, " [ 2 ]"), as_fwc(15, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_realgar"), as_fwc(0, " [ 5 ]"));
        of_fwc(event.register$item("gtceu:raw_red_garnet"), as_fwc(0, " [ 19 ]"));
        of_fwc(event.register$item("gtceu:raw_redstone"), as_fwc(0, " [ 9 ]"), as_fwc(6, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_rock_salt"), as_fwc(0, " [ 21 ]"));
        of_fwc(event.register$item("gtceu:raw_ruby"), as_fwc(0, " [ 9 ]"), as_fwc(6, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_salt"), as_fwc(0, " [ 21 ]"));
        of_fwc(event.register$item("gtceu:raw_saltpeter"), as_fwc(1, " [ 4 ]"), as_fwc(6, " [ 3 ]"), as_fwc(12, " [ 1 ]"), as_fwc(14, " [ 1 ]"));
        of_fwc(event.register$item("gtceu:raw_sapphire"), as_fwc(0, " [ 14 ]"));
        of_fwc(event.register$item("gtceu:raw_scheelite"), as_fwc(3, " [ 2 ]"), as_fwc(13, " [ 3 ]"), as_fwc(14, " [ 7 ]"), as_fwc(15, " [ 5 ]"));
        of_fwc(event.register$item("gtceu:raw_silver"), as_fwc(0, " [ 7 ]"));
        of_fwc(event.register$item("gtceu:raw_soapstone"), as_fwc(0, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_sodalite"), as_fwc(0, " [ 17 ]"));
        of_fwc(event.register$item("gtceu:raw_spessartine"), as_fwc(0, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_sphalerite"), as_fwc(1, " [ 6 ]"), as_fwc(4, " [ 2 ]"), as_fwc(6, " [ 12 ]"), as_fwc(8, " [ 4 ]"), as_fwc(9, " [ 4 ]"), as_fwc(14, " [ 2 ]"), as_fwc(15, " [ 6 ]"));
        of_fwc(event.register$item("gtceu:raw_spodumene"), as_fwc(0, " [ 21 ]"));
        of_fwc(event.register$item("gtceu:raw_stibnite"), as_fwc(1, " [ 7 ]"), as_fwc(6, " [ 8 ]"), as_fwc(10, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_sulfur"), as_fwc(1, " [ 6 ]"), as_fwc(4, " [ 2 ]"), as_fwc(6, " [ 12 ]"), as_fwc(8, " [ 4 ]"), as_fwc(9, " [ 4 ]"), as_fwc(14, " [ 2 ]"), as_fwc(15, " [ 6 ]"));
        of_fwc(event.register$item("gtceu:raw_talc"), as_fwc(0, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_tantalite"), as_fwc(0, " [ 4 ]"), as_fwc(6, " [ 5 ]"), as_fwc(9, " [ 5 ]"), as_fwc(12, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_tetrahedrite"), as_fwc(6, " [ 8 ]"));
        of_fwc(event.register$item("gtceu:raw_thorium"), as_fwc(2, " [ 1, 4 ]"), as_fwc(10, " [ 1, 5 ]"), as_fwc(12, " [ 4 ]"), as_fwc(15, " [ 1 ]"));
        of_fwc(event.register$item("gtceu:raw_tin"), as_fwc(0, " [ 8 ]"));
        of_fwc(event.register$item("gtceu:raw_topaz"), as_fwc(1, " [ 1 ]"), as_fwc(6, " [ 10 ]"), as_fwc(9, " [ 1 ]"), as_fwc(15, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_tricalcium_phosphate"), as_fwc(0, " [ 10 ]"), as_fwc(3, " [ 3 ]"), as_fwc(11, " [ 3 ]"), as_fwc(15, " [ 7 ]"));
        of_fwc(event.register$item("gtceu:raw_trona"), as_fwc(8, " [ 1 ]"), as_fwc(14, " [ 4 ]"));
        of_fwc(event.register$item("gtceu:raw_tungstate"), as_fwc(3, " [ 2 ]"), as_fwc(13, " [ 3 ]"), as_fwc(14, " [ 7 ]"), as_fwc(15, " [ 5 ]"));
        of_fwc(event.register$item("gtceu:raw_uraninite"), as_fwc(2, " [ 1, 4 ]"), as_fwc(10, " [ 1, 5 ]"), as_fwc(12, " [ 4 ]"), as_fwc(13, " [ 6 ]"), as_fwc(15, " [ 1 ]"));
        of_fwc(event.register$item("gtceu:raw_vanadium_magnetite"), as_fwc(0, " [ 16 ]"), as_fwc(13, " [ 2 ]"));
        of_fwc(event.register$item("gtceu:raw_wulfenite"), as_fwc(1, " [ 8 ]"), as_fwc(6, " [ 6 ]"), as_fwc(7, " [ 2 ]"), as_fwc(14, " [ 3 ]"));
        of_fwc(event.register$item("gtceu:raw_yellow_garnet"), as_fwc(0, " [ 19 ]"));
        of_fwc(event.register$item("gtceu:raw_yellow_limonite"), as_fwc(0, " [ 2 ]"), as_fwc(6, " [ 9 ]"));
        of_fwc(event.register$item("gtceu:raw_zeolite"), as_fwc(0, " [ 5 ]"));
        of_fwc(event.register$item("gtceu:raw_zircon"), as_fwc(9, " [ 5 ]"), as_fwc(12, " [ 2 ]"));
        of_fwc(event.register$item("minecraft:raw_copper"), as_fwc(0, " [ 6 ]"), as_fwc(1, " [ 7 ]"), as_fwc(6, " [ 8 ]"), as_fwc(10, " [ 3 ]"));
        of_fwc(event.register$item("minecraft:raw_gold"), as_fwc(0, " [ 16 ]"), as_fwc(6, " [ 9 ]"), as_fwc(7, " [ 4 ]"), as_fwc(13, " [ 2 ]"), as_fwc(14, " [ 9 ]"));
        of_fwc(event.register$item("minecraft:raw_iron"), as_fwc(0, " [ 6 ]"));
        of_fwc(event.register$item("minecraft:ancient_debris"), as_fwc(6, " [ 0 ]"));

        of_fwc(event.register$fluid("gtceu:benzene"), as_fwc(12, " [ 5 ]"));
        of_fwc(event.register$fluid("gtceu:chlorine"), as_fwc(11, " [ 4 ]"));
        of_fwc(event.register$fluid("gtceu:coal_gas"), as_fwc(8, " [ 5 ]"));
        of_fwc(event.register$fluid("gtceu:deuterium"), as_fwc(5, " [ 3 ]"));
        of_fwc(event.register$fluid("gtceu:fluorine"), as_fwc(11, " [ 5 ]"));
        of_fwc(event.register$fluid("gtceu:helium_3"), as_fwc(2, " [ 5 ]"));
        of_fwc(event.register$fluid("gtceu:helium"), as_fwc(2, " [ 6 ]"));
        of_fwc(event.register$fluid("gtceu:hydrochloric_acid"), as_fwc(9, " [ 6 ]"));
        of_fwc(event.register$fluid("gtceu:krypton"), as_fwc(7, " [ 6 ]"));
        of_fwc(event.register$fluid("gtceu:methane"), as_fwc(12, " [ 6 ]"));
        of_fwc(event.register$fluid("gtceu:natural_gas"), as_fwc(0, " [ 29 ]"), as_fwc(6, " [ 14 ]"));
        of_fwc(event.register$fluid("gtceu:neon"), as_fwc(7, " [ 5 ]"));
        of_fwc(event.register$fluid("gtceu:nitric_acid"), as_fwc(10, " [ 6 ]"));
        of_fwc(event.register$fluid("gtceu:oil_heavy"), as_fwc(0, " [ 25 ]"));
        of_fwc(event.register$fluid("gtceu:oil_light"), as_fwc(0, " [ 28 ]"));
        of_fwc(event.register$fluid("gtceu:oil_medium"), as_fwc(0, " [ 26 ]"));
        of_fwc(event.register$fluid("gtceu:oil"), as_fwc(0, " [ 27 ]"));
        of_fwc(event.register$fluid("gtceu:radon"), as_fwc(3, " [ 4 ]"));
        of_fwc(event.register$fluid("gtceu:charcoal_byproducts"), as_fwc(12, " [ 7 ]"));
        of_fwc(event.register$fluid("gtceu:salt_water"), as_fwc(0, " [ 24 ]"));
        of_fwc(event.register$fluid("gtceu:sulfuric_acid"), as_fwc(4, " [ 4 ]"));
        of_fwc(event.register$fluid("gtceu:unknowwater"), as_fwc(15, " [ 8 ]"));
        of_fwc(event.register$fluid("gtceu:xenon"), as_fwc(7, " [ 8 ]"));
        of_fwc(event.register$fluid("gtceu:barnarda_air"), as_fwc(15, " [ 0 ]"));
        of_fwc(event.register$fluid("minecraft:lava"), as_fwc(0, " [ 0 ]"), as_fwc(6, " [ 0, 13 ]"));

        String[] worlds = { "overworld", "reactor", "moon", "mars", "venus", "mercury", "nether", "ceres", "io", "ganymede", "pluto", "enceladus", "titan", "end", "glacio", "barnarda" };
        String[][] keys_fluid = {
                { "material.gtceu.salt_water", "material.gtceu.oil_heavy", "material.gtceu.oil_medium", "material.gtceu.oil_heavy", "material.gtceu.oil_light", "material.gtceu.natural_gas", "material.gtceu.lava" },
                {},
                { "material.gtceu.helium_3", "material.gtceu.helium" },
                { "material.gtceu.radon" },
                { "material.gtceu.sulfuric_acid" },
                { "material.gtceu.deuterium" },
                { "material.gtceu.lava" },
                { "material.gtceu.neon", "material.gtceu.krypton", "material.gtceu.xenon" },
                { "material.gtceu.coal_gas" },
                { "material.gtceu.hydrochloric_acid" },
                { "material.gtceu.nitric_acid" },
                { "material.gtceu.chlorine", "material.gtceu.fluorine" },
                { "material.gtceu.benzene", "material.gtceu.methane", "material.gtceu.charcoal_byproducts" },
                {},
                {},
                { "material.gtceu.barnarda_air", "material.gtceu.unknowwater" }
        };
        String[][] keys_item = {
                {},
                { "material.gtceu.blue_topaz", "material.gtceu.topaz", "material.gtceu.nether_quartz", "material.gtceu.barite", "material.gtceu.quartzite", "material.gtceu.beryllium", "material.gtceu.emerald", "material.gtceu.saltpeter", "material.gtceu.electrotine", "material.gtceu.alunite", "material.gtceu.certus_quartz", "material.gtceu.sulfur", "material.gtceu.sphalerite", "material.gtceu.stibnite", "material.gtceu.molybdenum", "material.gtceu.wulfenite", "material.gtceu.powellite", "material.gtceu.molybdenite" },
                { "material.gtceu.plutonium", "material.gtceu.thorium", "material.gtceu.uraninite", "material.gtceu.aluminium", "material.gtceu.ilmenite", "material.gtceu.bauxite", "material.gtceu.neodymium", "material.gtceu.monazite", "material.gtceu.bastnasite", "material.gtceu.pitchblende" },
                { "material.gtceu.palladium", "material.gtceu.platinum", "material.gtceu.cooperite", "material.gtceu.bornite", "material.gtceu.scheelite", "material.gtceu.tungstate", "material.gtceu.lithium", "material.gtceu.pyrochlore" },
                { "material.gtceu.magnesite", "material.gtceu.desh" },
                { "material.gtceu.cobalt", "material.gtceu.calorite" },
                { "block.minecraft.ancient_debris", "material.gtceu.tetrahedrite", "material.gtceu.chalcocite" },
                { "material.gtceu.ostrum" },
                { "material.gtceu.trona", "material.gtceu.celestine", "material.gtceu.naquadah" },
                { "material.gtceu.zircon" },
                {},
                {},
                { "material.gtceu.desh" },
                { "block.minecraft.dragon_egg", "block.minecraft.dragon_head", "item.minecraft.dragon_breath", "item.minecraft.chorus_fruit", "block.minecraft.chorus_flower" },
                { "item.kubejs.glacio_spirit", "item.ad_astra.ice_shard" },
                { "block.kubejs.barnarda_log", "block.kubejs.barnarda_leaves" }
        };
        int[][] cs_fluid = {
                { 24, 25, 26, 27, 28, 29, 0 },
                {},
                { 5, 6 },
                { 4 },
                { 4 },
                { 3 },
                { 13 },
                { 5, 6, 8 },
                { 5 },
                { 6 },
                { 6 },
                { 4, 5 },
                { 5, 6, 7 },
                {},
                {},
                { 0, 8 }
        };
        int[][] cs_item = {
                {},
                { 1, 1, 2, 2, 2, 3, 3, 4, 4, 4, 5, 6, 6, 7, 8, 8, 8, 8 },
                { 1, 1, 1, 2, 2, 2, 3, 3, 3, 4 },
                { 1, 1, 1, 1, 2, 2, 2, 3 },
                { 3, 3 },
                { 1, 1 },
                { 0, 8, 10 },
                { 4 },
                { 1, 1, 2 },
                { 5 },
                {},
                {},
                { 3 },
                { 0, 0, 0, 0, 0 },
                { 0, 0 },
                { 0, 0 }
        };
        for (int i = 0; i < worlds.length; i++) {
            var keys = keys_fluid[i];
            var components = new ArrayList<Component>();
            for (int j = 0; j < keys.length; j++) {
                components.add(as_fwc(Component.translatable(keys[j]).withStyle(SpecialComponent.color_fluid), String.format(" [ %s ]", cs_fluid[i][j])));
            }
            to_fwc(event.register$item(String.format("gtlcore:world_fragments_%s", worlds[i])), components);
        }
        for (int i = 0; i < worlds.length; i++) {
            var keys = keys_item[i];
            var components = new ArrayList<Component>();
            for (int j = 0; j < keys.length; j++) {
                components.add(as_fwc(keys[j].startsWith("material") ? Component.translatable("tagprefix.raw", Component.translatable(keys[j])).withStyle(SpecialComponent.color_item) : Component.translatable(keys[j]).withStyle(SpecialComponent.color_item), String.format(" [ %s ]", cs_item[i][j])));
            }
            to_fwc(event.register$item(String.format("gtlcore:world_fragments_%s", worlds[i])), components);
        }
    }
}
