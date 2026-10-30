package org.gtlcore.gtlcore.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.widget.ToggleButtonWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine;
import com.gregtechceu.gtceu.data.lang.LangHandler;
import com.gregtechceu.gtceu.utils.RedstoneUtil;

import com.lowdragmc.lowdraglib.gui.widget.TextBoxWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class NeutronSensorPartMachine extends TieredPartMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            NeutronSensorPartMachine.class, TieredPartMachine.MANAGED_FIELD_HOLDER);

    @Getter
    @Setter
    @Persisted
    @DescSynced
    private int min, max;

    @Persisted
    @Setter
    @Getter
    private boolean isInverted;

    @Getter
    @Persisted
    protected int redstoneSignalOutput = 0;

    public NeutronSensorPartMachine(IMachineBlockEntity holder) {
        super(holder, GTValues.IV);
    }

    @Override
    public Widget createUIWidget() {
        var group = new WidgetGroup(Position.ORIGIN, new Size(176, 112));

        group.addWidget(new TextBoxWidget(8, 35, 65,
                List.of(LocalizationUtils.format("gui.gtlcore.neutron_sensor.min_energy", "MeV"))));

        group.addWidget(new TextBoxWidget(8, 80, 65,
                List.of(LocalizationUtils.format("gui.gtlcore.neutron_sensor.max_energy", "MeV"))));

        group.addWidget(new TextFieldWidget(80, 35, 85, 18, () -> toText(min),
                stringValue -> setMin(Mth.clamp(fromText(stringValue), 0, max))).setNumbersOnly(0, max));

        group.addWidget(new TextFieldWidget(80, 80, 85, 18, () -> toText(max),
                stringValue -> setMax(Mth.clamp(fromText(stringValue), min, 1200000))).setNumbersOnly(min, 1200000));

        group.addWidget(new ToggleButtonWidget(8, 8, 20, 20,
                GuiTextures.INVERT_REDSTONE_BUTTON, this::isInverted, this::setInverted) {

            @Override
            public void updateScreen() {
                super.updateScreen();
                setHoverTooltips(List.copyOf(LangHandler.getMultiLang(
                        "gui.gtceu.neutron_sensor.invert." + (isPressed ? "enabled" : "disabled"))));
            }
        });
        return group;
    }

    public void update(int energy) {
        int output = RedstoneUtil.computeRedstoneBetweenValues(energy, max * 1000000, min * 1000000, isInverted());
        if (redstoneSignalOutput != output) {
            setRedstoneSignalOutput(output);
        }
    }

    private void setRedstoneSignalOutput(int redstoneSignalOutput) {
        this.redstoneSignalOutput = redstoneSignalOutput;
        updateSignal();
    }

    @Override
    public int getOutputSignal(@Nullable Direction side) {
        if (side == getFrontFacing().getOpposite()) {
            return redstoneSignalOutput;
        }
        return 0;
    }

    @Override
    public boolean canConnectRedstone(Direction side) {
        return false;
    }

    private String toText(int num) {
        return String.valueOf(num);
    }

    private int fromText(String num) {
        return Integer.parseInt(num);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }
}
