package org.gtlcore.gtlcore.common.data;

import org.gtlcore.gtlcore.GTLCore;

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
            })
                    .title(REGISTRATE.addLang("itemGroup", GTLCore.id("creative_tab"), "GTL Core"))
                    .icon(GTLItems.MEGA_ULTIMATE_BATTERY::asStack)
                    .build())
            .register();

    public static void init() {}
}
