package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorMEState;
import org.gtlcore.gtlcore.common.machine.multiblock.part.NeutronSensorPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.TemperatureSensorPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.IAutoConfiguratioGravityPart;
import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.IAutoConfigurationMaintenanceHatch;
import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.IGravityPartMachine;
import org.gtlcore.gtlcore.integration.gtmt.ConfigurableEnergyHatch;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.item.component.ICustomDescriptionId;
import com.gregtechceu.gtceu.api.item.component.IInteractionItem;
import com.gregtechceu.gtceu.api.item.tool.behavior.IToolBehavior;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.SimpleTieredMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMaintenanceMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredIOPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.MaintenanceHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ParallelHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.storage.QuantumChestMachine;
import com.gregtechceu.gtceu.common.machine.storage.QuantumTankMachine;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.accessor.IManagedAccessor;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

import com.hepdd.gtmthings.api.capability.IBindable;

import java.util.Objects;

import static com.gregtechceu.gtceu.api.item.tool.ToolHelper.getBehaviorsTag;

public class ConfigurationCopyBehavior implements IToolBehavior, IInteractionItem, ICustomDescriptionId {

    public static final ConfigurationCopyBehavior INSTANCE = new ConfigurationCopyBehavior();

    /** Configuration only: never includes physical inventories, stored energy or running recipes. */
    public CompoundTag captureConfiguration(MetaMachine machine) {
        CompoundTag tag = new CompoundTag();
        if (machine instanceof SimpleTieredMachine m) getSTMCfg(tag, m);
        else if (machine instanceof QuantumChestMachine m) tag.put(QUANTUM_CHEST_CONFIGURATION, getQuantumChestCfg(m));
        else if (machine instanceof QuantumTankMachine m) tag.put(QUANTUM_TANK_CONFIGURATION, getQuantumTankCfg(m));
        else if (machine instanceof FluidHatchPartMachine m) tag.put(FLUID_OUTPUT_HATCH_CONFIGURATION, getFluidOutputHatchCfg(m));
        else if (machine instanceof NeutronSensorPartMachine m) tag.put(NEUTRON_SENSOR_CONFIGURATION, getNeutronSensorCfg(m));
        else if (machine instanceof TemperatureSensorPartMachine m) tag.put(TEMPERATURE_SENSOR_CONFIGURATION, getTemperatureSensorCfg(m));
        else if (machine instanceof ItemBusPartMachine m) getBusCfg(tag, m);
        else if (machine instanceof IMaintenanceMachine m) {
            tag.putFloat("durationMultiplier", m.getDurationMultiplier());
            if (machine instanceof IGravityPartMachine gravity) tag.putInt("currentGravity", gravity.getCurrentGravity());
        } else if (machine instanceof ParallelHatchPartMachine m) {
            tag.putInt("currentParallel", m.getCurrentParallel());
        } else if (machine instanceof TieredIOPartMachine m && m.getTraits().stream().anyMatch(IEnergyContainer.class::isInstance)) {
            tag.putBoolean("isWorkingEnabled", m.isWorkingEnabled());
            if (m instanceof ConfigurableEnergyHatch configurable) tag.put("EnergyConfiguration", configurable.gtlcore$getEnergySettings());
            if (m instanceof IBindable bindable) {
                CompoundTag binding = new CompoundTag();
                var owner = bindable.getUUID();
                if (owner != null) binding.putUUID("Owner", owner);
                tag.put("EnergyBinding", binding);
            }
        }
        if (ManipulatorMEState.supports(machine)) tag.put("MEConfiguration", ManipulatorMEState.settings(machine));
        return tag;
    }

