ForgeEvents.onEvent("net.minecraftforge.event.entity.player.ItemTooltipEvent", event => {
    if (!LDLib.isClient()) return
    function addfull_colortooltip(text) {
        event.getToolTip().add(Component.literal(TextUtil.full_color(text)))
    }
    function adddark_purplish_redtooltip(text) {
        event.getToolTip().add(Component.literal(TextUtil.dark_purplish_red(text)))
    }
    function addwhite_bluetooltip(text) {
        event.getToolTip().add(Component.literal(TextUtil.white_blue(text)))
    }
    function addpurplish_redtooltip(text) {
        event.getToolTip().add(Component.literal(TextUtil.purplish_red(text)))
    }
    function addgoldentooltip(text) {
        event.getToolTip().add(Component.literal(TextUtil.golden(text)))
    }
    function adddark_greentooltip(text) {
        event.getToolTip().add(Component.literal(TextUtil.dark_green(text)))
    }
    function addtooltip(text) {
        event.getToolTip().add(Component.literal("§7" + text))
    }
    function unknown() {
        addtooltip(Text.translatable("kubejs.tooltip.misc.1").getString())
        event.getToolTip().add(Text.translatable("kubejs.tooltip.tier_prefix").append(Component.literal(TextUtil.white_blue(Text.translatable("kubejs.tooltip.unknown").getString()))))
    }
    const tiers = ["ulv", "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv", "uhv", "uev", "uiv", "uxv", "opv", "max"]
    tiers.forEach((suprachronal) => {
        if (event.getItemStack().getId() == "kubejs:suprachronal_" + suprachronal) {
            addtooltip(Text.translatable("kubejs.tooltip.misc.2").getString())
            addwhite_bluetooltip(Text.translatable("kubejs.tooltip.tier_circuit", suprachronal.toUpperCase()).getString())
        }
    })
    tiers.slice(0, 12).forEach((magneto_resonatic) => {
        if (event.getItemStack().getId() == "kubejs:circuit_resonatic_" + magneto_resonatic) {
            addtooltip("§d" + Text.translatable("kubejs.tooltip.tier_circuit", magneto_resonatic.toUpperCase()).getString())
        }
    })
    switch (event.getItemStack().getId()) {
        case "gtceu:dimensionally_transcendent_dirt_forge":
            addfull_colortooltip(Text.translatable("kubejs.tooltip.misc.3").getString())
            break
        case "gtceu:door_of_create":
            addwhite_bluetooltip(Text.translatable("kubejs.tooltip.misc.4").getString())
            break
        case "kubejs:create_ultimate_battery":
            addtooltip(Text.translatable("kubejs.tooltip.misc.5").getString())
            unknown()
            break
        case "kubejs:suprachronal_mainframe_complex":
            addtooltip(Text.translatable("kubejs.tooltip.misc.6").getString())
            unknown()
            break
        case "kubejs:supracausal_mainframe":
            addtooltip(Text.translatable("kubejs.tooltip.misc.7").getString())
            addfull_colortooltip(Text.translatable("kubejs.tooltip.tier.max").getString())
            break
        case "kubejs:supracausal_computer":
            addtooltip(Text.translatable("kubejs.tooltip.misc.8").getString())
            addfull_colortooltip(Text.translatable("kubejs.tooltip.tier.opv").getString())
            break
        case "kubejs:supracausal_assembly":
            addtooltip(Text.translatable("kubejs.tooltip.misc.9").getString())
            addfull_colortooltip(Text.translatable("kubejs.tooltip.tier.uxv").getString())
            break
        case "kubejs:supracausal_processor":
            addtooltip(Text.translatable("kubejs.tooltip.misc.10").getString())
            addfull_colortooltip(Text.translatable("kubejs.tooltip.tier.uiv").getString())
            break
        case "kubejs:cosmic_assembly":
            addtooltip(Text.translatable("kubejs.tooltip.misc.11").getString())
            adddark_purplish_redtooltip(Text.translatable("kubejs.tooltip.tier.uiv").getString())
            break
        case "kubejs:cosmic_computer":
            addtooltip(Text.translatable("kubejs.tooltip.misc.12").getString())
            adddark_purplish_redtooltip(Text.translatable("kubejs.tooltip.tier.uxv").getString())
            break
        case "kubejs:cosmic_mainframe":
            addtooltip(Text.translatable("kubejs.tooltip.misc.13").getString())
            adddark_purplish_redtooltip(Text.translatable("kubejs.tooltip.tier.opv").getString())
            break
        case "kubejs:cosmic_processor":
            addtooltip(Text.translatable("kubejs.tooltip.misc.14").getString())
            adddark_purplish_redtooltip(Text.translatable("kubejs.tooltip.tier.uev").getString())
            break
        case "kubejs:exotic_assembly":
            addtooltip(Text.translatable("kubejs.tooltip.misc.15").getString())
            addpurplish_redtooltip(Text.translatable("kubejs.tooltip.tier.uev").getString())
            break
        case "kubejs:exotic_computer":
            addtooltip(Text.translatable("kubejs.tooltip.misc.16").getString())
            addpurplish_redtooltip(Text.translatable("kubejs.tooltip.tier.uiv").getString())
            break
        case "kubejs:exotic_mainframe":
            addtooltip(Text.translatable("kubejs.tooltip.misc.17").getString())
            addpurplish_redtooltip(Text.translatable("kubejs.tooltip.tier.uxv").getString())
            break
        case "kubejs:exotic_processor":
            addtooltip(Text.translatable("kubejs.tooltip.misc.18").getString())
            addpurplish_redtooltip(Text.translatable("kubejs.tooltip.tier.uhv").getString())
            break
        case "kubejs:optical_assembly":
            addtooltip(Text.translatable("kubejs.tooltip.misc.19").getString())
            addgoldentooltip(Text.translatable("kubejs.tooltip.tier.uhv").getString())
            break
        case "kubejs:optical_computer":
            addtooltip(Text.translatable("kubejs.tooltip.misc.20").getString())
            addgoldentooltip(Text.translatable("kubejs.tooltip.tier.uev").getString())
            break
        case "kubejs:optical_mainframe":
            addtooltip(Text.translatable("kubejs.tooltip.misc.21").getString())
            addgoldentooltip(Text.translatable("kubejs.tooltip.tier.uiv").getString())
            break
        case "kubejs:optical_processor":
            addtooltip(Text.translatable("kubejs.tooltip.misc.22").getString())
            addgoldentooltip(Text.translatable("kubejs.tooltip.tier.uv").getString())
            break
        case "kubejs:bioware_assembly":
            addtooltip(Text.translatable("kubejs.tooltip.misc.23").getString())
            adddark_greentooltip(Text.translatable("kubejs.tooltip.tier.uv").getString())
            break
        case "kubejs:bioware_computer":
            addtooltip(Text.translatable("kubejs.tooltip.misc.24").getString())
            adddark_greentooltip(Text.translatable("kubejs.tooltip.tier.uhv").getString())
            break
        case "kubejs:bioware_mainframe":
            addtooltip(Text.translatable("kubejs.tooltip.misc.25").getString())
            adddark_greentooltip(Text.translatable("kubejs.tooltip.tier.uev").getString())
            break
        case "kubejs:bioware_processor":
            addtooltip(Text.translatable("kubejs.tooltip.misc.26").getString())
            adddark_greentooltip(Text.translatable("kubejs.tooltip.tier.zpm").getString())
    }
})