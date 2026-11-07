package org.gtlcore.gtlcore.mixin.ftbchunks;

import net.minecraft.client.gui.screens.Screen;

import dev.ftb.mods.ftbchunks.client.gui.ChunkScreen;
import dev.ftb.mods.ftbchunks.net.RequestChunkChangePacket;
import dev.ftb.mods.ftblibrary.math.XZ;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(value = ChunkScreen.class, remap = false)
public abstract class ChunkScreenMixin {

    private static final long MAX_RECT_CHUNKS = 4096L;

    @Shadow
    private Set<XZ> selectedChunks;

    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void gtlcore$rectClaimOnRelease(MouseButton button, CallbackInfo ci) {
        if (!Screen.hasControlDown() || selectedChunks.isEmpty()) return;
        boolean left = button.isLeft();
        if (!left && !button.isRight()) return;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (XZ pos : selectedChunks) {
            minX = Math.min(minX, pos.x());
            maxX = Math.max(maxX, pos.x());
            minZ = Math.min(minZ, pos.z());
            maxZ = Math.max(maxZ, pos.z());
        }
        long count = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (count > MAX_RECT_CHUNKS) {
            selectedChunks.clear();
            return;
        }
        Set<XZ> chunks = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) chunks.add(new XZ(x, z));
        }
        new RequestChunkChangePacket(
                RequestChunkChangePacket.ChunkChangeOp.create(button.isLeft(), Screen.hasShiftDown()), chunks)
                .sendToServer();
        selectedChunks.clear();
    }
}