    public void applyConfiguration(CompoundTag tag, MetaMachine machine) {
        if (machine instanceof SimpleTieredMachine m) setSTMCfg(tag, m);
        else if (machine instanceof QuantumChestMachine m) setQuantumChestCfg(tag.getCompound(QUANTUM_CHEST_CONFIGURATION), m);
        else if (machine instanceof QuantumTankMachine m) setQuantumTankCfg(tag.getCompound(QUANTUM_TANK_CONFIGURATION), m);
        else if (machine instanceof FluidHatchPartMachine m) setFluidOutputHatchCfg(tag.getCompound(FLUID_OUTPUT_HATCH_CONFIGURATION), m);
        else if (machine instanceof NeutronSensorPartMachine m) setNeutronSensorCfg(tag.getCompound(NEUTRON_SENSOR_CONFIGURATION), m);
        else if (machine instanceof TemperatureSensorPartMachine m) setTemperatureSensorCfg(tag.getCompound(TEMPERATURE_SENSOR_CONFIGURATION), m);
        else if (machine instanceof ItemBusPartMachine m) setBusCfg(tag, m);
        else if (machine instanceof IMaintenanceMachine) {
            if (tag.contains("durationMultiplier")) {
                if (machine instanceof IAutoConfigurationMaintenanceHatch m) m.setDurationMultiplier(tag.getFloat("durationMultiplier"));
                else if (machine instanceof MaintenanceHatchPartMachine m && m.isConfigurable()) {
                    // GT exposes no setter here; restore only this verified @Persisted setting.
                    CompoundTag duration = new CompoundTag();
                    duration.putFloat("durationMultiplier", tag.getFloat("durationMultiplier"));
                    IManagedAccessor.writePersistedFields(duration, m.getSyncStorage().getPersistedFields());
                }
            }
            if (machine instanceof IGravityPartMachine m && tag.contains("currentGravity")) m.setCurrentGravity(tag.getInt("currentGravity"));
        } else if (machine instanceof ParallelHatchPartMachine m && tag.contains("currentParallel")) {
            m.setCurrentParallel(tag.getInt("currentParallel"));
        } else if (machine instanceof TieredIOPartMachine m && m.getTraits().stream().anyMatch(IEnergyContainer.class::isInstance)) {
            if (m instanceof ConfigurableEnergyHatch configurable && tag.contains("EnergyConfiguration")) configurable.gtlcore$applyEnergySettings(tag.getCompound("EnergyConfiguration"));
            if (m instanceof IBindable bindable && tag.contains("EnergyBinding")) {
                CompoundTag binding = tag.getCompound("EnergyBinding");
                bindable.setUUID(binding.hasUUID("Owner") ? binding.getUUID("Owner") : null);
            }
            if (tag.contains("isWorkingEnabled")) m.setWorkingEnabled(tag.getBoolean("isWorkingEnabled"));
        }
        if (tag.contains("MEConfiguration") && ManipulatorMEState.supports(machine)) ManipulatorMEState.applySettings(machine, tag.getCompound("MEConfiguration"));
    }

    private static final String QUANTUM_CHEST_CONFIGURATION = "QuantumChestConfiguration";
    private static final String QUANTUM_TANK_CONFIGURATION = "QuantumTankConfiguration";
    private static final String FLUID_OUTPUT_HATCH_CONFIGURATION = "FluidOutputHatchConfiguration";
    private static final String NEUTRON_SENSOR_CONFIGURATION = "NeutronSensorConfiguration";
    private static final String TEMPERATURE_SENSOR_CONFIGURATION = "TemperatureSensorConfiguration";
    private static final String COPIED_CONFIGURATION_TYPE = "CopiedConfigurationType";
    private static final String COPIED_NAME_SUFFIX = "item.gtlcore.cfg_copy.copied_suffix";
    private static final int CONFIGURATION_SLOT = 0;

    private static final String[] LEGACY_CONFIGURATION_KEYS = {
            "Configuration",
            "hasAutoOutputItem",
            "hasAutoOutputFluid",
            "outputFacingItems",
            "outputFacingFluids",
            "allowInputFromOutputSideItems",
            "allowInputFromOutputSideFluids",
            "circuit",
            "isDistinct",
            "isWorkingEnabled",
            "durationMultiplier",
            "currentGravity"
    };

