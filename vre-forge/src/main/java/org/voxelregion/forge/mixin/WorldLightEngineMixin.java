/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.lighting.WorldLightProvider;

/** Dispatch at the native lighting API; no additional obligations are added to world subclasses. */
@Mixin(LevelLightEngine.class)
abstract class WorldLightEngineMixin implements WorldLightProvider.Access {
    @Unique private WorldLightProvider vre$lighting;
    @Override public WorldLightProvider vre$worldLighting() { return vre$lighting; }
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vre$create(LightChunkGetter getter, boolean block, boolean sky, CallbackInfo callback) {
        vre$lighting = WorldLightProvider.create(getter, block, sky);
    }
    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void vre$check(BlockPos pos, CallbackInfo ci) { if (vre$lighting != null) { vre$lighting.changed(pos); ci.cancel(); } }
    @Inject(method = "hasLightWork", at = @At("HEAD"), cancellable = true)
    private void vre$work(CallbackInfoReturnable<Boolean> ci) { if (vre$lighting != null) ci.setReturnValue(vre$lighting.hasWork()); }
    @Inject(method = "runLightUpdates", at = @At("HEAD"), cancellable = true)
    private void vre$run(CallbackInfoReturnable<Integer> ci) { if (vre$lighting != null) ci.setReturnValue(vre$lighting.drain()); }
    @Inject(method = "updateSectionStatus", at = @At("HEAD"), cancellable = true)
    private void vre$section(SectionPos pos, boolean empty, CallbackInfo ci) { if (vre$lighting != null) { vre$lighting.section(pos, empty); ci.cancel(); } }
    @Inject(method = "setLightEnabled", at = @At("HEAD"), cancellable = true)
    private void vre$enable(ChunkPos pos, boolean enabled, CallbackInfo ci) { if (vre$lighting != null) { vre$lighting.enable(pos, enabled); ci.cancel(); } }
    @Inject(method = "propagateLightSources", at = @At("HEAD"), cancellable = true)
    private void vre$sources(ChunkPos pos, CallbackInfo ci) { if (vre$lighting != null) { vre$lighting.sourceColumn(pos); ci.cancel(); } }
    @Inject(method = "getLayerListener", at = @At("HEAD"), cancellable = true)
    private void vre$layer(LightLayer layer, CallbackInfoReturnable<LayerLightEventListener> ci) { if (vre$lighting != null) ci.setReturnValue(vre$lighting.layer(layer)); }
    @Inject(method = "queueSectionData", at = @At("HEAD"), cancellable = true)
    private void vre$data(LightLayer layer, SectionPos pos, DataLayer data, CallbackInfo ci) { if (vre$lighting != null) { vre$lighting.queue(layer, pos, data); ci.cancel(); } }
    @Inject(method = "retainData", at = @At("HEAD"), cancellable = true)
    private void vre$retain(ChunkPos pos, boolean retain, CallbackInfo ci) { if (vre$lighting != null) { vre$lighting.retain(pos, retain); ci.cancel(); } }
    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    private void vre$brightness(BlockPos pos, int darkening, CallbackInfoReturnable<Integer> ci) { if (vre$lighting != null) ci.setReturnValue(vre$lighting.brightness(pos, darkening)); }
    @Inject(method = "lightOnInSection", at = @At("HEAD"), cancellable = true)
    private void vre$available(SectionPos pos, CallbackInfoReturnable<Boolean> ci) { if (vre$lighting != null) ci.setReturnValue(vre$lighting.hasSection(pos)); }
}
