package org.gtlcore.gtlcore.client.renderer.item;

import org.gtlcore.gtlcore.common.block.PlushieBlockEntity;
import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.data.GTLPlushies;
import org.gtlcore.gtlcore.common.item.WearablePlushieItem;

import net.minecraftforge.client.event.RegisterColorHandlersEvent;

public final class PlushieColors {

    private PlushieColors() {}

    public static void register(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? ((WearablePlushieItem) stack.getItem()).getColor(stack) : -1,
                GTLItems.FISHBIG_TM.get(), GTLItems.PIG_PLUSHIE.get());
    }

    public static void registerBlocks(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> {
            if (tint == 0 && level != null && pos != null &&
                    level.getBlockEntity(pos) instanceof PlushieBlockEntity plushie) {
                return plushie.getColor();
            }
            return -1;
        }, GTLPlushies.FISHBIG_TM.get(), GTLPlushies.PIG_PLUSHIE.get());
    }
}