    @Override
    public Component getItemName(ItemStack stack) {
        CopiedConfiguration copiedConfiguration = getCopiedConfiguration(getBehaviorsTag(stack));
        if (copiedConfiguration == null) {
            return null;
        }
        return Component.translatable(stack.getItem().getDescriptionId())
                .append(Component.translatable(COPIED_NAME_SUFFIX,
                        Component.translatable(copiedConfiguration.translationKey)));
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        CompoundTag tags = getBehaviorsTag(stack);
        if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MetaMachineBlockEntity machineBlock) {
            MetaMachine metaMachine = machineBlock.getMetaMachine();
            if (metaMachine instanceof SimpleTieredMachine simpleTieredMachine) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.SIMPLE_MACHINE);
                    getSTMCfg(tags, simpleTieredMachine);
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else {
                    if (tags.getBoolean("Configuration")) {
                        setSTMCfg(tags, simpleTieredMachine);
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                    } else {
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                    }
                }
            } else if (metaMachine instanceof QuantumChestMachine quantumChestMachine) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.QUANTUM_CHEST);
                    tags.put(QUANTUM_CHEST_CONFIGURATION, getQuantumChestCfg(quantumChestMachine));
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else if (tags.get(QUANTUM_CHEST_CONFIGURATION) instanceof CompoundTag configuration) {
                    setQuantumChestCfg(configuration, quantumChestMachine);
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                } else {
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                }
            } else if (metaMachine instanceof QuantumTankMachine quantumTankMachine) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.QUANTUM_TANK);
                    tags.put(QUANTUM_TANK_CONFIGURATION, getQuantumTankCfg(quantumTankMachine));
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else if (tags.get(QUANTUM_TANK_CONFIGURATION) instanceof CompoundTag configuration) {
                    setQuantumTankCfg(configuration, quantumTankMachine);
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                } else {
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                }
            } else if (metaMachine instanceof FluidHatchPartMachine fluidHatchPartMachine && fluidHatchPartMachine.tank.handlerIO == IO.OUT) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.FLUID_OUTPUT_HATCH);
                    tags.put(FLUID_OUTPUT_HATCH_CONFIGURATION, getFluidOutputHatchCfg(fluidHatchPartMachine));
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else if (tags.get(FLUID_OUTPUT_HATCH_CONFIGURATION) instanceof CompoundTag configuration) {
                    setFluidOutputHatchCfg(configuration, fluidHatchPartMachine);
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                } else {
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                }
            } else if (metaMachine instanceof NeutronSensorPartMachine neutronSensor) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.NEUTRON_SENSOR);
                    tags.put(NEUTRON_SENSOR_CONFIGURATION, getNeutronSensorCfg(neutronSensor));
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else if (tags.get(NEUTRON_SENSOR_CONFIGURATION) instanceof CompoundTag configuration) {
                    setNeutronSensorCfg(configuration, neutronSensor);
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                } else {
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                }
            } else if (metaMachine instanceof TemperatureSensorPartMachine temperatureSensor) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.TEMPERATURE_SENSOR);
                    tags.put(TEMPERATURE_SENSOR_CONFIGURATION, getTemperatureSensorCfg(temperatureSensor));
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else if (tags.get(TEMPERATURE_SENSOR_CONFIGURATION) instanceof CompoundTag configuration) {
                    setTemperatureSensorCfg(configuration, temperatureSensor);
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                } else {
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                }
            } else if (metaMachine instanceof ItemBusPartMachine itemBusPartMachine) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.ITEM_BUS);
                    getBusCfg(tags, itemBusPartMachine);
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else {
                    if (tags.getBoolean("Configuration")) {
                        setBusCfg(tags, itemBusPartMachine);
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                    } else {
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                    }
                }
            } else if (metaMachine instanceof IAutoConfiguratioGravityPart acgp) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.GRAVITY_CONFIGURATION_HATCH);
                    tags.putBoolean("Configuration", true);
                    tags.putFloat("durationMultiplier", acgp.getDurationMultiplier());
                    tags.putInt("currentGravity", acgp.getCurrentGravity());
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else {
                    if (tags.getBoolean("Configuration")) {
                        acgp.setDurationMultiplier(tags.getFloat("durationMultiplier"));
                        acgp.setCurrentGravity(tags.getInt("currentGravity"));
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                    } else {
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                    }
                }
            } else if (metaMachine instanceof IAutoConfigurationMaintenanceHatch acmh) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.CONFIGURATION_MAINTENANCE_HATCH);
                    tags.putBoolean("Configuration", true);
                    tags.putFloat("durationMultiplier", acmh.getDurationMultiplier());
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else {
                    if (tags.getBoolean("Configuration")) {
                        acmh.setDurationMultiplier(tags.getFloat("durationMultiplier"));
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                    } else {
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                    }
                }
            } else if (metaMachine instanceof IGravityPartMachine gpm) {
                if (Objects.requireNonNull(context.getPlayer()).isShiftKeyDown()) {
                    prepareCopy(tags, CopiedConfiguration.GRAVITY_HATCH);
                    tags.putBoolean("Configuration", true);
                    tags.putInt("currentGravity", gpm.getCurrentGravity());
                    context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_copied"), true);
                } else {
                    if (tags.getBoolean("Configuration")) {
                        gpm.setCurrentGravity(tags.getInt("currentGravity"));
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_pasted"), true);
                    } else {
                        context.getPlayer().displayClientMessage(Component.translatable("message.gtlcore.machine_data_not_found"), true);
                    }
                }
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private void prepareCopy(CompoundTag tags, CopiedConfiguration copiedConfiguration) {
        tags.remove(QUANTUM_CHEST_CONFIGURATION);
        tags.remove(QUANTUM_TANK_CONFIGURATION);
        tags.remove(FLUID_OUTPUT_HATCH_CONFIGURATION);
        tags.remove(NEUTRON_SENSOR_CONFIGURATION);
        tags.remove(TEMPERATURE_SENSOR_CONFIGURATION);
        for (String key : LEGACY_CONFIGURATION_KEYS) {
            tags.remove(key);
        }
        tags.putString(COPIED_CONFIGURATION_TYPE, copiedConfiguration.id);
    }

    private CopiedConfiguration getCopiedConfiguration(CompoundTag tags) {
        CopiedConfiguration copiedConfiguration = CopiedConfiguration.fromId(tags.getString(COPIED_CONFIGURATION_TYPE));
        if (copiedConfiguration != null) {
            return copiedConfiguration;
        }
        if (tags.contains(QUANTUM_CHEST_CONFIGURATION)) {
            return CopiedConfiguration.QUANTUM_CHEST;
        }
        if (tags.contains(QUANTUM_TANK_CONFIGURATION)) {
            return CopiedConfiguration.QUANTUM_TANK;
        }
        if (tags.contains(FLUID_OUTPUT_HATCH_CONFIGURATION)) {
            return CopiedConfiguration.FLUID_OUTPUT_HATCH;
        }
        if (tags.contains(NEUTRON_SENSOR_CONFIGURATION)) {
            return CopiedConfiguration.NEUTRON_SENSOR;
        }
        if (tags.contains(TEMPERATURE_SENSOR_CONFIGURATION)) {
            return CopiedConfiguration.TEMPERATURE_SENSOR;
        }
        if (!tags.getBoolean("Configuration")) {
            return null;
        }
        if (tags.contains("durationMultiplier") && tags.contains("currentGravity")) {
            return CopiedConfiguration.GRAVITY_CONFIGURATION_HATCH;
        }
        if (tags.contains("durationMultiplier")) {
            return CopiedConfiguration.CONFIGURATION_MAINTENANCE_HATCH;
        }
        if (tags.contains("currentGravity")) {
            return CopiedConfiguration.GRAVITY_HATCH;
        }
        if (tags.contains("isDistinct")) {
            return CopiedConfiguration.ITEM_BUS;
        }
        return tags.contains("hasAutoOutputItem") ? CopiedConfiguration.SIMPLE_MACHINE : null;
    }

    private CompoundTag getQuantumChestCfg(QuantumChestMachine metaMachine) {
        CompoundTag configuration = new CompoundTag();
        configuration.putBoolean("autoOutputItems", metaMachine.isAutoOutputItems());
        configuration.putString("outputFacingItems", metaMachine.getOutputFacingItems().getName());
        configuration.putBoolean("allowInputFromOutputSideItems", metaMachine.isAllowInputFromOutputSideItems());
        configuration.putBoolean("isVoiding", metaMachine.isVoiding());
        configuration.putBoolean("isLocked", metaMachine.isLocked());
        if (metaMachine.isLocked()) {
            ItemStack lockedItem = metaMachine.getLockedItem().getStackInSlot(CONFIGURATION_SLOT).copy();
            lockedItem.setCount(1);
            configuration.put("lockedItem", lockedItem.save(new CompoundTag()));
        }
        return configuration;
    }

    private void setQuantumChestCfg(CompoundTag configuration, QuantumChestMachine metaMachine) {
        metaMachine.setAutoOutputItems(configuration.getBoolean("autoOutputItems"));
        metaMachine.setOutputFacingItems(Direction.byName(configuration.getString("outputFacingItems")));
        metaMachine.setAllowInputFromOutputSideItems(configuration.getBoolean("allowInputFromOutputSideItems"));
        metaMachine.setVoiding(configuration.getBoolean("isVoiding"));
        ItemStack lockedItem = configuration.getBoolean("isLocked") ?
                ItemStack.of(configuration.getCompound("lockedItem")) : ItemStack.EMPTY;
        metaMachine.getLockedItem().setStackInSlot(CONFIGURATION_SLOT, lockedItem);
    }

    private CompoundTag getQuantumTankCfg(QuantumTankMachine metaMachine) {
        CompoundTag configuration = new CompoundTag();
        configuration.putBoolean("autoOutputFluids", metaMachine.isAutoOutputFluids());
        configuration.putString("outputFacingFluids", metaMachine.getOutputFacingFluids().getName());
        configuration.putBoolean("allowInputFromOutputSideFluids", metaMachine.isAllowInputFromOutputSideFluids());
        configuration.putBoolean("isVoiding", metaMachine.isVoiding());
        configuration.putBoolean("isLocked", metaMachine.isLocked());
        if (metaMachine.isLocked()) {
            configuration.put("lockedFluid", saveFluid(metaMachine.getCache().getLockedFluid().getFluid()));
        }
        return configuration;
    }

    private void setQuantumTankCfg(CompoundTag configuration, QuantumTankMachine metaMachine) {
        metaMachine.setAutoOutputFluids(configuration.getBoolean("autoOutputFluids"));
        metaMachine.setOutputFacingFluids(Direction.byName(configuration.getString("outputFacingFluids")));
        metaMachine.setAllowInputFromOutputSideFluids(configuration.getBoolean("allowInputFromOutputSideFluids"));
        metaMachine.setVoiding(configuration.getBoolean("isVoiding"));
        metaMachine.getCache().setLocked(false);
        if (configuration.getBoolean("isLocked")) {
            metaMachine.getCache().setLocked(true, FluidStack.loadFromTag(configuration.getCompound("lockedFluid")));
        }
    }

    private CompoundTag getFluidOutputHatchCfg(FluidHatchPartMachine metaMachine) {
        CompoundTag configuration = new CompoundTag();
        configuration.putBoolean("isWorkingEnabled", metaMachine.isWorkingEnabled());
        configuration.putInt("circuit", getCircuitConfiguration(metaMachine.getCircuitInventory()));
        configuration.putBoolean("isLocked", metaMachine.tank.isLocked());
        if (metaMachine.tank.isLocked()) {
            configuration.put("lockedFluid", saveFluid(metaMachine.tank.getLockedFluid().getFluid()));
        }
        return configuration;
    }

    private void setFluidOutputHatchCfg(CompoundTag configuration, FluidHatchPartMachine metaMachine) {
        metaMachine.setWorkingEnabled(configuration.getBoolean("isWorkingEnabled"));
        setCircuitConfiguration(metaMachine.getCircuitInventory(), configuration.getInt("circuit"));
        metaMachine.tank.setLocked(false);
        if (configuration.getBoolean("isLocked")) {
            metaMachine.tank.setLocked(true, FluidStack.loadFromTag(configuration.getCompound("lockedFluid")));
        }
    }

    private CompoundTag getNeutronSensorCfg(NeutronSensorPartMachine machine) {
        CompoundTag configuration = new CompoundTag();
        configuration.putInt("min", machine.getMin());
        configuration.putInt("max", machine.getMax());
        configuration.putBoolean("inverted", machine.isInverted());
        return configuration;
    }

    private void setNeutronSensorCfg(CompoundTag configuration, NeutronSensorPartMachine machine) {
        int min = Mth.clamp(configuration.getInt("min"), 0, 1_200_000);
        int max = Mth.clamp(configuration.getInt("max"), 0, 1_200_000);
        if (min > max) min = max;
        machine.setMin(min);
        machine.setMax(max);
        machine.setInverted(configuration.getBoolean("inverted"));
    }

    private CompoundTag getTemperatureSensorCfg(TemperatureSensorPartMachine machine) {
        CompoundTag configuration = new CompoundTag();
        configuration.putInt("max", machine.getMax());
        return configuration;
    }

    private void setTemperatureSensorCfg(CompoundTag configuration, TemperatureSensorPartMachine machine) {
        machine.setMax(Mth.clamp(configuration.getInt("max"), 1, 16000));
    }

    private CompoundTag saveFluid(FluidStack fluid) {
        FluidStack copy = fluid.copy();
        copy.setAmount(1);
        return copy.saveToTag(new CompoundTag());
    }

    private int getCircuitConfiguration(NotifiableItemStackHandler circuitInventory) {
        return circuitInventory.handlerIO.support(IO.IN) ?
                IntCircuitBehaviour.getCircuitConfiguration(circuitInventory.getStackInSlot(CONFIGURATION_SLOT)) : 0;
    }

    private void setCircuitConfiguration(NotifiableItemStackHandler circuitInventory, int configuration) {
        if (circuitInventory.handlerIO.support(IO.IN)) {
            circuitInventory.setStackInSlot(CONFIGURATION_SLOT,
                    configuration > 0 ? IntCircuitBehaviour.stack(configuration) : ItemStack.EMPTY);
        }
    }

    private void getBusCfg(CompoundTag tags, ItemBusPartMachine metaMachine) {
        tags.putBoolean("Configuration", true);
        tags.putBoolean("isDistinct", metaMachine.isDistinct());
        tags.putBoolean("isWorkingEnabled", metaMachine.isWorkingEnabled());
        NotifiableItemStackHandler circuitInventory = metaMachine.getCircuitInventory();
        if (circuitInventory.handlerIO.support(IO.IN)) {
            int c = IntCircuitBehaviour.getCircuitConfiguration(circuitInventory.getStackInSlot(0));
            if (c > 0) {
                tags.putInt("circuit", c);
            }
        }
    }

    private void setBusCfg(CompoundTag tags, ItemBusPartMachine metaMachine) {
        metaMachine.setDistinct(tags.getBoolean("isDistinct"));
        metaMachine.setWorkingEnabled(tags.getBoolean("isWorkingEnabled"));
        int c = tags.getInt("circuit");
        NotifiableItemStackHandler circuitInventory = metaMachine.getCircuitInventory();
        if (c > 0 && circuitInventory.handlerIO.support(IO.IN)) {
            circuitInventory.setStackInSlot(0, IntCircuitBehaviour.stack(c));
        }
    }

    private void getSTMCfg(CompoundTag tags, SimpleTieredMachine metaMachine) {
        tags.putBoolean("Configuration", true);
        boolean hasAutoOutputItem = metaMachine.hasAutoOutputItem();
        boolean hasAutoOutputFluid = metaMachine.hasAutoOutputFluid();
        tags.putBoolean("hasAutoOutputItem", hasAutoOutputItem);
        tags.putBoolean("hasAutoOutputFluid", hasAutoOutputFluid);
        if (hasAutoOutputItem) {
            tags.putString("outputFacingItems", metaMachine.getOutputFacingItems().toString());
        }
        if (hasAutoOutputFluid) {
            tags.putString("outputFacingFluids", metaMachine.getOutputFacingFluids().toString());
        }
        tags.putBoolean("allowInputFromOutputSideItems", metaMachine.isAllowInputFromOutputSideItems());
        tags.putBoolean("allowInputFromOutputSideFluids", metaMachine.isAllowInputFromOutputSideFluids());
        NotifiableItemStackHandler circuitInventory = metaMachine.getCircuitInventory();
        if (circuitInventory.handlerIO.support(IO.IN)) {
            int c = IntCircuitBehaviour.getCircuitConfiguration(circuitInventory.getStackInSlot(0));
            if (c > 0) {
                tags.putInt("circuit", c);
            }
        }
    }

    private void setSTMCfg(CompoundTag tags, SimpleTieredMachine metaMachine) {
        boolean hasAutoOutputItem = tags.getBoolean("hasAutoOutputItem");
        boolean hasAutoOutputFluid = tags.getBoolean("hasAutoOutputFluid");
        metaMachine.setAutoOutputItems(hasAutoOutputItem);
        metaMachine.setAutoOutputFluids(hasAutoOutputFluid);
        if (hasAutoOutputItem) {
            metaMachine.setOutputFacingItems(Direction.byName(tags.getString("outputFacingItems")));
        }
        if (hasAutoOutputFluid) {
            metaMachine.setOutputFacingFluids(Direction.byName(tags.getString("outputFacingFluids")));
        }
        metaMachine.setAllowInputFromOutputSideItems(tags.getBoolean("allowInputFromOutputSideItems"));
        metaMachine.setAllowInputFromOutputSideFluids(tags.getBoolean("allowInputFromOutputSideFluids"));
        int c = tags.getInt("circuit");
        NotifiableItemStackHandler circuitInventory = metaMachine.getCircuitInventory();
        if (c > 0 && circuitInventory.handlerIO.support(IO.IN)) {
            circuitInventory.setStackInSlot(0, IntCircuitBehaviour.stack(c));
        }
    }

    private enum CopiedConfiguration {

        SIMPLE_MACHINE("simple_machine", "item.gtlcore.cfg_copy.type.simple_machine"),
        ITEM_BUS("item_bus", "item.gtlcore.cfg_copy.type.item_bus"),
        QUANTUM_CHEST("quantum_chest", "item.gtlcore.cfg_copy.type.quantum_chest"),
        QUANTUM_TANK("quantum_tank", "item.gtlcore.cfg_copy.type.quantum_tank"),
        FLUID_OUTPUT_HATCH("fluid_output_hatch", "item.gtlcore.cfg_copy.type.fluid_output_hatch"),
        NEUTRON_SENSOR("neutron_sensor", "item.gtlcore.cfg_copy.type.neutron_sensor"),
        TEMPERATURE_SENSOR("temperature_sensor", "item.gtlcore.cfg_copy.type.temperature_sensor"),
        GRAVITY_CONFIGURATION_HATCH("gravity_configuration_hatch", "item.gtlcore.cfg_copy.type.gravity_configuration_hatch"),
        CONFIGURATION_MAINTENANCE_HATCH("configuration_maintenance_hatch", "item.gtlcore.cfg_copy.type.configuration_maintenance_hatch"),
        GRAVITY_HATCH("gravity_hatch", "item.gtlcore.cfg_copy.type.gravity_hatch");

        private final String id;
        private final String translationKey;

        CopiedConfiguration(String id, String translationKey) {
            this.id = id;
            this.translationKey = translationKey;
        }

        private static CopiedConfiguration fromId(String id) {
            for (CopiedConfiguration copiedConfiguration : values()) {
                if (copiedConfiguration.id.equals(id)) {
                    return copiedConfiguration;
                }
            }
            return null;
        }
    }
}
