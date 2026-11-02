package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.common.item.spray.InfiniteSprayCanUI;

import com.gregtechceu.gtceu.api.item.ComponentItem;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class InfiniteSprayCanItem extends ComponentItem {

    public InfiniteSprayCanItem(Properties properties) {
        super(properties.stacksTo(1));
        attachComponents(new InfiniteSprayCanBehavior());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        player.startUsingItem(hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return Integer.MAX_VALUE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!(entity instanceof ServerPlayer player)) return;
        if (!player.isShiftKeyDown()) {
            player.stopUsingItem();
            return;
        }
        if (getUseDuration(stack) - remaining < InfiniteSprayCanBehavior.CONFIG_HOLD_TICKS) return;
        InteractionHand hand = player.getUsedItemHand();
        player.stopUsingItem();
        HeldItemUIFactory.INSTANCE.openUI(player, hand);
    }

    @Override
    public ModularUI createUI(Player player, HeldItemUIFactory.HeldItemHolder holder) {
        return InfiniteSprayCanUI.create(holder, player);
    }
}
