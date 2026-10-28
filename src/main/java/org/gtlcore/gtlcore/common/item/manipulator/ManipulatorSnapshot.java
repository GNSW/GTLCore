package org.gtlcore.gtlcore.common.item.manipulator;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLMachines;
import org.gtlcore.gtlcore.common.item.ConfigurationCopyBehavior;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.blockentity.PipeBlockEntity;
import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.SimpleTieredMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList;

import com.lowdragmc.lowdraglib.syncdata.accessor.IManagedAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.blockentity.AEBaseInvBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.p2p.P2PTunnelPart;
import appeng.util.SettingsFrom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapters explicitly separate configuration from paid physical contents. */
public record ManipulatorSnapshot(BlockState state, ItemStack item, CompoundTag data, Map<AEKey, Long> cost) {

    public static ManipulatorSnapshot plain(BlockState state, ItemStack item) {
        Map<AEKey, Long> cost = new LinkedHashMap<>();
        ManipulatorResources.add(cost, item);
        if (!state.getFluidState().isEmpty() && state.getFluidState().isSource()) {
            ManipulatorResources.add(cost, AEFluidKey.of(state.getFluidState().getType()), net.minecraftforge.fluids.FluidType.BUCKET_VOLUME);
        }
        return new ManipulatorSnapshot(state, item, new CompoundTag(), cost);
    }

