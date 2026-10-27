package org.gtlcore.gtlcore.common.item.manipulator;

import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftParallelCore;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftPatternContainer;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftSpeedCore;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEIOPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.common.machine.multiblock.part.MEDualHatchStockPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MECraftingCPUInterfacePartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEExtendedOutputPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEMolecularAssemblerIOPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.mixin.gtm.ae.machine.MEInputBusPartMachineAccessor;
import org.gtlcore.gtlcore.mixin.gtm.ae.machine.MEInputHatchPartMachineAccessor;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList;
import com.gregtechceu.gtceu.integration.ae2.utils.KeyStorage;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.syncdata.accessor.IManagedAccessor;
import com.lowdragmc.lowdraglib.utils.TagUtils;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.hepdd.gtmthings.common.block.machine.multiblock.part.appeng.MEOutputPartMachine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Portable ME settings and paid stores, without node identities or simulated network stock. */
public final class ManipulatorMEState {

    private ManipulatorMEState() {}

    public static boolean supports(MetaMachine machine) {
        return machine instanceof IGridConnectedMachine || machine instanceof IMECraftPatternContainer ||
                machine instanceof IMECraftParallelCore || machine instanceof IMECraftSpeedCore;
    }

    public static CompoundTag settings(MetaMachine machine) {
        CompoundTag result = new CompoundTag();
        CompoundTag fields = new CompoundTag();
        CompoundTag lists = new CompoundTag();
        for (var ref : machine.getSyncStorage().getPersistedFields()) {
            Object value = ref.readRaw();
            // Pattern-container shouldOpen is controller lifecycle state. Base-machine fields are restored by the
            // snapshot.
            if (!(machine instanceof IMECraftPatternContainer) && ref.getKey().getRawField().getDeclaringClass() != MetaMachine.class &&
                    (value instanceof Number || value instanceof Boolean || value instanceof String || value instanceof Enum<?>)) {
                Tag serialized = ref.getKey().readPersistedField(ref);
                if (serialized != null) TagUtils.setTagExtended(fields, ref.getPersistedKey(), serialized);
            } else if (value instanceof IConfigurableSlotList list) {
                ListTag slots = new ListTag();
                for (int i = 0; i < list.getConfigurableSlots(); i++) {
                    GenericStack config = list.getConfigurableSlot(i).getConfig();
                    slots.add(config == null ? new CompoundTag() : GenericStack.writeTag(config));
                }
                TagUtils.setTagExtended(lists, ref.getPersistedKey(), slots);
            }
        }
        result.put("Fields", fields);
        result.put("Slots", lists);
        if (machine instanceof MEInputBusPartMachineAccessor input) result.put("Device", input.gtlcore$writeConfigToTag());
        else if (machine instanceof MEInputHatchPartMachineAccessor input) result.put("Device", input.gtlcore$writeConfigToTag());
        else if (machine instanceof MEDualHatchStockPartMachine input) result.put("Device", input.writeConfigToTag());
        else if (machine instanceof MEExtendedOutputPartMachineBase output) result.put("Device", output.writeConfigToTag());
        if (machine instanceof MECraftingCPUInterfacePartMachine cpu) result.putLong("Parallelism", cpu.getParallelism());
        if (machine instanceof MEPatternBufferPartMachineBase buffer) result.putInt("SharedCircuit", IntCircuitBehaviour.getCircuitConfiguration(buffer.getSharedCircuitInventory().getStackInSlot(0)));
        result.putBoolean("AllFacing", machine.getHolder().self().getPersistentData().getBoolean("isAllFacing"));
        return result;
    }

