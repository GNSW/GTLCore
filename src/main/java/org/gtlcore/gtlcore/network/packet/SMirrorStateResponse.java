package org.gtlcore.gtlcore.network.packet;

import org.gtlcore.gtlcore.integration.terminal.host.TerminalHostPanel;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

import com.glodblock.github.glodium.network.packet.IMessage;

public final class SMirrorStateResponse implements IMessage<SMirrorStateResponse> {

    private BlockPos controllerPos;
    private boolean mirrored;

    public SMirrorStateResponse() {}

    public SMirrorStateResponse(BlockPos controllerPos, boolean mirrored) {
        this.controllerPos = controllerPos;
        this.mirrored = mirrored;
    }

    @Override
    public void toBytes(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(controllerPos);
        buffer.writeBoolean(mirrored);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buffer) {
        controllerPos = buffer.readBlockPos();
        mirrored = buffer.readBoolean();
    }

    @Override
    public void onMessage(Player player) {
        TerminalHostPanel.acceptMirrorState(controllerPos, mirrored);
    }

    @Override
    public Class<SMirrorStateResponse> getPacketClass() {
        return SMirrorStateResponse.class;
    }

    @Override
    public boolean isClient() {
        return true;
    }
}