    public static ManipulatorSnapshot capture(ServerLevel level, BlockPos pos, ServerPlayer player, ManipulatorSettings settings) {
        BlockState state = level.getBlockState(pos);
        ItemStack item = state.isAir() ? ItemStack.EMPTY : new ItemStack(state.getBlock().asItem());
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof MetaMachineBlockEntity holder && settings.mode == ManipulatorSettings.Mode.COPY &&
                holder.getMetaMachine() instanceof MEPatternBufferPartMachineBase) {
            var result = plain(GTLMachines.GTAEMachines.ME_PATTERN_BUFFER_PROXY.getBlock().defaultBlockState(), GTLMachines.GTAEMachines.ME_PATTERN_BUFFER_PROXY.asStack());
            result.data.putLong("ProxySource", pos.asLong());
            result.data.putString("Kind", "proxy");
            result.data.putString("Facing", holder.getMetaMachine().getFrontFacing().getName());
            return result;
        }
        var result = plain(state, item);
        if (be == null) return result;
        CompoundTag data = result.data;
        data.putLong("Origin", pos.asLong());
        org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorConnections.captureWireless(level, pos, data);
        if (be instanceof org.gtlcore.gtlcore.integration.ae2.wireless.WirelessNetworkCoreBlockEntity core) {
            data.putString("Kind", "wireless_core");
            if (settings.mode == ManipulatorSettings.Mode.MOVE) data.put("Persistent", core.saveWithFullMetadata());
            return result;
        }
        if (settings.mode == ManipulatorSettings.Mode.MOVE) {
            if (!(be instanceof MetaMachineBlockEntity || be instanceof PipeBlockEntity<?, ?> || be instanceof AEBaseBlockEntity || be instanceof Container)) unsupported(be);
            data.putString("Kind", "move");
            data.put("Persistent", be.saveWithFullMetadata());
            if (be instanceof MetaMachineBlockEntity holder && holder.getMetaMachine() instanceof MEPatternBufferPartMachineBase buffer) {
                data.putLongArray("BufferProxies", buffer.getBoundProxyPositions().stream().mapToLong(BlockPos::asLong).toArray());
            }
            result.cost.clear();
            return result;
        }
        if (be instanceof IPartHost host) {
            result.cost.clear(); // the cable bus block is not a real item; its parts are.
            data.putString("Kind", "parts");
            ListTag parts = new ListTag();
            for (int index = 0; index <= Direction.values().length; index++) {
                Direction side = index == Direction.values().length ? null : Direction.values()[index];
                IPart part = host.getPart(side);
                if (part == null) continue;
                CompoundTag partTag = new CompoundTag();
                partTag.putInt("Side", index);
                ItemStack partItem = new ItemStack(part.getPartItem());
                partTag.put("Item", partItem.save(new CompoundTag()));
                ManipulatorResources.add(result.cost, partItem);
                CompoundTag configuration = new CompoundTag();
                part.exportSettings(SettingsFrom.MEMORY_CARD, configuration);
                if (part instanceof P2PTunnelPart<?> tunnel) {
                    partTag.putShort("Frequency", tunnel.getFrequency());
                    partTag.putBoolean("Output", tunnel.isOutput());
                    if (tunnel.getFrequency() != 0) {
                        var input = tunnel.isOutput() ? tunnel.getInput() : tunnel;
                        if (input == null || input.getBlockEntity() == null) throw new IllegalArgumentException("mm.error.p2p_input");
                        partTag.putString("InputGroup", input.getBlockEntity().getLevel().dimension().location() + ":" + input.getBlockEntity().getBlockPos().asLong() + ":" + input.getSide());
                    }
                }
                partTag.put("Configuration", configuration);
                captureAeInventories(part, partTag, result.cost);
                List<ItemStack> drops = new ArrayList<>();
                part.addAdditionalDrops(drops, true);
                verifyAeContents(drops, partTag);
                copyPatterns(part, partTag, result.cost);
                parts.add(partTag);
            }
            ListTag facades = new ListTag();
            for (Direction side : Direction.values()) if (host.getFacadeContainer().getFacade(side) != null) {
                ItemStack facade = host.getFacadeContainer().getFacade(side).getItemStack();
                CompoundTag entry = facade.save(new CompoundTag());
                entry.putInt("Side", side.ordinal());
                facades.add(entry);
                ManipulatorResources.add(result.cost, facade);
            }
            data.put("Facades", facades);
            data.put("Parts", parts);
        } else if (be instanceof MetaMachineBlockEntity holder) {
            MetaMachine machine = holder.getMetaMachine();
            data.putString("Kind", "gt");
            CompoundTag configuration = ConfigurationCopyBehavior.INSTANCE.captureConfiguration(machine);
            if (configuration.isEmpty() && !(machine instanceof MEPatternBufferPartMachine || machine instanceof MEPatternBufferProxyPartMachine)) unsupported(be);
            data.put("Configuration", configuration);
            data.putString("Facing", machine.getFrontFacing().getName());
            data.putInt("Color", machine.getPaintingColor());
            ListTag items = new ListTag();
            ListTag fluids = new ListTag();
            ListTag meStocks = new ListTag();
            for (int t = 0; t < machine.getTraits().size(); t++) {
                var trait = machine.getTraits().get(t);
                if (trait instanceof IConfigurableSlotList list) {
                    for (int slot = 0; slot < list.getConfigurableSlots(); slot++) {
                        var meSlot = list.getConfigurableSlot(slot);
                        GenericStack stock = meSlot.getStock();
                        if (ManipulatorMEState.virtualStock(meSlot) || stock == null || stock.amount() <= 0) continue;
                        CompoundTag entry = GenericStack.writeTag(stock);
                        entry.putInt("Trait", t);
                        entry.putInt("Slot", slot);
                        meStocks.add(entry);
                        ManipulatorResources.add(result.cost, stock.what(), stock.amount());
                    }
                } else if (trait instanceof NotifiableItemStackHandler handler) {
                    // Virtual circuits are settings, never a physical bill of materials.
                    if (machine instanceof SimpleTieredMachine m && handler == m.getCircuitInventory()) continue;
                    if (machine instanceof MEPatternBufferPartMachineBase m && handler == m.getSharedCircuitInventory()) continue;
                    if (machine instanceof com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine m && handler == m.getCircuitInventory()) continue;
                    if (machine instanceof com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine m && handler == m.getCircuitInventory()) continue;
                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (stack.isEmpty()) continue;
                        CompoundTag entry = savePhysicalStack(stack);
                        entry.putInt("Trait", t);
                        entry.putInt("Slot", slot);
                        items.add(entry);
                        ManipulatorResources.add(result.cost, stack);
                    }
                } else if (trait instanceof NotifiableFluidTank tank) {
                    for (int slot = 0; slot < tank.getTanks(); slot++) {
                        var fluid = tank.getFluidInTank(slot);
                        if (fluid.isEmpty()) continue;
                        CompoundTag entry = fluid.saveToTag(new CompoundTag());
                        entry.putInt("Trait", t);
                        entry.putInt("Slot", slot);
                        fluids.add(entry);
                        ManipulatorResources.add(result.cost, AEFluidKey.of(fluid.getFluid(), fluid.getTag()), fluid.getAmount());
                    }
                }
            }
            data.put("Items", items);
            data.put("Fluids", fluids);
            data.put("MEStocks", meStocks);
            if (ManipulatorMEState.supports(machine)) data.put("MEContents", ManipulatorMEState.contents(machine, result.cost));
            if (machine instanceof SimpleTieredMachine simple) {
                ListTag charger = new ListTag();
                for (int slot = 0; slot < simple.getChargerInventory().getSlots(); slot++) {
                    ItemStack stack = simple.getChargerInventory().getStackInSlot(slot);
                    CompoundTag entry = savePhysicalStack(stack);
                    entry.putInt("Slot", slot);
                    charger.add(entry);
                    ManipulatorResources.add(result.cost, stack);
                }
                data.put("Charger", charger);
                if (machine instanceof com.gregtechceu.gtceu.api.capability.IControllable control) data.putBoolean("WorkingEnabled", control.isWorkingEnabled());
            }
            if (machine instanceof MEPatternBufferPartMachine buffer) {
                captureInventory(buffer.getTerminalPatternInventory(), data, "Patterns", result.cost);
                data.put("BufferSettings", buffer.manipulatorSettings());
                data.put("BufferContents", buffer.captureManipulatorContents(result.cost));
            }
            if (machine instanceof MEPatternBufferProxyPartMachine proxy && proxy.getBufferPos() != null) data.putLong("ProxySource", proxy.getBufferPos().asLong());
            captureCovers(machine.getCoverContainer(), data, result.cost);
        } else if (be instanceof PipeBlockEntity<?, ?> pipe) {
            data.putString("Kind", "pipe");
            data.putInt("Connections", pipe.getConnections());
            data.putInt("Blocked", pipe.getBlockedConnections());
            data.putInt("Color", pipe.getPaintingColor());
            captureCovers(pipe.getCoverContainer(), data, result.cost);
            if (pipe.getFrameMaterial() != null) {
                data.putString("Frame", pipe.getFrameMaterial().getResourceLocation().toString());
                ManipulatorResources.add(result.cost, com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper.get(com.gregtechceu.gtceu.api.data.tag.TagPrefix.frameGt, pipe.getFrameMaterial()));
            }
        } else if (be instanceof AEBaseBlockEntity ae) {
            data.putString("Kind", "ae");
            CompoundTag configuration = new CompoundTag();
            ae.exportSettings(SettingsFrom.MEMORY_CARD, configuration, player);
            data.put("Configuration", configuration);
            captureAeInventories(ae, data, result.cost);
            List<ItemStack> drops = new ArrayList<>();
            ae.addAdditionalDrops(level, pos, drops);
            verifyAeContents(drops, data);
            copyPatterns(ae, data, result.cost);
        } else if (be instanceof Container container) {
            // Container is a real inventory contract; additional device configuration needs an adapter.
            if (!(be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity)) unsupported(be);
            data.putString("Kind", "container");
            ListTag contents = new ListTag();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                CompoundTag entry = savePhysicalStack(stack);
                entry.putInt("Slot", slot);
                contents.add(entry);
                ManipulatorResources.add(result.cost, stack);
            }
            data.put("Contents", contents);
        } else unsupported(be);
        return result;
    }

    private static void unsupported(BlockEntity be) {
        var implementation = be instanceof MetaMachineBlockEntity holder ? holder.getMetaMachine() : be;
        GTLCore.LOGGER.warn("Unsupported manipulator snapshot for {} at {} (implementation {})",
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()), be.getBlockPos(), implementation.getClass().getName());
        throw new IllegalArgumentException("mm.error.adapter|" + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
    }

    static CompoundTag savePhysicalStack(ItemStack stack) {
        CompoundTag tag = stack.copyWithCount(1).save(new CompoundTag());
        tag.putInt("Quantity", stack.getCount());
        return tag;
    }

    static ItemStack readPhysicalStack(CompoundTag tag) {
        ItemStack stack = ItemStack.of(tag);
        if (tag.contains("Quantity")) stack.setCount(tag.getInt("Quantity"));
        return stack;
    }

    private static void captureAeInventories(Object owner, CompoundTag data, Map<AEKey, Long> cost) {
        if (owner instanceof AEBaseInvBlockEntity inv) captureInventory(inv.getInternalInventory(), data, "Contents", cost);
        if (owner instanceof PatternContainer patterns) captureInventory(patterns.getTerminalPatternInventory(), data, "Patterns", cost);
        if (owner instanceof IUpgradeableObject upgraded) captureInventory(upgraded.getUpgrades(), data, "Upgrades", cost);
    }

    private static void copyPatterns(Object owner, CompoundTag data, Map<AEKey, Long> cost) {
        if (!(owner instanceof PatternProviderLogicHost)) return;
        // Memory-card imports charge the player's inventory. Restore paid slots directly instead.
        data.getCompound("Configuration").remove(PatternProviderLogic.NBT_MEMORY_CARD_PATTERNS);
        for (Tag entry : data.getList("Patterns", Tag.TAG_COMPOUND)) {
            ItemStack pattern = readPhysicalStack((CompoundTag) entry);
            if (!PatternDetailsHelper.isEncodedPattern(pattern)) continue;
            cost.compute(AEItemKey.of(pattern), (key, amount) -> amount - pattern.getCount());
            ManipulatorResources.add(cost, AEItems.BLANK_PATTERN.stack(pattern.getCount()));
        }
        cost.entrySet().removeIf(entry -> entry.getValue() == 0);
    }

    private static void captureInventory(InternalInventory inventory, CompoundTag tag, String name, Map<AEKey, Long> cost) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = savePhysicalStack(stack);
            entry.putInt("Slot", slot);
            list.add(entry);
            ManipulatorResources.add(cost, stack);
        }
        tag.put(name, list);
    }

    private static void verifyAeContents(List<ItemStack> drops, CompoundTag tag) {
        Map<AEKey, Long> actual = new LinkedHashMap<>();
        drops.forEach(stack -> ManipulatorResources.add(actual, stack));
        Map<AEKey, Long> captured = new LinkedHashMap<>();
        for (String key : List.of("Contents", "Patterns", "Upgrades")) for (Tag entry : tag.getList(key, Tag.TAG_COMPOUND)) ManipulatorResources.add(captured, readPhysicalStack((CompoundTag) entry));
        if (!actual.equals(captured)) throw new IllegalArgumentException("mm.error.ae_inventory");
    }

    private static void captureCovers(ICoverable holder, CompoundTag data, Map<AEKey, Long> cost) {
        ListTag covers = new ListTag();
        for (Direction side : Direction.values()) {
            var cover = holder.getCoverAtSide(side);
            if (cover == null) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Side", side.ordinal());
            entry.putString("Type", cover.coverDefinition.getId().toString());
            entry.put("Item", cover.getAttachItem().save(new CompoundTag()));
            CompoundTag configuration = new CompoundTag();
            IManagedAccessor.readManagedFields(cover, configuration);
            entry.put("Configuration", configuration);
            ManipulatorResources.add(cost, cover.getAttachItem());
            cover.getAdditionalDrops().forEach(stack -> ManipulatorResources.add(cost, stack));
            covers.add(entry);
        }
        data.put("Covers", covers);
    }

    public void restore(ServerLevel level, BlockPos pos, ServerPlayer player, ManipulatorSettings settings) {
        BlockEntity be = level.getBlockEntity(pos);
        switch (data.getString("Kind")) {
            case "wireless_core" -> {
                if (settings.mode == ManipulatorSettings.Mode.MOVE) {
                    CompoundTag persistent = data.getCompound("Persistent").copy();
                    persistent.putInt("x", pos.getX());
                    persistent.putInt("y", pos.getY());
                    persistent.putInt("z", pos.getZ());
                    be.load(persistent);
                    var core = (org.gtlcore.gtlcore.integration.ae2.wireless.WirelessNetworkCoreBlockEntity) be;
                    org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeSavedData.get(level.getServer()).setCore(core.getFrequency(), net.minecraft.core.GlobalPos.of(level.dimension(), pos));
                }
            }
            case "move" -> {
                CompoundTag persistent = data.getCompound("Persistent").copy();
                persistent.putInt("x", pos.getX());
                persistent.putInt("y", pos.getY());
                persistent.putInt("z", pos.getZ());
                level.removeBlockEntity(pos);
                BlockEntity moved = BlockEntity.loadStatic(pos, level.getBlockState(pos), persistent);
                if (moved == null) throw new IllegalStateException("mm.error.restore");
                level.setBlockEntity(moved);
                if (moved instanceof MetaMachineBlockEntity holder && holder.getMetaMachine() instanceof MEPatternBufferProxyPartMachine proxy && proxy.getBufferPos() != null) proxy.setBuffer(reference(proxy.getBufferPos(), settings, pos));
                if (moved instanceof appeng.helpers.patternprovider.PatternProviderLogicHost provider) moveSharedReference(provider, settings, pos);
                if (moved instanceof IPartHost parts) for (Direction side : Direction.values()) {
                    if (parts.getPart(side) instanceof appeng.helpers.patternprovider.PatternProviderLogicHost provider) moveSharedReference(provider, settings, pos);
                }
            }
            case "proxy" -> {
                var proxy = (MEPatternBufferProxyPartMachine) MetaMachine.getMachine(level, pos);
                proxy.setFrontFacing(ManipulatorGeometry.direction(Direction.byName(data.getString("Facing")), settings));
                proxy.setBuffer(BlockPos.of(data.getLong("ProxySource")));
            }
            case "gt" -> {
                MetaMachine machine = ((MetaMachineBlockEntity) be).getMetaMachine();
                machine.setFrontFacing(ManipulatorGeometry.direction(Direction.byName(data.getString("Facing")), settings));
                machine.setPaintingColor(data.getInt("Color"));
                CompoundTag configuration = transformConfiguration(data.getCompound("Configuration"), settings);
                ConfigurationCopyBehavior.INSTANCE.applyConfiguration(configuration, machine);
                for (Tag t : data.getList("MEStocks", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) t;
                    ((IConfigurableSlotList) machine.getTraits().get(entry.getInt("Trait"))).getConfigurableSlot(entry.getInt("Slot")).setStock(GenericStack.readTag(entry));
                }
                if (data.contains("MEContents") && ManipulatorMEState.supports(machine)) ManipulatorMEState.restoreContents(machine, data.getCompound("MEContents"));
                if (machine instanceof SimpleTieredMachine simple) {
                    for (Tag t : data.getList("Charger", Tag.TAG_COMPOUND)) {
                        CompoundTag entry = (CompoundTag) t;
                        simple.getChargerInventory().setStackInSlot(entry.getInt("Slot"), readPhysicalStack(entry));
                    }
                    if (machine instanceof com.gregtechceu.gtceu.api.capability.IControllable control) control.setWorkingEnabled(data.getBoolean("WorkingEnabled"));
                }
                for (Tag t : data.getList("Items", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) t;
                    ((NotifiableItemStackHandler) machine.getTraits().get(entry.getInt("Trait"))).setStackInSlot(entry.getInt("Slot"), readPhysicalStack(entry));
                }
                for (Tag t : data.getList("Fluids", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) t;
                    ((NotifiableFluidTank) machine.getTraits().get(entry.getInt("Trait"))).setFluidInTank(entry.getInt("Slot"), com.lowdragmc.lowdraglib.side.fluid.FluidStack.loadFromTag(entry));
                }
                if (machine instanceof MEPatternBufferPartMachine buffer) {
                    restoreInventory(buffer.getTerminalPatternInventory(), data.getList("Patterns", Tag.TAG_COMPOUND));
                    buffer.applyManipulatorSettings(data.getCompound("BufferSettings"));
                    buffer.restoreManipulatorContents(data.getCompound("BufferContents"));
                }
                if (machine instanceof MEPatternBufferProxyPartMachine proxy && data.contains("ProxySource")) proxy.setBuffer(reference(BlockPos.of(data.getLong("ProxySource")), settings, pos));
                restoreCovers(machine.getCoverContainer(), settings, player);
                machine.onChanged();
            }
            case "pipe" -> {
                var pipe = (PipeBlockEntity<?, ?>) be;
                pipe.setPaintingColor(data.getInt("Color"));
                for (Direction side : Direction.values()) {
                    Direction target = ManipulatorGeometry.direction(side, settings);
                    pipe.setConnection(target, (data.getInt("Connections") & 1 << side.ordinal()) != 0, false);
                    pipe.setBlocked(target, (data.getInt("Blocked") & 1 << side.ordinal()) != 0);
                }
                restoreCovers(pipe.getCoverContainer(), settings, player);
                if (data.contains("Frame")) pipe.setFrameMaterial(com.gregtechceu.gtceu.api.GTCEuAPI.materialManager.getMaterial(data.getString("Frame")));
            }
            case "ae" -> {
                ((AEBaseBlockEntity) be).importSettings(SettingsFrom.MEMORY_CARD, data.getCompound("Configuration"), player);
                restoreAeInventories(be, data);
                restoreSharedPatterns(be, data);
            }
            case "parts" -> {
                var host = (IPartHost) be;
                for (Tag t : data.getList("Parts", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) t;
                    Direction side = entry.getInt("Side") == Direction.values().length ? null : ManipulatorGeometry.direction(Direction.values()[entry.getInt("Side")], settings);
                    IPart part = host.addPart((IPartItem<?>) ItemStack.of(entry.getCompound("Item")).getItem(), side, player);
                    if (part == null) throw new IllegalStateException("mm.error.restore");
                    if (!(part instanceof P2PTunnelPart<?>)) part.importSettings(SettingsFrom.MEMORY_CARD, entry.getCompound("Configuration"), player);
                    restoreAeInventories(part, entry);
                    restoreSharedPatterns(part, entry);
                }
                for (Tag t : data.getList("Facades", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) t;
                    ItemStack facade = readPhysicalStack(entry);
                    var item = (appeng.api.implementations.items.IFacadeItem) facade.getItem();
                    if (!host.getFacadeContainer().addFacade(item.createPartFromItemStack(facade, ManipulatorGeometry.direction(Direction.values()[entry.getInt("Side")], settings)))) throw new IllegalStateException("mm.error.restore");
                }
                host.markForSave();
                host.markForUpdate();
            }
            case "container" -> {
                Container container = (Container) be;
                for (Tag t : data.getList("Contents", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) t;
                    container.setItem(entry.getInt("Slot"), readPhysicalStack(entry));
                }
            }
            default -> {}
        }
        if (level.getBlockEntity(pos) != null) level.getBlockEntity(pos).setChanged();
    }

    private BlockPos reference(BlockPos source, ManipulatorSettings s, BlockPos destination) {
        BlockPos relative = source.subtract(s.min());
        BlockPos size = s.size();
        if (relative.getX() < 0 || relative.getX() >= size.getX() || relative.getY() < 0 || relative.getY() >= size.getY() || relative.getZ() < 0 || relative.getZ() >= size.getZ()) return source;
        BlockPos originalDestination = ManipulatorGeometry.target(BlockPos.of(data.getLong("Origin")).subtract(s.min()), 0, s);
        return ManipulatorGeometry.target(relative, 0, s).offset(destination.subtract(originalDestination));
    }

    private void moveSharedReference(appeng.helpers.patternprovider.PatternProviderLogicHost provider, ManipulatorSettings s, BlockPos pos) {
        var sharing = (org.gtlcore.gtlcore.integration.ae2.manipulator.SharedManipulatorPatterns) provider.getLogic();
        if (sharing.gtlcore$manipulatorSource() != null) sharing.gtlcore$setManipulatorSource(reference(sharing.gtlcore$manipulatorSource(), s, pos), sharing.gtlcore$manipulatorSourceSide());
    }

    private void restoreCovers(ICoverable holder, ManipulatorSettings s, ServerPlayer player) {
        for (Tag t : data.getList("Covers", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            Direction side = ManipulatorGeometry.direction(Direction.values()[entry.getInt("Side")], s);
            var definition = GTRegistries.COVERS.get(new ResourceLocation(entry.getString("Type")));
            if (!holder.placeCoverOnSide(side, ItemStack.of(entry.getCompound("Item")), definition, player)) throw new IllegalStateException("mm.error.restore");
            var cover = holder.getCoverAtSide(side);
            IManagedAccessor.writePersistedFields(entry.getCompound("Configuration"), cover.getSyncStorage().getPersistedFields());
        }
    }

    private static void restoreAeInventories(Object owner, CompoundTag data) {
        if (owner instanceof AEBaseInvBlockEntity inv) restoreInventory(inv.getInternalInventory(), data.getList("Contents", Tag.TAG_COMPOUND));
        if (owner instanceof PatternContainer patterns) restoreInventory(patterns.getTerminalPatternInventory(), data.getList("Patterns", Tag.TAG_COMPOUND));
        if (owner instanceof IUpgradeableObject upgraded) restoreInventory(upgraded.getUpgrades(), data.getList("Upgrades", Tag.TAG_COMPOUND));
    }

    private static void restoreSharedPatterns(Object owner, CompoundTag data) {
        if (data.contains("SharedSource") && owner instanceof appeng.helpers.patternprovider.PatternProviderLogicHost provider) {
            var binding = (org.gtlcore.gtlcore.integration.ae2.manipulator.SharedManipulatorPatterns) provider.getLogic();
            binding.gtlcore$setManipulatorSource(BlockPos.of(data.getLong("SharedSource")), data.contains("SharedSide") ? Direction.values()[data.getInt("SharedSide")] : null);
        }
    }

    private static void restoreInventory(InternalInventory inventory, ListTag list) {
        for (Tag t : list) {
            CompoundTag entry = (CompoundTag) t;
            inventory.setItemDirect(entry.getInt("Slot"), readPhysicalStack(entry));
        }
    }

    private static CompoundTag transformConfiguration(CompoundTag original, ManipulatorSettings s) {
        CompoundTag tag = original.copy();
        for (String key : List.of("outputFacingItems", "outputFacingFluids")) {
            if (tag.contains(key)) tag.putString(key, ManipulatorGeometry.direction(Direction.byName(tag.getString(key)), s).getName());
        }
        for (String key : tag.getAllKeys()) if (tag.get(key) instanceof CompoundTag child) tag.put(key, transformConfiguration(child, s));
        return tag;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("State", NbtUtils.writeBlockState(state));
        tag.put("Item", item.save(new CompoundTag()));
        tag.put("Data", data.copy());
        tag.put("Cost", ManipulatorResources.save(cost));
        return tag;
    }

    public static ManipulatorSnapshot read(CompoundTag tag) {
        return new ManipulatorSnapshot(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("State")),
                ItemStack.of(tag.getCompound("Item")), tag.getCompound("Data"), ManipulatorResources.read(tag.getList("Cost", Tag.TAG_COMPOUND)));
    }
}
