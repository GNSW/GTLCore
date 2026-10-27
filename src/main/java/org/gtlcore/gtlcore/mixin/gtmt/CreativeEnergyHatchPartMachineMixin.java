package org.gtlcore.gtlcore.mixin.gtmt;

import org.gtlcore.gtlcore.integration.gtmt.ConfigurableEnergyHatch;
import org.gtlcore.gtlcore.integration.gtmt.InfinityEnergyContainer;
import org.gtlcore.gtlcore.integration.gtmt.NewGTValues;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredIOPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.utils.GTUtil;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import com.hepdd.gtmthings.common.block.machine.multiblock.part.CreativeEnergyHatchPartMachine;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.*;

import java.util.Arrays;

@Mixin(CreativeEnergyHatchPartMachine.class)
public abstract class CreativeEnergyHatchPartMachineMixin extends TieredIOPartMachine implements ConfigurableEnergyHatch {

    @Shadow(remap = false)
    @Final
    public NotifiableEnergyContainer energyContainer;

    @Shadow(remap = false)
    private long voltage;

    @Persisted
    @Shadow(remap = false)
    private Long maxEnergy;

    @Shadow(remap = false)
    private int setTier;

    @Shadow(remap = false)
    private int amps;

    public CreativeEnergyHatchPartMachineMixin(IMachineBlockEntity holder, int tier, IO io) {
        super(holder, tier, io);
    }

    /**
     * @author liansishen
     * @reason Lastest GTMT
     */
    @Overwrite(remap = false)
    protected NotifiableEnergyContainer createEnergyContainer() {
        this.setTier = 30;
        this.voltage = GTValues.VEX[setTier];
        this.maxEnergy = NumberUtils.saturatedMultiply(this.voltage, this.amps);
        return new InfinityEnergyContainer(this, this.maxEnergy, this.voltage, this.amps, 0L, 0L);
    }

    /**
     * @author liansishen
     * @reason Lastest GTMT
     */
    @Overwrite(remap = false)
    public ModularUI createUI(Player entityPlayer) {
        return new ModularUI(176, 136, this, entityPlayer)
                .background(GuiTextures.BACKGROUND)
                .widget(new LabelWidget(7, 32, "gtceu.creative.energy.voltage"))
                .widget(new TextFieldWidget(9, 47, 152, 16, () -> String.valueOf(voltage),
                        value -> {
                            gTLCore$setVoltage(Long.parseLong(value));
                            setTier = GTUtil.getTierByVoltage(this.voltage);
                        }).setNumbersOnly(8L, Long.MAX_VALUE))
                .widget(new LabelWidget(7, 74, "gtceu.creative.energy.amperage"))
                .widget(new ButtonWidget(7, 87, 20, 20,
                        new GuiTextureGroup(ResourceBorderTexture.BUTTON_COMMON, new TextTexture("-")),
                        cd -> gTLCore$setAmps(--amps == -1 ? 0 : amps)))
                .widget(new TextFieldWidget(31, 89, 114, 16, () -> String.valueOf(amps),
                        value -> gTLCore$setAmps(Integer.parseInt(value))).setNumbersOnly(1, 67108864))
                .widget(new ButtonWidget(149, 87, 20, 20,
                        new GuiTextureGroup(ResourceBorderTexture.BUTTON_COMMON, new TextTexture("+")),
                        cd -> {
                            if (amps < Integer.MAX_VALUE) {
                                gTLCore$setAmps(++amps);
                            }
                        }))

                .widget(new SelectorWidget(7, 7, 50, 20, Arrays.stream(NewGTValues.VNF).toList(), -1)
                        .setOnChanged(tier -> {
                            setTier = ArrayUtils.indexOf(NewGTValues.VNF, tier);
                            gTLCore$setVoltage(GTValues.VEX[setTier]);
                        })
                        .setSupplier(() -> NewGTValues.VNF[setTier])
                        .setButtonBackground(ResourceBorderTexture.BUTTON_COMMON)
                        .setBackground(ColorPattern.BLACK.rectTexture())
                        .setValue(NewGTValues.VNF[setTier]));
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        gTLCore$updateEnergyContainer();
    }

    @Override
    @Unique
    public CompoundTag gtlcore$getEnergySettings() {
        CompoundTag settings = new CompoundTag();
        settings.putLong("voltage", voltage);
        settings.putInt("amps", amps);
        settings.putInt("setTier", setTier);
        return settings;
    }

    @Override
    @Unique
    public void gtlcore$applyEnergySettings(CompoundTag settings) {
        voltage = settings.getLong("voltage");
        amps = settings.getInt("amps");
        setTier = settings.getInt("setTier");
        maxEnergy = NumberUtils.saturatedMultiply(voltage, amps);
        gTLCore$updateMachine();
    }

    @Unique
    private void gTLCore$setVoltage(long voltage) {
        this.voltage = voltage;
        this.maxEnergy = NumberUtils.saturatedMultiply(this.voltage, this.amps);
        gTLCore$updateMachine();
    }

    @Unique
    private void gTLCore$setAmps(int amps) {
        this.amps = amps;
        this.maxEnergy = NumberUtils.saturatedMultiply(this.voltage, this.amps);
        gTLCore$updateMachine();
    }

    @Unique
    private void gTLCore$updateEnergyContainer() {
        this.energyContainer.resetBasicInfo(this.maxEnergy, this.voltage, this.amps, 0, 0);
        this.energyContainer.setEnergyStored(this.maxEnergy);
    }

    @Unique
    private void gTLCore$updateMachine() {
        gTLCore$updateEnergyContainer();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                for (var c : getControllers()) {
                    if (c.isFormed()) {
                        c.getPatternLock().lock();
                        try {
                            c.onStructureInvalid();
                            var mwsd = MultiblockWorldSavedData.getOrCreate(serverLevel);
                            mwsd.removeMapping(c.getMultiblockState());
                            mwsd.addAsyncLogic(c);
                        } finally {
                            c.getPatternLock().unlock();
                        }
                    }
                }
            });
        }
    }

    /**
     * @author Dragons
     * @reason Dont use
     */
    @Overwrite(remap = false)
    protected void addEnergy() {}

    /**
     * @author Dragons
     * @reason Dont use
     */
    @Overwrite(remap = false)
    protected void InfinityEnergySubscription() {}

    @Override
    public void onLoad() {
        super.onLoad();
    }

    @Override
    public void onUnload() {
        super.onUnload();
    }
}
