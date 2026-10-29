/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import com.mojang.serialization.*;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.voxelregion.forge.UniformSectionEncoding;

@Mixin(ChunkSerializer.class)
abstract class SectionEncodingMixin {
    @SuppressWarnings("rawtypes")
    @Redirect(method = "write", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult vre$encodeUniform(Codec codec, DynamicOps ops, Object value) {
        return UniformSectionEncoding.encode(codec, ops, value);
    }
}
