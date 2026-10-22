package org.gtlcore.gtlcore.client.renderer.item;

import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.item.WearablePlushieItem;

import net.minecraftforge.client.event.RegisterColorHandlersEvent;

public final class PlushieColors {

    private PlushieColors() {}

    public static void register(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? ((WearablePlushieItem) stack.getItem()).getColor(stack) : -1,
                GTLItems.FISHBIG_TM.get(), GTLItems.PIG_PLUSHIE.get());
    }
}
