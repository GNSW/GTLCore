package org.gtlcore.gtlcore.common.machine.generator;

import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.integration.ae2.wireless.CuriosCompat;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IElectricItem;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.TieredMachine;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.data.GTItems;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.energy.IEnergyStorage;

import com.hepdd.gtmthings.api.misc.WirelessEnergyManager;
import com.hepdd.gtmthings.utils.TeamUtil;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.*;

public class WirelessChargingStationMachine extends TieredMachine implements IFancyUIMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            WirelessChargingStationMachine.class, MetaMachine.MANAGED_FIELD_HOLDER);

    private static final int FIELD_GENERATOR_COUNT = 16;
    private static final int CHARGE_INTERVAL = 10;
    private static final Map<Item, Integer> FIELD_GENERATOR_TIERS = new HashMap<>();

    static {
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_LV.asItem(), GTValues.LV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_MV.asItem(), GTValues.MV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_HV.asItem(), GTValues.HV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_EV.asItem(), GTValues.EV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_IV.asItem(), GTValues.IV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_LuV.asItem(), GTValues.LuV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_ZPM.asItem(), GTValues.ZPM);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_UV.asItem(), GTValues.UV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_UHV.asItem(), GTValues.UHV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_UEV.asItem(), GTValues.UEV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_UIV.asItem(), GTValues.UIV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_UXV.asItem(), GTValues.UXV);
        FIELD_GENERATOR_TIERS.put(GTItems.FIELD_GENERATOR_OpV.asItem(), GTValues.OpV);
        FIELD_GENERATOR_TIERS.put(GTLItems.FIELD_GENERATOR_MAX.asItem(), GTValues.MAX);
    }

    @Persisted
    private final NotifiableItemStackHandler fieldGenerators = new NotifiableItemStackHandler(
            this, 1, IO.NONE, IO.BOTH,
            slots -> new ItemStackTransfer(1) {

                @Override
                public int getSlotLimit(int slot) {
                    return FIELD_GENERATOR_COUNT;
                }
            })
            .setFilter(stack -> FIELD_GENERATOR_TIERS.containsKey(stack.getItem()));

    @Persisted
    @DescSynced
    private String owner;
    @Persisted
    @DescSynced
    private String ownerDisplay;
    private TickableSubscription tickSubs;
    private int tickCounter;

    public WirelessChargingStationMachine(IMachineBlockEntity holder) {
        super(holder, GTValues.HV);
    }

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        tickSubs = subscribeServerTick(tickSubs, this::tick);
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (tickSubs != null) tickSubs.unsubscribe();
    }

    public void bindTeam(Player player) {
        UUID team = TeamUtil.getTeamUUID(player.getUUID());
        if (team != null) {
            owner = team.toString();
            ownerDisplay = TeamUtil.GetName(player).getString();
            markDirty();
        }
    }

    public void unbindTeam() {
        owner = null;
        ownerDisplay = null;
        markDirty();
    }

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        if (!player.level().isClientSide &&
                player.getItemInHand(hand).is(GTItems.TOOL_DATA_STICK.asItem())) {
            bindTeam(player);
        }
        return true;
    }

    private UUID ownerId() {
        if (owner == null) return null;
        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Component ownerName() {
        if (ownerDisplay != null && !ownerDisplay.isBlank()) {
            return Component.literal(ownerDisplay);
        }
        UUID id = ownerId();
        if (id == null) return Component.translatable("gui.gtlcore.wireless_charging_station.unbound");
        try {
            return TeamUtil.GetName(getLevel(), id);
        } catch (RuntimeException ignored) {
            return Component.literal(owner);
        }
    }

    private boolean isTeamMember(Player player) {
        UUID id = ownerId();
        if (id == null) return false;
        UUID team = TeamUtil.getTeamUUID(player.getUUID());
        return team != null && team.equals(id);
    }

    private String wirelessEu() {
        UUID id = ownerId();
        return formatEu(id == null ? null : WirelessEnergyManager.getUserEU(id));
    }

    private static String formatEu(BigInteger value) {
        if (value == null || value.signum() == 0) return "0";
        String digits = value.abs().toString();
        if (digits.length() <= 3) return value.toString();
        int exponent = digits.length() - 1;
        String mantissa = digits.charAt(0) + "." + digits.substring(1, 3);
        while (mantissa.endsWith("0")) mantissa = mantissa.substring(0, mantissa.length() - 1);
        if (mantissa.endsWith(".")) mantissa = mantissa.substring(0, mantissa.length() - 1);
        return (value.signum() < 0 ? "-" : "") + mantissa + "e" + exponent;
    }

    private void tick() {
        if (Objects.requireNonNull(getLevel()).isClientSide || ++tickCounter < CHARGE_INTERVAL) return;
        tickCounter = 0;
        UUID userId = ownerId();
        if (userId == null || !hasFieldGenerators()) return;

        MinecraftServer server = getLevel().getServer();
        if (server == null) return;

        long remaining = Math.round(chargeRate() * CHARGE_INTERVAL);
        List<ChargeAction> actions = new ArrayList<>();
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        for (ServerPlayer player : players) {
            if (!isTeamMember(player)) continue;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                remaining = collect(player.getInventory().getItem(slot), remaining, actions);
            }
            for (CuriosCompat.CurioSlot slot : CuriosCompat.getCurioSlots(player)) {
                remaining = collect(slot.stack(), remaining, actions);
            }
            if (remaining <= 0) break;
        }
        if (actions.isEmpty()) return;

        long total = 0;
        for (ChargeAction action : actions) total += action.amount();
        if (!WirelessEnergyManager.addEUToGlobalEnergyMap(userId, -total, this)) return;
        for (ChargeAction action : actions) apply(action);
        for (ServerPlayer player : players) {
            player.getInventory().setChanged();
            CuriosCompat.writeAllCurios(player);
        }
    }

    private long collect(ItemStack stack, long remaining, List<ChargeAction> actions) {
        if (remaining <= 0 || stack.isEmpty()) return remaining;

        IElectricItem electric = GTCapabilityHelper.getElectricItem(stack);
        if (electric != null) {
            long accepted = electric.charge(remaining, voltageTier(), true, true);
            if (accepted > 0) {
                actions.add(new ChargeAction(stack, accepted, true));
                remaining -= accepted;
            }
            return remaining;
        }

        IEnergyStorage energy = GTCapabilityHelper.getForgeEnergyItem(stack);
        if (energy != null) {
            int accepted = energy.receiveEnergy((int) Math.min(remaining, Integer.MAX_VALUE), true);
            if (accepted > 0) {
                actions.add(new ChargeAction(stack, accepted, false));
                remaining -= accepted;
            }
        }
        return remaining;
    }

    private void apply(ChargeAction action) {
        if (action.electric()) {
            IElectricItem electric = GTCapabilityHelper.getElectricItem(action.stack());
            if (electric != null) electric.charge(action.amount(), voltageTier(), true, false);
        } else {
            IEnergyStorage energy = GTCapabilityHelper.getForgeEnergyItem(action.stack());
            if (energy != null) energy.receiveEnergy((int) action.amount(), false);
        }
    }

    private boolean hasFieldGenerators() {
        return fieldGeneratorTier() >= 0 && fieldGeneratorCount() >= 1;
    }

    private int fieldGeneratorTier() {
        ItemStack stack = fieldGenerators.getStackInSlot(0);
        Integer tier = FIELD_GENERATOR_TIERS.get(stack.getItem());
        return tier == null ? -1 : tier;
    }

    private int voltageTier() {
        int tier = fieldGeneratorTier();
        return tier < 0 ? GTValues.HV : tier;
    }

    private int fieldGeneratorCount() {
        ItemStack stack = fieldGenerators.getStackInSlot(0);
        return FIELD_GENERATOR_TIERS.containsKey(stack.getItem()) ? stack.getCount() : 0;
    }

    private double amperage() {
        return fieldGeneratorCount() * 0.25D;
    }

    private String amperageText() {
        double amps = amperage();
        if (amps == Math.rint(amps)) return Long.toString((long) amps);
        return String.format(Locale.ROOT, "%.2f", amps).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private double chargeRate() {
        return (double) GTValues.V[voltageTier()] * amperage();
    }

    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(0, 0, 176, 132);
        group.addWidget(new SlotWidget(fieldGenerators.storage, 0, 80, 24, true, true)
                .setBackground(GuiTextures.SLOT));
        group.addWidget(new LabelWidget(8, 8, () -> Component.translatable(
                "gui.gtlcore.wireless_charging_station.owner", ownerName()).getString()));
        group.addWidget(new LabelWidget(8, 84, () -> fieldGeneratorCount() > 0 ?
                Component.translatable("gui.gtlcore.wireless_charging_station.power",
                        amperageText(), GTValues.VNF[voltageTier()]).getString() :
                ""));
        group.addWidget(new LabelWidget(8, 100, () -> Component.translatable(
                "gui.gtlcore.wireless_charging_station.status",
                owner != null && hasFieldGenerators() ?
                        Component.translatable("gui.gtlcore.wireless_charging_station.active") :
                        Component.translatable("gui.gtlcore.wireless_charging_station.inactive"))
                .getString()));
        group.addWidget(new LabelWidget(8, 116, () -> Component.translatable(
                "gui.gtlcore.wireless_charging_station.wireless_eu", wirelessEu()).getString()));
        return group;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("block.gtlcore.wireless_charging_station");
    }

    private record ChargeAction(ItemStack stack, long amount, boolean electric) {}
}
