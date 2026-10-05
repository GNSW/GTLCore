package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.BloomClient;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class LevelRendererMixin {

    @org.spongepowered.asm.mixin.Unique
    private int gtlcore$bloom$dirtyNesting;

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void gtlcore$bloom$dirty(int x, int y, int z, boolean important, CallbackInfo ci) {
        if (gtlcore$bloom$dirtyNesting == 0) BloomClient.MESHES.dirty(x, y, z);
    }

    // Embeddium overwrites these methods and bypasses vanilla's setSectionDirty calls.
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void gtlcore$bloom$area(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo ci) {
        if (gtlcore$bloom$dirtyNesting++ == 0) gtlcore$bloom$dirtyArea(x0, y0, z0, x1, y1, z1);
    }

    @Inject(method = "setBlocksDirty", at = @At("RETURN"))
    private void gtlcore$bloom$areaEnd(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo ci) {
        gtlcore$bloom$dirtyNesting--;
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"))
    private void gtlcore$bloom$block(BlockPos pos, boolean important, CallbackInfo ci) {
        if (gtlcore$bloom$dirtyNesting++ == 0)
            BloomClient.MESHES.dirtyBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("RETURN"))
    private void gtlcore$bloom$blockEnd(BlockPos pos, boolean important, CallbackInfo ci) {
        gtlcore$bloom$dirtyNesting--;
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void gtlcore$bloom$neighbors(int x, int y, int z, CallbackInfo ci) {
        if (gtlcore$bloom$dirtyNesting++ == 0)
            for (int sx = x - 1; sx <= x + 1; sx++) for (int sy = y - 1; sy <= y + 1; sy++) for (int sz = z - 1; sz <= z + 1; sz++)
                BloomClient.MESHES.dirty(sx, sy, sz);
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("RETURN"))
    private void gtlcore$bloom$neighborsEnd(int x, int y, int z, CallbackInfo ci) {
        gtlcore$bloom$dirtyNesting--;
    }

    @org.spongepowered.asm.mixin.Unique
    private static void gtlcore$bloom$dirtyArea(int x0, int y0, int z0, int x1, int y1, int z1) {
        BloomClient.MESHES.dirtyArea(x0, y0, z0, x1, y1, z1);
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void gtlcore$bloom$reload(CallbackInfo ci) {
        BloomClient.requestRebuild();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void gtlcore$bloom$matrices(PoseStack pose, float ticks, long time, boolean outline, Camera camera,
                                        GameRenderer renderer, LightTexture light, Matrix4f projection, CallbackInfo ci) {
        BloomClient.capture(pose.last().pose(), projection, camera.getPosition());
    }
}
