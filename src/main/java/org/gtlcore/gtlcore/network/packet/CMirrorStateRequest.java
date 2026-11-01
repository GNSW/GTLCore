package org.gtlcore.gtlcore.network.packet;

import org.gtlcore.gtlcore.integration.terminal.host.MirrorIntent;
import org.gtlcore.gtlcore.network.GTLNetworkHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import com.glodblock.github.glodium.network.packet.IMessage;

public final class CMirrorStateRequest implements IMessage<CMirrorStateRequest> {

    private BlockPos controllerPos;

    public CMirrorStateRequest() {}

    public CMirrorStateRequest(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
    }

    @Override
    public void toBytes(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(controllerPos);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buffer) {
        controllerPos = buffer.readBlockPos();
    }

    @Override
    public void onMessage(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            boolean mirrored = MirrorIntent.resolve(serverPlayer.level(), controllerPos);
            GTLNetworkHandler.INSTANCE.sendTo(
                    new SMirrorStateResponse(controllerPos, mirrored), serverPlayer);
        }
    }

    @Override
    public Class<CMirrorStateRequest> getPacketClass() {
        return CMirrorStateRequest.class;
    }

    @Override
    public boolean isClient() {
        return false;
    }
}
