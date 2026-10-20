package org.gtlcore.gtlcore.common.event;

import java.util.Set;

/** Item groups approved in the tooltip inventory, including subsequent user corrections. */
public final class ModifiedItemTooltipRules {

    private static final Set<String> TIERS = Set.of(
            "ulv", "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv",
            "uhv", "uev", "uiv", "uxv", "opv", "max");

    private static final Set<String> GTCEU_ITEMS = Set.of(
            // M008-M010: both item and fluid ME parts.
            "me_input_bus", "me_input_hatch", "me_output_bus", "me_output_hatch",
            "me_stocking_input_bus", "me_stocking_input_hatch",
            // M012, M016-M019.
            "active_transformer", "high_performance_computation_array", "network_switch",
            "creative_computation_provider", "computation_receiver_hatch", "computation_transmitter_hatch",
            // M026, M056.
            "item_tag_filter", "fluid_tag_filter", "prospector.lv", "prospector.hv", "prospector.luv");

    private static final Set<String> GTCEU_TIERED_ITEMS = Set.of(
            // M001-M002, M005-M006, M011 (including the requested quantum tanks), M013, M022.
            "input_bus", "output_bus", "input_hatch", "output_hatch",
            "input_hatch_4x", "output_hatch_4x", "input_hatch_9x", "output_hatch_9x",
            "rotor_holder", "muffler_hatch", "quantum_chest", "quantum_tank",
            "fusion_reactor", "fluid_drilling_rig",
            // M024-M025 and S001.
            "conveyor_module", "robot_arm", "electric_pump", "fluid_regulator", "solar_panel");

    private static final Set<String> STEAM_MACHINES = Set.of(
            // M023: SimpleSteamMachine hosts, plus the solar boilers affected by S001.
            "alloy_smelter", "compressor", "extractor", "forge_hammer", "furnace",
            "macerator", "rock_crusher", "solar_boiler");

    private static final Set<String> AE2_ITEMS = Set.of(
            // M029, M031, M033, M035-M037, M039-M040.
            "creative_item_cell", "creative_fluid_cell", "pattern_provider", "cable_pattern_provider",
            "io_port", "terminal", "crafting_terminal", "wireless_terminal", "wireless_crafting_terminal",
            "pattern_encoding_terminal", "pattern_access_terminal", "storage_bus", "level_emitter",
            "energy_level_emitter");

    private static final Set<String> EXTENDED_AE_ITEMS = Set.of(
            // M030, M032, M034, M038-M040 (subclasses of the modified AE2 implementations), M043.
            "infinity_cell", "ex_pattern_provider", "ex_pattern_provider_part", "ex_io_port",
            "ex_pattern_access_part", "wireless_ex_pat", "pattern_modifier",
            "tag_storage_bus", "mod_storage_bus", "precise_storage_bus", "threshold_level_emitter");

    private static final Set<String> WIRELESS_TERMINALS = Set.of(
            // M036-M037, M044.
            "wireless_pattern_encoding_terminal", "wireless_pattern_access_terminal", "wireless_universal_terminal");

    private static final Set<String> STORAGE_CELL_PREFIXES = Set.of(
            "item_storage_cell_", "fluid_storage_cell_", "portable_item_cell_", "portable_fluid_cell_");
    private static final Set<String> STORAGE_CELL_SIZES = Set.of("1k", "4k", "16k", "64k", "256k");

    private static final Set<String> GTMTHINGS_ITEMS = Set.of(
            // M020, M046 (explicitly included after review), M048, M050, M054.
            "wireless_computation_receiver_hatch", "wireless_computation_transmitter_hatch",
            "me_export_buffer", "creative_energy_hatch", "creative_laser_hatch", "wireless_energy_monitor", "advanced_terminal");

    private static final Set<String> GTMTHINGS_TIERED_ITEMS = Set.of(
            // M045. Catalyst hatches remain excluded.
            "huge_item_import_bus", "huge_item_export_bus", "huge_dual_hatch");

    private ModifiedItemTooltipRules() {}

    public static boolean isModified(String namespace, String path) {
        return switch (namespace) {
            case "gtceu" -> GTCEU_ITEMS.contains(path) || isTiered(path, GTCEU_TIERED_ITEMS) ||
                    isSteamMachine(path) || path.equals("solar_panel");
            case "ae2" -> AE2_ITEMS.contains(path) || isStorageCell(path);
            case "expatternprovider" -> EXTENDED_AE_ITEMS.contains(path);
            case "ae2wtlib" -> WIRELESS_TERMINALS.contains(path);
            case "gtmthings" -> GTMTHINGS_ITEMS.contains(path) || isTiered(path, GTMTHINGS_TIERED_ITEMS);
            case "merequester" -> path.equals("requester") || path.equals("requester_terminal");
            case "ad_astra" -> path.equals("tier_1_rocket") || path.equals("tier_2_rocket") ||
                    path.equals("tier_3_rocket") || path.equals("tier_4_rocket") || path.equals("tier_1_rover");
            case "travelanchors" -> path.equals("travel_staff");
            default -> false;
        };
    }

    private static boolean isTiered(String path, Set<String> names) {
        int separator = path.indexOf('_');
        return separator > 0 && TIERS.contains(path.substring(0, separator)) &&
                names.contains(path.substring(separator + 1));
    }

    private static boolean isSteamMachine(String path) {
        return (path.startsWith("lp_steam_") || path.startsWith("hp_steam_")) &&
                STEAM_MACHINES.contains(path.substring(9));
    }

    private static boolean isStorageCell(String path) {
        for (String prefix : STORAGE_CELL_PREFIXES) {
            if (path.startsWith(prefix) && STORAGE_CELL_SIZES.contains(path.substring(prefix.length()))) {
                return true;
            }
        }
        return false;
    }
}
