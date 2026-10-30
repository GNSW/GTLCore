package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/** Shared templates cache block-filtered lists even when their placements use separate regions. */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructurePaletteMixin {

    @WrapMethod(method = "blocks(Lnet/minecraft/world/level/block/Block;)Ljava/util/List;")
    private List<StructureTemplate.StructureBlockInfo> gtlcore$publishFilteredBlocks(
                                                                                     Block block,
                                                                                     Operation<List<StructureTemplate.StructureBlockInfo>> original) {
        // Preserve the original list and filtering order; only cache publication is shared.
        synchronized (this) {
            return original.call(block);
        }
    }
}
