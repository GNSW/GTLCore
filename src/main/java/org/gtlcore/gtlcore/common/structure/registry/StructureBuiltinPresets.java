package org.gtlcore.gtlcore.common.structure.registry;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialType;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.Preset;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.Structure;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import static org.gtlcore.gtlcore.common.structure.material.StructureMaterialType.*;
import static org.gtlcore.gtlcore.common.structure.model.StructureDefinition.Structure.structure;
import static org.gtlcore.gtlcore.common.structure.registry.StructureTemplateRegistry.tr;

/**
 * 内置预设数据（GTO 移植 + demo）。注册 API 见 {@link StructureTemplateRegistry}。
 */
final class StructureBuiltinPresets {

    // —— 类型标签 ——
    private static final Component TYPE_PLATFORM = tr("type.platform", "平台", "Platform");
    private static final Component TYPE_PLATFORM_3X3 = tr("type.platform_3x3", "平台(3×3)", "Platform (3×3)");
    private static final Component TYPE_PLATFORM_LARGE = tr("type.platform_large", "平台(大)", "Platform (Large)");
    private static final Component TYPE_ROAD = tr("type.road", "道路", "Road");
    private static final Component TYPE_FACTORY = tr("type.factory", "工厂", "Factory");

    // —— 通用显示名 ——
    private static final Component NAME_CHESSBOARD = tr("name.vibrant_checkerboard", "高饱和棋盘", "Vibrant Checkerboard");
    private static final Component NAME_PANEL = tr("name.vibrant_panel", "高饱和嵌板", "Vibrant Panel");
    private static final Component NAME_SMALL_PLOT = tr("name.small_plot", "小型地块", "Small plot");
    private static final Component NAME_MEDIUM_PLOT = tr("name.medium_plot", "中型地块", "Medium plot");
    private static final Component NAME_LARGE_PLOT = tr("name.large_plot", "大型地块", "Large plot");
    private static final Component NAME_MIXED_PLOT = tr("name.mixed_plot", "多用途地块", "Mixed-use plot");

    private StructureBuiltinPresets() {}

    static void registerAll() {
        registerGtoLibraries();
    }

