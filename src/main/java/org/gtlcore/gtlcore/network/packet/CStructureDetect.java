package org.gtlcore.gtlcore.network.packet;

import org.gtlcore.gtlcore.api.pattern.StructureDetection;
import org.gtlcore.gtlcore.common.item.UltimateTerminalBehavior;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.glodblock.github.glodium.network.packet.IMessage;

/**
 * Sent by the client when the ultimate terminal is used with Ctrl held on a multiblock controller.
 */
public class CStructureDetect implements IMessage<CStructureDetect> {

    private static final double MAX_DETECT_DISTANCE = 8.0D;

    private BlockPos pos;

    public CStructureDetect() {}

    public CStructureDetect(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
    }

    @Override
    public void onMessage(Player player) {
        if (!(player instanceof ServerPlayer) || pos == null) return;
        ItemStack held = player.getMainHandItem();
        if (!UltimateTerminalBehavior.isUltimateTerminal(held)) return;
        var level = player.level();
        if (!level.isLoaded(pos)) return;
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DETECT_DISTANCE * MAX_DETECT_DISTANCE) return;
        if (MetaMachine.getMachine(level, pos) instanceof IMultiController controller) {
            StructureDetection.detect(player, controller, UltimateTerminalBehavior.getIsFlip(held),
                    UltimateTerminalBehavior.getRepeatCount(held));
        }
    }

    @Override
    public Class<CStructureDetect> getPacketClass() {
        return CStructureDetect.class;
    }

    @Override
    public boolean isClient() {
        return false;
    }
}
