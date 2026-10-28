package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.common.item.manipulator.*;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorNetwork;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.item.ComponentItem;
import com.gregtechceu.gtceu.api.item.component.ElectricStats;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import appeng.api.implementations.blockentities.IWirelessAccessPoint;

import java.util.List;

public final class MatterManipulatorBehavior extends ComponentItem {

    public static final int HOLD_TICKS = SharedConstants.TICKS_PER_SECOND;

    public MatterManipulatorBehavior(Properties properties) {
        super(properties.stacksTo(1));
        attachComponents(ElectricStats.createElectricItem(ConfigHolder.INSTANCE.manipulator.capacity, GTValues.ZPM));
    }

    public static boolean isMarker(ItemStack stack) {
        return stack.hasTag() && stack.getTag().hasUUID("ManipulatorPlanMarker");
    }

    public static String statusKey(ItemStack stack) {
        String text = stack.getOrCreateTag().getString("ManipulatorStatus");
        int end = text.indexOf(' ');
        if (end >= 0) text = text.substring(0, end);
        int detail = text.indexOf('|');
        return detail < 0 ? text : text.substring(0, detail);
    }

    public static String statusDetail(ItemStack stack) {
        String text = stack.getOrCreateTag().getString("ManipulatorStatus");
        String key = statusKey(stack);
        return text.length() > key.length() ? text.substring(key.length()).replace('|', ' ').trim() : "";
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || isMarker(stack)) return InteractionResult.FAIL;
        if (!context.getLevel().isClientSide && stack.getOrCreateTag().getBoolean("ManipulatorBindNext")) {
            if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof IWirelessAccessPoint)) {
                player.displayClientMessage(Component.translatable("mm.error.access_point"), true);
                return InteractionResult.CONSUME;
            }
            ManipulatorNetwork.LINK.link(stack, GlobalPos.of(context.getLevel().dimension(), context.getClickedPos()));
            stack.getOrCreateTag().remove("ManipulatorBindNext");
            player.displayClientMessage(Component.translatable("mm.status.bound"), true);
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown()) {
            begin(player, context.getHand(), context.getClickedPos());
        } else if (!context.getLevel().isClientSide) {
            select(stack, player, context.getClickedPos());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return onItemUseFirst(context.getItemInHand(), context);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (isMarker(stack)) return InteractionResultHolder.fail(stack);
        if (player.isShiftKeyDown()) {
            var hit = player.pick(player.getBlockReach(), 0, false);
            begin(player, hand, hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : BlockPos.containing(hit.getLocation()));
        } else if (player instanceof ServerPlayer serverPlayer) HeldItemUIFactory.INSTANCE.openUI(serverPlayer, hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void begin(Player player, InteractionHand hand, BlockPos pos) {
        if (player instanceof ServerPlayer serverPlayer) {
            ItemStack stack = player.getItemInHand(hand);
            CompoundTag tag = stack.getOrCreateTag();
            var settings = ManipulatorSettings.read(stack);
            var task = ManipulatorSavedData.get(serverPlayer.server).task(serverPlayer, stack);
            boolean resume = task != null && !task.cancelled && !task.finished();
            boolean selection = settings.a == null || settings.b == null || settings.template() && settings.anchor == null;
            tag.putLong("ManipulatorUsePos", pos.asLong());
            tag.putBoolean("ManipulatorExecuted", false);
            // Decide the gesture once: aborting a start must never fall back to selecting a point.
            tag.putBoolean("ManipulatorSelectOnRelease", selection && !resume);
        }
        player.startUsingItem(hand);
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return Integer.MAX_VALUE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!(entity instanceof ServerPlayer player)) return;
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.getBoolean("ManipulatorExecuted") && !player.isShiftKeyDown()) {
            tag.putBoolean("ManipulatorSelectOnRelease", false);
            player.stopUsingItem();
            return;
        }
        if (getUseDuration(stack) - remaining < HOLD_TICKS) return;
        if (!tag.getBoolean("ManipulatorExecuted")) {
            tag.putBoolean("ManipulatorExecuted", true);
            ManipulatorSavedData.get(player.server).start(player, stack, 0);
        }
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        if (entity instanceof ServerPlayer player && stack.getOrCreateTag().getBoolean("ManipulatorSelectOnRelease") &&
                !stack.getOrCreateTag().getBoolean("ManipulatorExecuted") && player.isShiftKeyDown() && player.containerMenu == player.inventoryMenu) {
            select(stack, player, BlockPos.of(stack.getOrCreateTag().getLong("ManipulatorUsePos")));
        }
        stack.removeTagKey("ManipulatorSelectOnRelease");
    }

    private static void select(ItemStack stack, Player player, BlockPos pos) {
        var settings = ManipulatorSettings.read(stack);
        String dimension = player.level().dimension().location().toString();
        if (settings.a != null && !settings.dimension.equals(dimension)) {
            player.displayClientMessage(Component.translatable("mm.error.dimension"), true);
            return;
        }
        settings.dimension = dimension;
        if (settings.paste && settings.template()) settings.anchor = pos;
        else if (settings.a == null || settings.b != null) {
            settings.a = pos;
            settings.b = null;
        } else settings.b = pos;
        settings.save(stack);
        player.displayClientMessage(Component.translatable("mm.status.selected", pos.toShortString()), true);
    }

    /** Packets contain only intents; the server resolves the hit and reads the world itself. */
    public static void action(ServerPlayer player, ItemStack stack, Action action, int material) {
        if (!(stack.getItem() instanceof MatterManipulatorBehavior) || isMarker(stack)) return;
        var s = ManipulatorSettings.read(stack);
        switch (action) {
            case COPY -> {
                s.mode = ManipulatorSettings.Mode.COPY;
                s.paste = false;
            }
            case CUT -> {
                s.mode = ManipulatorSettings.Mode.MOVE;
                s.paste = false;
            }
            case PASTE -> s.paste = true;
            case CLEAR -> {
                s.a = null;
                s.b = null;
                s.anchor = null;
                s.dimension = "";
            }
            case RESET -> {
                for (int i = 0; i < 3; i++) {
                    s.rotation[i] = 0;
                    s.mirror[i] = false;
                    s.repeat[i] = 1;
                    s.spacing[i] = 0;
                }
            }
            case PICK, WHITELIST -> {
                HitResult hit = player.pick(player.getBlockReach(), 0, false);
                if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return;
                BlockState state = player.level().getBlockState(block.getBlockPos());
                if (action == Action.WHITELIST) {
                    if (!s.whitelist.contains(state)) s.whitelist.add(state);
                } else {
                    if (material < 0) java.util.Arrays.fill(s.materials, state);
                    else if (material < s.materials.length) s.materials[material] = state;
                }
            }
        }
        s.save(stack);
        player.getInventory().setChanged();
    }

    public enum Action {
        COPY,
        CUT,
        PASTE,
        CLEAR,
        RESET,
        PICK,
        WHITELIST
    }

    @Override
    public ModularUI createUI(Player player, HeldItemUIFactory.HeldItemHolder holder) {
        return ManipulatorUI.create(holder, player);
    }

    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    public boolean isCorrectToolForDrops(BlockState state) {
        return !state.is(net.minecraftforge.common.Tags.Blocks.ORES);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        if (isMarker(stack)) {
            super.appendHoverText(stack, level, tooltip, flag);
            tooltip.add(Component.translatable("mm.marker").withStyle(ChatFormatting.GRAY));
            return;
        }
        var electric = GTCapabilityHelper.getElectricItem(stack);
        tooltip.add(Component.translatable("mm.tooltip.energy",
                Component.literal(FormattingUtil.formatNumbers(electric.getCharge())).withStyle(ChatFormatting.YELLOW),
                Component.literal(FormattingUtil.formatNumbers(electric.getMaxCharge())).withStyle(ChatFormatting.YELLOW)).withStyle(ChatFormatting.GOLD));
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("mm.tooltip.functions").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("mm.tooltip.mode",
                Component.translatable("mm.mode." + ManipulatorSettings.read(stack).mode.name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.GREEN)).withStyle(ChatFormatting.GRAY));
        boolean linked = stack.hasTag() && stack.getTag().contains("ManipulatorNetwork");
        tooltip.add(Component.translatable("mm.tooltip.network", Component.translatable(linked ? "mm.network_linked" : "mm.network_unlinked")
                .withStyle(linked ? ChatFormatting.GREEN : ChatFormatting.YELLOW)).withStyle(ChatFormatting.GRAY));
        if (stack.hasTag() && stack.getTag().contains("ManipulatorStatus")) {
            String status = statusKey(stack);
            if (!status.isEmpty()) {
                ChatFormatting color = status.startsWith("mm.error.") ? ChatFormatting.RED :
                        status.equals("mm.status.done") || status.equals("mm.status.undone") ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
                tooltip.add(Component.translatable("mm.tooltip.status", Component.translatable(status).withStyle(color)).withStyle(ChatFormatting.GRAY));
                String detail = statusDetail(stack);
                if (!detail.isEmpty()) tooltip.add(Component.literal(detail).withStyle(ChatFormatting.GRAY));
            }
        }
        tooltip.add(Component.empty());
        Component use = Component.keybind("key.use");
        Component sneakUse = Component.translatable("mm.tooltip.chord", Component.keybind("key.sneak"), use);
        tooltip.add(controlHint(Component.translatable("mm.tooltip.air", use), "mm.tooltip.menu"));
        tooltip.add(controlHint(Component.translatable("mm.tooltip.block", use), "mm.tooltip.select"));
        tooltip.add(controlHint(sneakUse, "mm.tooltip.tap"));
        tooltip.add(Component.translatable("mm.tooltip.control", sneakUse.copy().withStyle(ChatFormatting.YELLOW),
                Component.translatable("mm.tooltip.execute", (float) HOLD_TICKS / SharedConstants.TICKS_PER_SECOND)).withStyle(ChatFormatting.GRAY));
        tooltip.add(controlHint(Component.translatable("mm.tooltip.copy_keys", Component.keybind("key.gtlcore.manipulator.copy"),
                Component.keybind("key.gtlcore.manipulator.cut"), Component.keybind("key.gtlcore.manipulator.paste")), "mm.tooltip.copy"));
        tooltip.add(controlHint(Component.keybind("key.gtlcore.manipulator.clear"), "mm.tooltip.clear"));
        tooltip.add(controlHint(Component.keybind("key.pickItem"), "mm.tooltip.material"));
        tooltip.add(controlHint(Component.translatable("mm.tooltip.chord", Component.keybind("key.sneak"), Component.keybind("key.pickItem")), "mm.tooltip.whitelist"));
        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("mm.tooltip.settings").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("mm.tooltip.logistics").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("mm.tooltip.patterns").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("mm.tooltip.automatic").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("mm.tooltip.undo").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("mm.tooltip.safety").withStyle(ChatFormatting.GRAY));
    }

    private static Component controlHint(Component input, String description) {
        return Component.translatable("mm.tooltip.control", input.copy().withStyle(ChatFormatting.YELLOW), Component.translatable(description)).withStyle(ChatFormatting.GRAY);
    }
}