    private static void registerGtoLibraries() {
        StructureTemplateRegistry.register(Preset.preset("platform_standard_library_alpha")
                .displayName(tr("preset.alpha", "平台标准预设库-α", "Platform Standard Library-α"))
                .description(tr("preset.alpha.desc", "高饱和棋盘/嵌板等（GTO 移植）", "High-sat chessboard/panel (from GTO)"))
                .addStructure(s("high_saturation_chessboard_1_blue_pink", "high_saturation_chessboard_1",
                        "high_saturation_chessboard_blue_pink.json", TYPE_PLATFORM,
                        NAME_CHESSBOARD, "1×1 蓝·粉", "1×1 blue·pink", "阿龙-还有一件事", FRAME, 144))
                .addStructure(s("high_saturation_chessboard_1_orange_white", "high_saturation_chessboard_1",
                        "high_saturation_chessboard_orange_white.json", TYPE_PLATFORM,
                        NAME_CHESSBOARD, "1×1 橙·白", "1×1 orange·white", "阿龙-还有一件事", FRAME, 144))
                .addStructure(s("high_saturation_chessboard_1_yellow_lime", "high_saturation_chessboard_1",
                        "high_saturation_chessboard_yellow_lime.json", TYPE_PLATFORM,
                        NAME_CHESSBOARD, "1×1 黄·青", "1×1 yellow·lime", "阿龙-还有一件事", FRAME, 144))
                .addStructure(s("high_saturation_chessboard_3_blue_pink", "high_saturation_chessboard_3",
                        "high_saturation_chessboard_blue_pink.json", TYPE_PLATFORM_3X3,
                        NAME_CHESSBOARD, "3×3 蓝·粉", "3×3 blue·pink", "阿龙-还有一件事", FRAME, 1296))
                .addStructure(s("high_saturation_chessboard_3_orange_white", "high_saturation_chessboard_3",
                        "high_saturation_chessboard_orange_white.json", TYPE_PLATFORM_3X3,
                        NAME_CHESSBOARD, "3×3 橙·白", "3×3 orange·white", "阿龙-还有一件事", FRAME, 1296))
                .addStructure(s("high_saturation_chessboard_3_yellow_lime", "high_saturation_chessboard_3",
                        "high_saturation_chessboard_yellow_lime.json", TYPE_PLATFORM_3X3,
                        NAME_CHESSBOARD, "3×3 黄·青", "3×3 yellow·lime", "阿龙-还有一件事", FRAME, 1296))
                .addStructure(s("high_saturation_panel_1_white_pink", "high_saturation_panel_1",
                        "high_saturation_panel_white_pink.json", TYPE_PLATFORM,
                        NAME_PANEL, "1×1 白嵌粉", "1×1 white embed pink", "阿龙-还有一件事", FRAME, 144))
                .addStructure(s("high_saturation_panel_1_black_blue", "high_saturation_panel_1",
                        "high_saturation_panel_black_blue.json", TYPE_PLATFORM,
                        NAME_PANEL, "1×1 黑嵌蓝", "1×1 black embed blue", "阿龙-还有一件事", FRAME, 144))
                .addStructure(s("high_saturation_panel_3_white_pink", "high_saturation_panel_1",
                        "high_saturation_panel_white_pink.json", TYPE_PLATFORM_3X3,
                        NAME_PANEL, "3×3 白嵌粉", "3×3 white embed pink", "阿龙-还有一件事", FRAME, 144))
                .addStructure(s("high_saturation_panel_3_black_blue", "high_saturation_panel_1",
                        "high_saturation_panel_black_blue.json", TYPE_PLATFORM_3X3,
                        NAME_PANEL, "3×3 黑嵌蓝", "3×3 black embed blue", "阿龙-还有一件事", FRAME, 144))
                .addStructure(s("white_floor_with_greenery_and_orange_and_yellow_edges",
                        "white_floor_with_greenery_and_orange_and_yellow_edges",
                        "white_floor_with_greenery_and_orange_and_yellow_edges.json", TYPE_PLATFORM_LARGE,
                        NAME_PANEL, "2×2 带绿化的镶橙黄边白色地板", "2×2 white floor with greenery",
                        "阿龙-还有一件事", FRAME, 576))
                .build());

        StructureTemplateRegistry.register(Preset.preset("platform_standard_library_beta")
                .displayName(tr("preset.beta", "平台标准预设库-β", "Platform Standard Library-β"))
                .description(tr("preset.beta.desc", "小/中/大地块地基（GTO 移植）", "Plot foundations (from GTO)"))
                .addStructure(s("small_plot_stone_foundation", "small_plot_stone_foundation",
                        "small_plot_stone_foundation.json", TYPE_PLATFORM,
                        NAME_SMALL_PLOT, "3×3-石质地基", "3×3 plot-stone", "疏影", FRAME, 1296))
                .addStructure(s("small_plot_concrete_foundation", "small_plot_concrete_foundation",
                        "small_plot_concrete_foundation.json", TYPE_PLATFORM,
                        NAME_SMALL_PLOT, "3×3-混凝土地基", "3×3 plot-concrete", "疏影", FRAME, 1296))
                .addStructure(s("medium_sized_plot_stone_foundation", "medium_sized_plot_stone_foundation",
                        "medium_sized_plot_stone_foundation.json", TYPE_PLATFORM,
                        NAME_MEDIUM_PLOT, "5×5-石质地基", "5×5 plot-stone", "疏影", FRAME, 3600))
                .addStructure(s("medium_sized_plot_concrete_foundation", "medium_sized_plot_concrete_foundation",
                        "medium_sized_plot_concrete_foundation.json", TYPE_PLATFORM,
                        NAME_MEDIUM_PLOT, "5×5-混凝土地基", "5×5 plot-concrete", "疏影", FRAME, 3600))
                .addStructure(s("large_plot_stone_foundation", "large_plot_stone_foundation",
                        "large_plot_stone_foundation.json", TYPE_PLATFORM,
                        NAME_LARGE_PLOT, "7×7-石质地基", "7×7 plot-stone", "疏影", FRAME, 7056))
                .addStructure(s("large_plot_concrete_foundation", "large_plot_concrete_foundation",
                        "large_plot_concrete_foundation.json", TYPE_PLATFORM,
                        NAME_LARGE_PLOT, "7×7-混凝土地基", "7×7 plot-concrete", "疏影", FRAME, 7056))
                .addStructure(s("mixed_use_plot_stone_foundation", "mixed_use_plot_stone_foundation",
                        "mixed_use_plot_stone_foundation.json", TYPE_PLATFORM,
                        NAME_MIXED_PLOT, "9|2×2-石质地基", "9|2×2 plot-stone", "疏影", FRAME, 5184))
                .addStructure(s("mixed_use_plot_concrete_foundation", "mixed_use_plot_concrete_foundation",
                        "mixed_use_plot_concrete_foundation.json", TYPE_PLATFORM,
                        NAME_MIXED_PLOT, "9|2×2-混凝土地基", "9|2×2 plot-concrete", "疏影", FRAME, 5184))
                .build());

        StructureTemplateRegistry.register(Preset.preset("platform_extension_library")
                .displayName(tr("preset.extension", "平台扩展预设库", "Platform Extension Library"))
                .description(tr("preset.extension.desc", "公路地板与灯带地板", "Road & light floors"))
                .addStructure(structure("light_colored_road_floor_1")
                        .type(TYPE_PLATFORM)
                        .displayName(tr("name.light_road_floor_1", "浅色公路地板 1", "Light road floor 1"))
                        .source("神官")
                        .resource(id("light_colored_road_floor_1"))
                        .symbolMap(id("light_colored_road_floor_1.json"))
                        .materials(FRAME, 100)
                        .build())
                .addStructure(structure("light_colored_road_floor_2")
                        .type(TYPE_PLATFORM)
                        .displayName(tr("name.light_road_floor_2", "浅色公路地板 2", "Light road floor 2"))
                        .source("神官")
                        .resource(id("light_colored_road_floor_2"))
                        .symbolMap(id("light_colored_road_floor_2.json"))
                        .materials(FRAME, 20)
                        .build())
                .addStructure(structure("light_colored_road_floor_3")
                        .type(TYPE_PLATFORM)
                        .displayName(tr("name.light_road_floor_3", "浅色公路地板 3", "Light road floor 3"))
                        .source("神官")
                        .resource(id("light_colored_road_floor_3"))
                        .symbolMap(id("light_colored_road_floor_3.json"))
                        .materials(FRAME, 676)
                        .build())
                .addStructure(structure("light_colored_road_floor_4")
                        .type(TYPE_PLATFORM)
                        .displayName(tr("name.light_road_floor_4", "浅色公路地板 4", "Light road floor 4"))
                        .source("神官")
                        .resource(id("light_colored_road_floor_4"))
                        .symbolMap(id("light_colored_road_floor_4.json"))
                        .materials(FRAME, 676)
                        .build())
                .addStructure(structure("gray_floor_with_lights_1")
                        .type(TYPE_PLATFORM)
                        .displayName(tr("name.gray_lights_floor_1", "灯带灰地板 1", "Gray lights floor 1"))
                        .source("神官")
                        .resource(id("gray_floor_with_lights_1"))
                        .symbolMap(id("gray_floor_with_lights_1.json"))
                        .materials(FINISH, 100)
                        .build())
                .addStructure(structure("gray_floor_with_lights_2")
                        .type(TYPE_PLATFORM)
                        .displayName(tr("name.gray_lights_floor_2", "灯带灰地板 2", "Gray lights floor 2"))
                        .source("神官")
                        .resource(id("gray_floor_with_lights_2"))
                        .symbolMap(id("gray_floor_with_lights_2.json"))
                        .materials(FINISH, 20)
                        .build())
                .addStructure(structure("gray_floor_with_lights_3")
                        .type(TYPE_PLATFORM)
                        .displayName(tr("name.gray_lights_floor_3", "灯带灰地板 3", "Gray lights floor 3"))
                        .source("神官")
                        .resource(id("gray_floor_with_lights_3"))
                        .symbolMap(id("gray_floor_with_lights_3.json"))
                        .materials(FINISH, 676)
                        .build())
                .addStructure(structure("gray_floor_with_lights_4")
                        .type(TYPE_PLATFORM)
                        .displayName(tr("name.gray_lights_floor_4", "灯带灰地板 4", "Gray lights floor 4"))
                        .source("神官")
                        .resource(id("gray_floor_with_lights_4"))
                        .symbolMap(id("gray_floor_with_lights_4.json"))
                        .materials(FINISH, 676)
                        .build())
                .build());

        StructureTemplateRegistry.register(Preset.preset("factory_standard_library")
                .displayName(tr("preset.factory", "工厂标准预设库", "Factory Standard Library"))
                .description(tr("preset.factory.desc", "厂房结构", "Factory buildings"))
                .addStructure(structure("standard_factory_building")
                        .type(TYPE_FACTORY)
                        .displayName(tr("name.standard_factory", "标准厂房", "Standard factory building"))
                        .source("疏影")
                        .resource(id("standard_factory_building"))
                        .symbolMap(id("standard_factory_building.json"))
                        .materials(FRAME, 400)
                        .materials(PLATE, 100)
                        .build())
                .addStructure(structure("long_corridor_factory_building")
                        .type(TYPE_FACTORY)
                        .displayName(tr("name.long_corridor_factory", "长廊厂房", "Long corridor factory building"))
                        .source("疏影")
                        .resource(id("long_corridor_factory_building"))
                        .symbolMap(id("long_corridor_factory_building.json"))
                        .materials(FRAME, 800)
                        .materials(PLATE, 800)
                        .build())
                .build());
    }

    private static Structure s(
                               String name,
                               String resource,
                               String map,
                               Component type,
                               Component displayName,
                               String descCn,
                               String descEn,
                               String source,
                               StructureMaterialType mat,
                               int cost) {
        return structure(name)
                .type(type)
                .displayName(displayName)
                .description(tr("desc." + StructureTemplateRegistry.keyOf(descEn), descCn, descEn))
                .source(source)
                .resource(id(resource))
                .symbolMap(id(map))
                .materials(mat, cost)
                .build();
    }

    private static ResourceLocation id(String path) {
        return GTLCore.id(path);
    }
}
