package org.gtlcore.gtlcore.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine;

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

/**
 * @author EasterFG on 2024/12/18
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class TemperatureSensorPartMachine extends TieredPartMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            TemperatureSensorPartMachine.class, TieredPartMachine.MANAGED_FIELD_HOLDER);

    @Getter
    @Setter
    @Persisted
    @DescSynced
    protected int max;

    private static int MAX_TEMP = 16000;

    @Getter
    @Persisted
    protected int redStoneSignalOutput = 0;

    public TemperatureSensorPartMachine(IMachineBlockEntity holder) {
        super(holder, GTValues.UIV);
    }

    @Override
    public Widget createUIWidget() {
        var group = new WidgetGroup(Position.ORIGIN, new Size(176, 112));

        group.addWidget(new TextBoxWidget(8, 35, 65,
                List.of(LocalizationUtils.format("gui.gtlcore.temperature_sensor.max_temperature"))));

        group.addWidget(new TextFieldWidget(80, 35, 85, 18, () -> toText(max),
                stringValue -> setMax(Mth.clamp(fromText(stringValue), 1, MAX_TEMP))).setNumbersOnly(1, MAX_TEMP));

        return group;
    }

    @Override
    public int getOutputSignal(@Nullable Direction side) {
        if (side == getFrontFacing().getOpposite()) {
            return redStoneSignalOutput;
        }
        return 0;
    }

    public void update(int heat) {
        int value = heat > max ? 15 : 0;
        if (redStoneSignalOutput != value) {
            setRedStoneSignalOutput(value);
        }
    }

    private void setRedStoneSignalOutput(int redStoneSignalOutput) {
        this.redStoneSignalOutput = redStoneSignalOutput;
        updateSignal();
    }

    // @Override
    // public boolean canConnectRedstone(Direction side) {
    // return side == getFrontFacing().getOpposite();
    // }

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