    public static void applySettings(MetaMachine machine, CompoundTag settings) {
        IManagedAccessor.writePersistedFields(settings.getCompound("Fields"), machine.getSyncStorage().getPersistedFields());
        if (settings.contains("Device")) {
            CompoundTag device = settings.getCompound("Device");
            if (machine instanceof MEInputBusPartMachineAccessor input) input.gtlcore$readConfigFromTag(device);
            else if (machine instanceof MEInputHatchPartMachineAccessor input) input.gtlcore$readConfigFromTag(device);
            else if (machine instanceof MEDualHatchStockPartMachine input) input.readConfigFromTag(device);
            else if (machine instanceof MEExtendedOutputPartMachineBase output) output.readConfigFromTag(device);
        }
        for (var ref : machine.getSyncStorage().getPersistedFields()) {
            if (!(ref.readRaw() instanceof IConfigurableSlotList list)) continue;
            if (!(TagUtils.getTagExtended(settings.getCompound("Slots"), ref.getPersistedKey()) instanceof ListTag slots)) continue;
            for (int i = 0; i < Math.min(slots.size(), list.getConfigurableSlots()); i++) {
                IConfigurableSlot slot = list.getConfigurableSlot(i);
                CompoundTag entry = slots.getCompound(i);
                slot.setConfig(entry.isEmpty() ? null : GenericStack.readTag(entry));
                if (virtualStock(slot)) slot.setStock(null);
            }
        }
        if (machine instanceof MECraftingCPUInterfacePartMachine cpu) {
            if (settings.contains("Parallelism")) cpu.setParallelism(settings.getLong("Parallelism"));
            cpu.notifyCraftingCpuChange();
        }
        if (machine instanceof MEPatternBufferPartMachineBase buffer && settings.contains("SharedCircuit")) {
            int circuit = settings.getInt("SharedCircuit");
            buffer.getSharedCircuitInventory().setStackInSlot(0, circuit > 0 ? IntCircuitBehaviour.stack(circuit) : ItemStack.EMPTY);
        }
        boolean allFacing = settings.getBoolean("AllFacing");
        machine.getHolder().self().getPersistentData().putBoolean("isAllFacing", allFacing);
        if (machine instanceof IGridConnectedMachine connected) connected.getMainNode().setExposedOnSides(allFacing ? EnumSet.allOf(Direction.class) : EnumSet.of(machine.getFrontFacing()));
        machine.onChanged();
    }

    public static boolean virtualStock(IConfigurableSlot slot) {
        return slot instanceof IMESlot me && me.isVirtualStock();
    }

