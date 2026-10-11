package org.gtlcore.gtlcore.mixin.travelanchors;

import org.gtlcore.gtlcore.config.ConfigHolder;

import de.castcrafter.travelanchors.EventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(EventListener.class)
public abstract class EventListenerMixin {

    @ModifyConstant(method = "onRightClick", remap = false, constant = @Constant(intValue = 30))
    private int modifyConsume1(int constant) {
        return ConfigHolder.INSTANCE.gameplay.travelStaffCD;
    }
}
