package org.gtlcore.gtlcore.common.data;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.cover.CoverDefinition;
import com.gregtechceu.gtceu.client.renderer.cover.*;
import com.gregtechceu.gtceu.common.cover.ConveyorCover;
import com.gregtechceu.gtceu.common.cover.FluidRegulatorCover;
import com.gregtechceu.gtceu.common.cover.PumpCover;
import com.gregtechceu.gtceu.common.cover.RobotArmCover;
import com.gregtechceu.gtceu.common.data.GTCovers;

import com.hepdd.gtmthings.GTMThings;
import com.hepdd.gtmthings.common.cover.WirelessEnergyReceiveCover;

import java.util.Locale;

public class GTLCovers {

    public static final CoverDefinition ELECTRIC_PUMP_MAX = GTCovers.register(
            "pump.max",
            (def, coverable, side) -> new PumpCover(def, coverable, side, GTValues.MAX),
            PumpCoverRenderer.INSTANCE);

    public static final CoverDefinition FLUID_REGULATORS_ULV = GTCovers.register(
            "fluid_regulators.ulv",
            (def, coverable, side) -> new FluidRegulatorCover(def, coverable, side, GTValues.ULV),
            FluidRegulatorCoverRenderer.INSTANCE);

    public static final CoverDefinition FLUID_REGULATOR_MAX = GTCovers.register(
            "fluid_regulator.max",
            (def, coverable, side) -> new FluidRegulatorCover(def, coverable, side, GTValues.MAX),
            FluidRegulatorCoverRenderer.INSTANCE);

    public static final CoverDefinition CONVEYOR_MODULE_MAX = GTCovers.register(
            "conveyor.max",
            (def, coverable, side) -> new ConveyorCover(def, coverable, side, GTValues.MAX),
            ConveyorCoverRenderer.INSTANCE);

    public static final CoverDefinition ROBOT_ARM_MAX = GTCovers.register(
            "robot_arm.max",
            (def, coverable, side) -> new RobotArmCover(def, coverable, side, GTValues.MAX),
            RobotArmCoverRenderer.INSTANCE);

    public static final CoverDefinition ROBOT_ARM_ULV = GTCovers.register(
            "robot_arm.ulv",
            (def, coverable, side) -> new RobotArmCover(def, coverable, side, GTValues.ULV),
            RobotArmCoverRenderer.INSTANCE);

    public final static CoverDefinition MAX_WIRELESS_ENERGY_RECEIVE = registerTieredWirelessCover(
            "wireless_energy_receive", 1, GTValues.MAX);

    public final static CoverDefinition MAX_WIRELESS_ENERGY_RECEIVE_4A = registerTieredWirelessCover(
            "4a_wireless_energy_receive", 4, GTValues.MAX);

    public static CoverDefinition registerTieredWirelessCover(String id, int amperage, int tier) {
        String name = id + "." + GTValues.VN[tier].toLowerCase(Locale.ROOT);
        return GTCovers.register(name, (holder, coverable, side) -> new WirelessEnergyReceiveCover(holder, coverable, side, tier, amperage),
                new SimpleCoverRenderer(GTMThings.id("block/cover/overlay_" + (amperage == 1 ? "" : "4a_") + "wireless_energy_receive")));
    }

    public static int itemTransferRate(int tier) {
        if (tier <= 0 || tier >= 15) return Integer.MAX_VALUE;
        return 2 << (2 * tier);
    }

    public static long fluidTransferRate(int tier) {
        if (tier <= 0) return Integer.MAX_VALUE;
        if (tier >= 30) return Long.MAX_VALUE;
        return 64L << (2 * (tier - 1));
    }

    public static void init() {}
}
