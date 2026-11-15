package org.gtlcore.gtlcore.common.data;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialTier;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialType;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterials;

import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs;

import net.minecraft.world.item.CreativeModeTab;

import com.tterrag.registrate.util.entry.RegistryEntry;

import static org.gtlcore.gtlcore.api.registries.GTLRegistration.REGISTRATE;

public class GTLCreativeModeTabs {

    public static RegistryEntry<CreativeModeTab> GTL_CORE = REGISTRATE.defaultCreativeTab(GTLCore.MOD_ID,
            builder -> builder.displayItems((parameters, output) -> {
                new GTCreativeModeTabs.RegistrateDisplayItemsGenerator(GTLCore.MOD_ID, REGISTRATE).accept(parameters, output);
                // Their blocks use DeferredRegister, so the Registrate block-item generator skips them.
                output.accept(GTLItems.FISHBIG_TM.get());
                output.accept(GTLItems.PIG_PLUSHIE.get());
                output.accept(GTLItems.SLIM_PIG_PLUSHIE.get());
                // 结构放置器与结构材料组件（DeferredRegister）
                output.accept(GTLStructureContent.STRUCTURE_PLACER_ITEM.get());
                output.accept(GTLStructureContent.COORDINATE_POSITIONING_CARD.get());
                for (StructureMaterialType type : StructureMaterialType.values()) {
                    for (StructureMaterialTier tier : StructureMaterialTier.values()) {
                        output.accept(StructureMaterials.item(type, tier));
                    }
                }
            })
                    .title(REGISTRATE.addLang("itemGroup", GTLCore.id("creative_tab"), "GTL Core"))
                    .icon(GTLItems.MEGA_ULTIMATE_BATTERY::asStack)
                    .build())
            .register();

    public static void init() {}
}
