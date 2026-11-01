package org.gtlcore.gtlcore.network.packet;

import org.gtlcore.gtlcore.integration.terminal.host.TerminalActions;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import com.glodblock.github.glodium.network.packet.IMessage;

public final class CTerminalAction implements IMessage<CTerminalAction> {

    public enum Action {
        BUILD,
        UPGRADE,
        DISMANTLE_AE,
        DISMANTLE_INVENTORY
    }

    private BlockPos controllerPos;
    private Action action;
    private boolean noHatch;
    private boolean preferAe;
    private boolean mirrorMode;
    private int repeatCount;
    private String blockType;
    private int blockTier;

    public CTerminalAction() {}

    public CTerminalAction(BlockPos controllerPos, Action action, boolean noHatch,
                           boolean preferAe, boolean mirrorMode, int repeatCount,
                           String blockType, int blockTier) {
        this.controllerPos = controllerPos;
        this.action = action;
        this.noHatch = noHatch;
        this.preferAe = preferAe;
        this.mirrorMode = mirrorMode;
        this.repeatCount = repeatCount;
        this.blockType = blockType;
        this.blockTier = blockTier;
    }

    @Override
    public void toBytes(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(controllerPos);
        buffer.writeEnum(action);
        buffer.writeBoolean(noHatch);
        buffer.writeBoolean(preferAe);
        buffer.writeBoolean(mirrorMode);
        buffer.writeVarInt(repeatCount);
        buffer.writeUtf(blockType, 32);
        buffer.writeVarInt(blockTier);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buffer) {
        controllerPos = buffer.readBlockPos();
        action = buffer.readEnum(Action.class);
        noHatch = buffer.readBoolean();
        preferAe = buffer.readBoolean();
        mirrorMode = buffer.readBoolean();
        repeatCount = buffer.readVarInt();
        blockType = buffer.readUtf(32);
        blockTier = buffer.readVarInt();
    }

    @Override
    public void onMessage(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            TerminalActions.perform(serverPlayer, controllerPos, action, noHatch,
                    repeatCount, preferAe, mirrorMode, blockType, blockTier);
        }
    }

    @Override
    public Class<CTerminalAction> getPacketClass() {
        return CTerminalAction.class;
    }

    @Override
    public boolean isClient() {
        return false;
    }
}
