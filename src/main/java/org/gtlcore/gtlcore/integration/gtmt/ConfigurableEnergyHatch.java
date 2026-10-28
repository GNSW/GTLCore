package org.gtlcore.gtlcore.integration.gtmt;

import net.minecraft.nbt.CompoundTag;

/** Energy parameters only; applying them must also refresh the hatch's derived capacity and container. */
public interface ConfigurableEnergyHatch {

    CompoundTag gtlcore$getEnergySettings();

    void gtlcore$applyEnergySettings(CompoundTag settings);
}
