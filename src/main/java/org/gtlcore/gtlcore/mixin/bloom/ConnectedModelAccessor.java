package org.gtlcore.gtlcore.mixin.bloom;

import com.lowdragmc.lowdraglib.client.model.custommodel.CustomBakedModel;

import net.minecraft.client.resources.model.BakedModel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Inspect the delegate without replacing LDLib's position-dependent connected quads. */
@Mixin(CustomBakedModel.class)
public interface ConnectedModelAccessor {

    @Accessor(value = "parent", remap = false)
    BakedModel gtlcore$bloomParent();
}
