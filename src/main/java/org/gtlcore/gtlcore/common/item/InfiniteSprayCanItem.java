package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.common.item.spray.InfiniteSprayCanUI;

import com.gregtechceu.gtceu.api.item.ComponentItem;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
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
        if (player instanceof ServerPlayer serverPlayer) HeldItemUIFactory.INSTANCE.openUI(serverPlayer, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public ModularUI createUI(Player player, HeldItemUIFactory.HeldItemHolder holder) {
        return InfiniteSprayCanUI.create(holder, player);
    }
}
