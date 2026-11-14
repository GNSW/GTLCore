package org.gtlcore.gtlcore.client.renderer.item;

import org.gtlcore.gtlcore.common.structure.material.StructureMaterialTier;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialType;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterials;

import net.minecraftforge.client.event.RegisterColorHandlersEvent;

/** 结构材料组件的第二层按类型着色（与原模组一致的 tint 行为）。 */
public final class StructureMaterialColors {

    private StructureMaterialColors() {}

    public static void register(RegisterColorHandlersEvent.Item event) {
        for (StructureMaterialType type : StructureMaterialType.values()) {
            int tint = type.getColor() | 0xFF000000;
            for (StructureMaterialTier tier : StructureMaterialTier.values()) {
                event.register((stack, layer) -> layer == 1 ? tint : 0xFFFFFFFF,
                        StructureMaterials.item(type, tier));
            }
        }
    }
}
