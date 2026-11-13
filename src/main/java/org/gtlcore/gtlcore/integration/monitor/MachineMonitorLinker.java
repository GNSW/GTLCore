package org.gtlcore.gtlcore.integration.monitor;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public final class MachineMonitorLinker extends Item {

    public static final String IDENTITY = "gtlcore_machine_monitor_identity";
    private static final String TARGET = "MonitorTarget";

    public MachineMonitorLinker() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        var pos = context.getClickedPos();
        if (!player.mayBuild() || !level.mayInteract(player, pos)) return InteractionResult.FAIL;
        if (level.getBlockEntity(pos) instanceof MachineMonitorBlockEntity panel) {
            var master = MachineMonitorRuntime.get(player.serverLevel()).controller(panel);
            if (master == null) return message(player, panel.structureMessage());
            var tag = stack.getTag();
            if (tag == null || !tag.contains(TARGET)) return message(player, "select_first");
            if (!MachineMonitorRuntime.get(player.serverLevel()).mayEdit(player, master.getBlockPos())) return InteractionResult.FAIL;
            master.bind(tag.getCompound(TARGET));
            return message(player, "bound");
        }
        // Intentionally never resolve IMultiPart: the selected block itself must be a controller.
        if (!(MetaMachine.getMachine(level, pos) instanceof IMultiController)) return message(player, "controller_only");
        if (!player.isShiftKeyDown()) return message(player, "sneak_select");
        var entity = level.getBlockEntity(pos);
        var persistent = entity.getPersistentData();
        if (!persistent.hasUUID(IDENTITY)) {
            persistent.putUUID(IDENTITY, UUID.randomUUID());
            entity.setChanged();
        }
        var target = new CompoundTag();
        target.putString("Dimension", level.dimension().location().toString());
        target.putLong("Position", pos.asLong());
        target.putUUID("Identity", persistent.getUUID(IDENTITY));
        stack.getOrCreateTag().put(TARGET, target);
        return message(player, "selected");
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("monitor.gtlcore.machine.linker_help").withStyle(ChatFormatting.GRAY));
        var tag = stack.getTag();
        if (tag != null && tag.contains(TARGET)) {
            var target = tag.getCompound(TARGET);
            lines.add(Component.literal(target.getString("Dimension") + " " + net.minecraft.core.BlockPos.of(target.getLong("Position")).toShortString()).withStyle(ChatFormatting.AQUA));
        }
    }

    private static InteractionResult message(ServerPlayer player, String key) {
        return message(player, Component.translatable("monitor.gtlcore.machine." + key));
    }

    private static InteractionResult message(ServerPlayer player, Component text) {
        player.displayClientMessage(text, true);
        return InteractionResult.SUCCESS;
    }
}
