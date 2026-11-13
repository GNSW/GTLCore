package org.gtlcore.gtlcore.integration.monitor;

import org.gtlcore.gtlcore.api.machine.ISteamMachine;
import org.gtlcore.gtlcore.api.machine.computation.*;
import org.gtlcore.gtlcore.mixin.gtm.jade.MonitorProviderInvoker;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jade.provider.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One sampler per screen. Provider instances are stateless; each write dispatches through GTL's Jade Mixins. */
final class MachineMonitorData {

    private static final RecipeLogicProvider LOGIC = new RecipeLogicProvider();
    private static final WorkableBlockProvider WORKABLE = new WorkableBlockProvider();
    private static final ControllableBlockProvider CONTROLLABLE = new ControllableBlockProvider();
    private GTRecipe recipe;
    private MetaMachine targetMachine;
    private CompoundTag contents = new CompoundTag();
    private int contentVersion;

    void invalidate() {
        targetMachine = null;
        recipe = null;
        contents = new CompoundTag();
        contentVersion++;
    }

    CompoundTag contents() {
        return contents;
    }

    CompoundTag sample(ServerLevel level, CompoundTag binding) {
        var state = new CompoundTag();
        if (binding.isEmpty()) return status(state, "unbound");
        var dimensionId = ResourceLocation.tryParse(binding.getString("Dimension"));
        if (dimensionId == null || !binding.hasUUID("Identity")) return status(state, "invalid");
        var dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        var targetLevel = level.getServer().getLevel(dimension);
        BlockPos pos = BlockPos.of(binding.getLong("Position"));
        state.putString("TargetDimension", dimensionId.toString());
        state.putLong("TargetPosition", pos.asLong());
        if (targetLevel == null || !targetLevel.isLoaded(pos)) return status(state, "unloaded");
        var machine = MetaMachine.getMachine(targetLevel, pos);
        if (!(machine instanceof IMultiController controller) ||
                !Objects.requireNonNull(targetLevel.getBlockEntity(pos)).getPersistentData().hasUUID(MachineMonitorLinker.IDENTITY) ||
                !binding.getUUID("Identity").equals(Objects.requireNonNull(targetLevel.getBlockEntity(pos)).getPersistentData().getUUID(MachineMonitorLinker.IDENTITY))) {
            return status(state, "invalid");
        }
        state.putString("MachineName", machine.getDefinition().getDescriptionId());
        if (machine instanceof IRecipeLogicMachine workable) {
            var typeName = machine instanceof IMonitorRecipeType provider ? provider.monitorRecipeTypeName() : net.minecraft.network.chat.Component.translatable(workable.getRecipeType().registryName.toLanguageKey());
            state.putString("RecipeType", typeName.getString());
        }
        if (!controller.isFormed()) return status(state, "unformed");
        if (!(machine instanceof IRecipeLogicMachine workable)) return status(state, "no_recipe_logic");
        var logic = workable.getRecipeLogic();
        ((MonitorProviderInvoker) LOGIC).gtlcore$writeMonitorData(state, logic);
        ((MonitorProviderInvoker) WORKABLE).gtlcore$writeMonitorData(state, logic);
        ((MonitorProviderInvoker) CONTROLLABLE).gtlcore$writeMonitorData(state, logic);
        GTRecipe current = logic.getDuration() > 0 && !logic.isIdle() ? logic.getLastRecipe() : null;
        if (machine != targetMachine || current != recipe) {
            targetMachine = machine;
            recipe = current;
            contents = new CompoundTag();
            contentVersion++;
            if (current != null) {
                contents.put("Inputs", workable.getRecipeLogic() instanceof IMonitorRecipeInputs provider ? provider.monitorInputs() : MachineMonitorEntries.inputs(current));
                applyActualItems(controller, contents.getList("Inputs", Tag.TAG_COMPOUND));
                contents.put("Outputs", MachineMonitorEntries.outputs(current));
            }
        }
        state.putInt("ContentVersion", contentVersion);
        state.putBoolean("HasRecipe", current != null);
        if (current != null && current.data.getBoolean("duration_is_total_cwu")) {
            state.putBoolean("Computation", true);
            state.putLong("CompletedCWU", ((ComputationProgress) logic).gtlcore$completedComputation(current));
            state.putLong("TotalCWU", ComputationAmounts.total(current));
        }
        if (machine instanceof ISteamMachine steam) state.putDouble("SteamConversion", steam.getConversionRate());
        return status(state, !logic.isWorkingEnabled() ? "paused" : logic.isWaiting() ? "waiting" : logic.isWorking() ? "working" : "idle");
    }

    private static void applyActualItems(IMultiController controller, ListTag inputs) {
        List<IRecipeHandler<?>> handlers = new ArrayList<>();
        for (IMultiPart part : controller.getParts()) {
            for (IRecipeHandlerTrait<?> trait : part.getRecipeHandlers()) {
                if (trait.getHandlerIO() == IO.IN) handlers.add(trait);
            }
        }
        for (Tag rowTag : inputs) {
            if (!(rowTag instanceof CompoundTag row)) continue;
            List<ItemStack> addedItems = new ArrayList<>();
            List<FluidStack> addedFluids = new ArrayList<>();
            ListTag matchedItems = new ListTag();
            for (Tag alt : row.getList("Items", Tag.TAG_COMPOUND)) {
                ItemStack stack = ItemStack.of((CompoundTag) alt);
                if (stack.isEmpty()) continue;
                for (IRecipeHandler<?> handler : handlers) {
                    if (handler.getCapability() != ItemRecipeCapability.CAP) continue;
                    for (Object content : handler.getContents()) {
                        if (content instanceof ItemStack in && ItemStack.isSameItemSameTags(in, stack) &&
                                addedItems.stream().noneMatch(added -> ItemStack.isSameItemSameTags(added, in))) {
                            addedItems.add(in);
                            matchedItems.add(in.copyWithCount(1).save(new CompoundTag()));
                        }
                    }
                }
            }
            ListTag matchedFluids = new ListTag();
            for (Tag alt : row.getList("Fluids", Tag.TAG_COMPOUND)) {
                FluidStack stack = FluidStack.loadFluidStackFromNBT((CompoundTag) alt);
                if (stack.isEmpty()) continue;
                for (IRecipeHandler<?> handler : handlers) {
                    if (handler.getCapability() != FluidRecipeCapability.CAP) continue;
                    for (Object content : handler.getContents()) {
                        if (content instanceof FluidStack in && in.isFluidEqual(stack) &&
                                addedFluids.stream().noneMatch(added -> added.isFluidEqual(in))) {
                            addedFluids.add(in);
                            FluidStack copy = in.copy();
                            copy.setAmount(1);
                            matchedFluids.add(copy.writeToNBT(new CompoundTag()));
                        }
                    }
                }
            }
            if (!matchedItems.isEmpty()) {
                row.put("Items", matchedItems);
                row.remove("Fluids");
            }
            if (!matchedFluids.isEmpty()) {
                row.put("Fluids", matchedFluids);
                row.remove("Items");
            }
        }
    }

    private CompoundTag status(CompoundTag state, String status) {
        state.putString("Status", status);
        state.putInt("ContentVersion", contentVersion);
        return state;
    }
}
