package org.gtlcore.gtlcore.client.forge;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.block.IMachineBlock;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class HatchPowerTooltip {

    private static final Pattern AMPERAGE = Pattern.compile("(?:^|_)(\\d+)a(?:_|$)");
    private static final NumberFormat NUMBER = NumberFormat.getIntegerInstance(Locale.ROOT);
    private static final String[] DESCRIPTION_KEYS = {
            "gtceu.machine.energy_hatch.input.tooltip",
            "gtceu.machine.energy_hatch.input_hi_amp.tooltip",
            "gtceu.machine.energy_hatch.output.tooltip",
            "gtceu.machine.energy_hatch.output_hi_amp.tooltip",
            "gtceu.machine.substation_hatch.input.tooltip",
            "gtceu.machine.substation_hatch.output.tooltip",
            "gtceu.machine.laser_hatch.source.tooltip",
            "gtceu.machine.laser_hatch.target.tooltip",
            "gtceu.machine.laser_hatch.both.tooltip",
            "gtceu.machine.laser_source_hatch.tooltip.0",
            "gtceu.machine.laser_target_hatch.tooltip.0",
            "gtmthings.machine.energy_hatch.input.tooltip",
            "gtmthings.machine.energy_hatch.output.tooltip",
            "gtmthings.machine.energy_hatch.target.tooltip",
            "gtmthings.machine.energy_hatch.source.tooltip"
    };

    private HatchPowerTooltip() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof BlockItem blockItem) ||
                !(blockItem.getBlock() instanceof IMachineBlock machineBlock))
            return;

        ResourceLocation id = machineBlock.getDefinition().getId();
        String namespace = id.getNamespace();
        if (!namespace.equals("gtceu") && !namespace.equals("gtmthings") && !namespace.equals("gtladditions")) return;

        String path = id.getPath();
        int tier = tier(path);
        if (tier < 0) return;

        boolean laser = path.contains("laser_source_hatch") || path.contains("laser_target_hatch");
        boolean energyInput = path.contains("energy_input_hatch") || path.contains("substation_input_hatch");
        boolean energyOutput = path.contains("energy_output_hatch") || path.contains("substation_output_hatch");
        if (!laser && !energyInput && !energyOutput) return;

        BigInteger voltage = BigInteger.valueOf(GTValues.V[tier]);
        BigInteger amperage = BigInteger.valueOf(amperage(path));
        BigInteger power = voltage.multiply(amperage);
        BigInteger capacity = laser || energyOutput ? power.multiply(BigInteger.valueOf(64)) : power.multiply(BigInteger.valueOf(16));
        boolean input = laser ? path.contains("laser_target_hatch") : energyInput;

        event.getToolTip().add(Component.translatable(input ? "tooltip.gtlcore.hatch_input_power" :
                "tooltip.gtlcore.hatch_output_power", NUMBER.format(power)).withStyle(ChatFormatting.GREEN));
        event.getToolTip().add(Component.translatable("tooltip.gtlcore.hatch_capacity", NUMBER.format(capacity))
                .withStyle(ChatFormatting.RED));
        moveAfterDescription(event.getToolTip());
    }

    private static void moveAfterDescription(List<Component> tooltip) {
        int insert = 1;
        for (int i = 1; i < tooltip.size(); i++) {
            String line = tooltip.get(i).getString();
            for (String key : DESCRIPTION_KEYS) {
                if (line.equals(Component.translatable(key).getString())) {
                    insert = i + 1;
                    break;
                }
            }
            if (insert > 1) break;
        }
        if (insert >= tooltip.size() - 2) return;
        Component power = tooltip.remove(tooltip.size() - 2);
        Component capacity = tooltip.remove(tooltip.size() - 1);
        tooltip.add(insert, capacity);
        tooltip.add(insert, power);
    }

    private static int tier(String path) {
        int underscore = path.indexOf('_');
        String tier = underscore < 0 ? path : path.substring(0, underscore);
        return switch (tier) {
            case "ulv" -> GTValues.ULV;
            case "lv" -> GTValues.LV;
            case "mv" -> GTValues.MV;
            case "hv" -> GTValues.HV;
            case "ev" -> GTValues.EV;
            case "iv" -> GTValues.IV;
            case "luv" -> GTValues.LuV;
            case "zpm" -> GTValues.ZPM;
            case "uv" -> GTValues.UV;
            case "uhv" -> GTValues.UHV;
            case "uev" -> GTValues.UEV;
            case "uiv" -> GTValues.UIV;
            case "uxv" -> GTValues.UXV;
            case "opv" -> GTValues.OpV;
            case "max" -> GTValues.MAX;
            default -> -1;
        };
    }

    private static int amperage(String path) {
        Matcher matcher = AMPERAGE.matcher(path);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 2;
    }
}