    public static CompoundTag contents(MetaMachine machine, Map<AEKey, Long> cost) {
        CompoundTag result = new CompoundTag();
        Set<Object> captured = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var ref : machine.getSyncStorage().getPersistedFields()) {
            Object value = ref.readRaw();
            if (!captured.add(value) || machine.getTraits().contains(value) || value instanceof IConfigurableSlotList) continue;
            Tag contents = null;
            if (value instanceof KeyStorage store) {
                contents = ManipulatorResources.save(store.storage);
                store.storage.forEach((key, amount) -> ManipulatorResources.add(cost, key, amount));
            } else if (!(machine instanceof MEPatternBufferPartMachine) && value instanceof IItemTransfer inventory) {
                ListTag items = new ListTag();
                for (int slot = 0; slot < inventory.getSlots(); slot++) {
                    var stack = inventory.getStackInSlot(slot);
                    items.add(ManipulatorSnapshot.savePhysicalStack(stack));
                    ManipulatorResources.add(cost, stack);
                }
                contents = items;
            } else if (!(machine instanceof MEPatternBufferPartMachine) && value instanceof IFluidTransfer tank) {
                ListTag fluids = new ListTag();
                for (int slot = 0; slot < tank.getTanks(); slot++) {
                    var fluid = tank.getFluidInTank(slot);
                    fluids.add(fluid.saveToTag(new CompoundTag()));
                    if (!fluid.isEmpty()) ManipulatorResources.add(cost, AEFluidKey.of(fluid.getFluid(), fluid.getTag()), fluid.getAmount());
                }
                contents = fluids;
            }
            if (contents != null) TagUtils.setTagExtended(result, ref.getPersistedKey(), contents);
        }
        if (machine instanceof MEExtendedOutputPartMachineBase output) {
            output.prepareManipulatorSnapshot();
            result.put("OutputBuffer", ManipulatorResources.save(output.getBuffer()));
            output.getBuffer().forEach((key, amount) -> ManipulatorResources.add(cost, key, amount));
        }
        if (machine instanceof MEPatternBufferPartMachineBase buffer && !(machine instanceof MEPatternBufferPartMachine)) {
            result.put("OutputBuffer", ManipulatorResources.save(buffer.getBuffer()));
            buffer.getBuffer().forEach((key, amount) -> ManipulatorResources.add(cost, key, amount));
        }
        if (machine instanceof MEMolecularAssemblerIOPartMachine assembler) {
            // Its terminal inventory is a view of separate pattern containers, captured at their own positions.
            result.put("OutputBuffer", ManipulatorResources.save(assembler.getBuffer()));
            assembler.getBuffer().forEach((key, amount) -> ManipulatorResources.add(cost, key, amount));
            Map<AEKey, Long> output = new java.util.LinkedHashMap<>();
            assembler.getOutputItems().forEach((stack, amount) -> ManipulatorResources.add(output, stack.what(), Math.multiplyExact(stack.amount(), amount)));
            result.put("AssemblerOutput", ManipulatorResources.save(output));
            output.forEach((key, amount) -> ManipulatorResources.add(cost, key, amount));
        }
        if (machine instanceof MEOutputPartMachine output) {
            result.put("ReturnBuffer", ManipulatorResources.save(output.getReturnBuffer()));
            output.getReturnBuffer().forEach((key, amount) -> ManipulatorResources.add(cost, key, amount));
        }
        return result;
    }

    public static void restoreContents(MetaMachine machine, CompoundTag contents) {
        Set<Object> restored = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var ref : machine.getSyncStorage().getPersistedFields()) {
            Object value = ref.readRaw();
            if (!restored.add(value) || machine.getTraits().contains(value) || value instanceof IConfigurableSlotList) continue;
            if (!(TagUtils.getTagExtended(contents, ref.getPersistedKey()) instanceof ListTag list)) continue;
            if (value instanceof KeyStorage store) {
                store.storage.clear();
                store.storage.putAll(ManipulatorResources.read(list));
                store.onChanged();
            } else if (!(machine instanceof MEPatternBufferPartMachine) && value instanceof IItemTransfer inventory) {
                for (int slot = 0; slot < Math.min(list.size(), inventory.getSlots()); slot++) inventory.setStackInSlot(slot, ManipulatorSnapshot.readPhysicalStack(list.getCompound(slot)));
            } else if (!(machine instanceof MEPatternBufferPartMachine) && value instanceof IFluidTransfer tank) {
                for (int slot = 0; slot < Math.min(list.size(), tank.getTanks()); slot++) tank.setFluidInTank(slot, FluidStack.loadFromTag(list.getCompound(slot)));
            }
        }
        if (machine instanceof MEExtendedOutputPartMachineBase output && contents.contains("OutputBuffer")) output.restoreManipulatorBuffer(ManipulatorResources.read(contents.getList("OutputBuffer", Tag.TAG_COMPOUND)));
        if (machine instanceof MEPatternBufferPartMachineBase buffer && !(machine instanceof MEPatternBufferPartMachine) && contents.contains("OutputBuffer")) {
            buffer.getBuffer().clear();
            buffer.getBuffer().putAll(ManipulatorResources.read(contents.getList("OutputBuffer", Tag.TAG_COMPOUND)));
        }
        if (machine instanceof MEMolecularAssemblerIOPartMachine assembler && contents.contains("OutputBuffer")) {
            assembler.getBuffer().clear();
            assembler.getBuffer().putAll(ManipulatorResources.read(contents.getList("OutputBuffer", Tag.TAG_COMPOUND)));
            assembler.getOutputItems().clear();
            ManipulatorResources.read(contents.getList("AssemblerOutput", Tag.TAG_COMPOUND)).forEach((key, amount) -> assembler.getOutputItems().put(new GenericStack(key, 1), amount.longValue()));
        }
        if (machine instanceof MEOutputPartMachine output && contents.contains("ReturnBuffer")) {
            output.getReturnBuffer().clear();
            output.getReturnBuffer().putAll(ManipulatorResources.read(contents.getList("ReturnBuffer", Tag.TAG_COMPOUND)));
        }
        if (machine instanceof IMEIOPartMachine part) part.getMETrait().notifySelfIO();
        machine.onChanged();
    }
}
