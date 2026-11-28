package org.gtlcore.gtlcore.common.data.machines;

import org.gtlcore.gtlcore.common.data.GTLMachines;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gregtechceu.gtceu.common.data.machines.GTResearchMachines;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public class TooltipsModify {

    private static final String ENERGY_CAPACITY_KEY = "gtceu.universal.tooltip.energy_storage_capacity";
    private static final Set<String> WORKING_AREA_KEYS = Set.of(
            "gtceu.universal.tooltip.working_area",
            "gtceu.universal.tooltip.working_area_chunks",
            "gtceu.universal.tooltip.working_area_max",
            "gtceu.universal.tooltip.working_area_chunks_max");

    public static void init() {
        MachineDefinition hpca_computation_component = GTResearchMachines.HPCA_COMPUTATION_COMPONENT;
        hpca_computation_component.setTooltipBuilder(hpca_computation_component.getTooltipBuilder().andThen((itemStack, components) -> components.set(3, Component.translatable("gtceu.machine.hpca.component_type.computation_cwut", 8))));
        hpca_computation_component.setTooltipBuilder(hpca_computation_component.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition hpca_advanced_computation_component = GTResearchMachines.HPCA_ADVANCED_COMPUTATION_COMPONENT;
        hpca_advanced_computation_component.setTooltipBuilder(hpca_advanced_computation_component.getTooltipBuilder().andThen((itemStack, components) -> components.set(3, Component.translatable("gtceu.machine.hpca.component_type.computation_cwut", 32))));
        hpca_advanced_computation_component.setTooltipBuilder(hpca_advanced_computation_component.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition electric_blast_furnace = GTMachines.ELECTRIC_BLAST_FURNACE;
        electric_blast_furnace.setTooltipBuilder(electric_blast_furnace.getTooltipBuilder().andThen((itemStack, components) -> {
            components.add(1, Component.translatable("gtceu.machine.electric_blast_furnace.tooltip.a"));
            components.set(3, Component.translatable("gtceu.machine.perfect_oc"));
        }));
        electric_blast_furnace.setTooltipBuilder(electric_blast_furnace.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition steamExportBus = GTMachines.STEAM_EXPORT_BUS;
        steamExportBus.setTooltipBuilder(steamExportBus.getTooltipBuilder().andThen((itemStack, components) -> {
            components.add(1, Component.translatable("gtceu.machine.item_bus.export.tooltip"));
            components.add(2, Component.translatable("gtceu.universal.tooltip.item_storage_capacity", 16));
        }));
        steamExportBus.setTooltipBuilder(steamExportBus.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition steamImportBus = GTMachines.STEAM_IMPORT_BUS;
        steamImportBus.setTooltipBuilder(steamImportBus.getTooltipBuilder().andThen((itemStack, components) -> {
            components.add(1, Component.translatable("gtceu.machine.item_bus.import.tooltip"));
            components.add(2, Component.translatable("gtceu.universal.tooltip.item_storage_capacity", 16));
        }));
        steamImportBus.setTooltipBuilder(steamImportBus.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition steamOven = GTMachines.STEAM_OVEN;
        steamOven.setTooltipBuilder(steamOven.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition bronzeLargeBoiler = GTMachines.LARGE_BOILER_BRONZE;
        bronzeLargeBoiler.setTooltipBuilder(bronzeLargeBoiler.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition steelBoiler = GTMachines.LARGE_BOILER_STEEL;
        steelBoiler.setTooltipBuilder(steelBoiler.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition titaniumBoiler = GTMachines.LARGE_BOILER_TITANIUM;
        titaniumBoiler.setTooltipBuilder(titaniumBoiler.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition tungstensteelLargeBoiler = GTMachines.LARGE_BOILER_TUNGSTENSTEEL;
        tungstensteelLargeBoiler.setTooltipBuilder(tungstensteelLargeBoiler.getTooltipBuilder().andThen(GTLMachines.GTL_MODIFY));

        MachineDefinition largeChemicalReactor = GTMachines.LARGE_CHEMICAL_REACTOR;
        largeChemicalReactor.setTooltipBuilder(largeChemicalReactor.getTooltipBuilder().andThen((itemStack, components) -> components.add(1, Component.translatable("gtceu.machine.perfect_oc"))));

        MachineDefinition researchStation = GTResearchMachines.RESEARCH_STATION;
        researchStation.setTooltipBuilder(researchStation.getTooltipBuilder().andThen((itemStack, components) -> {
            components.add(1, Component.translatable("gtceu.machine.research_station.tooltip.0"));
            components.add(2, Component.translatable("gtceu.machine.research_station.tooltip.1"));
            components.add(3, Component.translatable("gtceu.machine.research_station.tooltip.2"));
            components.add(4, Component.translatable("gtceu.machine.research_station.tooltip.3"));
        }));

        gtlcore$applyEnergyTooltips();
    }

    private static void gtlcore$applyEnergyTooltips() {
        for (Class<?> holder : new Class<?>[] { GTMachines.class, GTResearchMachines.class, GTLMachines.class }) {
            gtlcore$applyToHolder(holder);
        }
        for (String name : new String[] {
                "com.hepdd.gtmthings.data.CustomMachines",
                "com.hepdd.gtmthings.data.WirelessMachines",
                "com.hepdd.gtmthings.data.CreativeMachines" }) {
            try {
                gtlcore$applyToHolder(Class.forName(name));
            } catch (ClassNotFoundException ignored) {
                // GTMThings is optional; skip when absent.
            }
        }
    }

    private static void gtlcore$applyToHolder(Class<?> holder) {
        for (Field field : holder.getFields()) {
            Class<?> type = field.getType();
            if (MachineDefinition.class.isAssignableFrom(type)) {
                try {
                    gtlcore$applyDefinition((MachineDefinition) field.get(null));
                } catch (IllegalAccessException ignored) {
                    // Static machine definitions are public; skip anything unexpected.
                }
            } else if (type.isArray() && MachineDefinition.class.isAssignableFrom(type.getComponentType())) {
                try {
                    Object array = field.get(null);
                    if (array == null) {
                        continue;
                    }
                    for (Object element : (Object[]) array) {
                        if (element instanceof MachineDefinition definition) {
                            gtlcore$applyDefinition(definition);
                        }
                    }
                } catch (IllegalAccessException ignored) {
                    // Static machine definitions are public; skip anything unexpected.
                }
            }
        }
    }

    private static void gtlcore$applyDefinition(MachineDefinition definition) {
        if (definition == null) {
            return;
        }
        final MachineDefinition target = definition;
        BiConsumer<ItemStack, List<Component>> builder = definition.getTooltipBuilder();
        BiConsumer<ItemStack, List<Component>> appender = (stack, components) -> gtlcore$modifyEnergyTooltip(target, components);
        definition.setTooltipBuilder(builder == null ? appender : builder.andThen(appender));
    }

    private static void gtlcore$modifyEnergyTooltip(MachineDefinition definition, List<Component> components) {
        components.removeIf(line -> {
            if (!(line.getContents() instanceof TranslatableContents contents)) {
                return false;
            }
            return WORKING_AREA_KEYS.contains(contents.getKey()) && gtlcore$isZeroArea(contents.getArgs());
        });
        int ratingTier = definition.getTier();
        if (ratingTier < 0 || ratingTier >= GTValues.V.length) {
            ratingTier = gtlcore$tierFromComponents(components);
        }
        if (ratingTier < 0 || ratingTier >= GTValues.V.length) {
            return;
        }
        long voltage = GTValues.V[ratingTier];
        if (voltage <= 0L) {
            return;
        }
        for (int i = 0; i < components.size(); i++) {
            Component line = components.get(i);
            if (!(line.getContents() instanceof TranslatableContents contents) ||
                    !ENERGY_CAPACITY_KEY.equals(contents.getKey())) {
                continue;
            }
            long capacity = gtlcore$capacity(contents.getArgs());
            if (capacity <= 0L) {
                continue;
            }
            double amps = capacity / (voltage * 20.0);
            MutableComponent rating = Component.literal(gtlcore$openBracket() + String.format(java.util.Locale.ROOT, "%.1f", amps) + " A·s ");
            rating.append(gtlcore$tierName(ratingTier));
            rating.append(Component.literal(gtlcore$closeBracket()));
            components.set(i, line.copy().append(rating));
        }
    }

    private static String gtlcore$openBracket() {
        return gtlcore$usesFullwidthBracket() ? "（" : " (";
    }

    private static String gtlcore$closeBracket() {
        return gtlcore$usesFullwidthBracket() ? "）" : ")";
    }

    private static boolean gtlcore$usesFullwidthBracket() {
        return Language.getInstance().getOrDefault("gtceu.universal.tooltip.voltage_in").indexOf('（') >= 0;
    }

    private static Component gtlcore$tierName(int tier) {
        String formatted = GTValues.VNF[tier];
        if (formatted.length() > 1 && formatted.charAt(0) == '§') {
            ChatFormatting formatting = ChatFormatting.getByCode(formatted.charAt(1));
            if (formatting != null) {
                return Component.literal(GTValues.VN[tier]).withStyle(formatting);
            }
        }
        return Component.literal(GTValues.VN[tier]);
    }

    private static long gtlcore$capacity(Object[] args) {
        if (args.length == 0) {
            return 0L;
        }
        Object value = args[0];
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.replace(",", ""));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static boolean gtlcore$isZeroArea(Object[] args) {
        if (args.length < 2) {
            return false;
        }
        return gtlcore$number(args[0]) == 0L && gtlcore$number(args[1]) == 0L;
    }

    private static long gtlcore$number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.replace(",", ""));
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
    }

    private static int gtlcore$tierFromComponents(List<Component> components) {
        for (Component line : components) {
            if (!(line.getContents() instanceof TranslatableContents contents)) {
                continue;
            }
            for (Object arg : contents.getArgs()) {
                if (arg instanceof String text) {
                    for (int i = 0; i < GTValues.VN.length; i++) {
                        if (text.equals(GTValues.VN[i])) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }
}
