package org.gtlcore.gtlcore.mixin.ae2.storage;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {

    @Accessor("container1")
    Container gtlcore$container1();

    @Accessor("container2")
    Container gtlcore$container2();
}
