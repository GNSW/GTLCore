package org.gtlcore.gtlcore.integration.monitor;

import org.gtlcore.gtlcore.api.machine.ISteamMachine;
import org.gtlcore.gtlcore.api.machine.computation.*;
import org.gtlcore.gtlcore.mixin.gtm.jade.MonitorProviderInvoker;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jade.provider.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;

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
        if (targetLevel == null || !targetLevel.hasChunkAt(pos)) return status(state, "unloaded");
        var machine = MetaMachine.getMachine(targetLevel, pos);
        if (!(machine instanceof IMultiController controller) ||
                !targetLevel.getBlockEntity(pos).getPersistentData().hasUUID(MachineMonitorLinker.IDENTITY) ||
                !binding.getUUID("Identity").equals(targetLevel.getBlockEntity(pos).getPersistentData().getUUID(MachineMonitorLinker.IDENTITY))) {
            return status(state, "invalid");
        }
        state.putString("MachineName", machine.getDefinition().getDescriptionId());
        if (machine instanceof IRecipeLogicMachine workable) {
            state.putString("RecipeType", workable.getRecipeType().registryName.toLanguageKey());
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
                contents.put("Inputs", MachineMonitorEntries.inputs(current));
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

    private CompoundTag status(CompoundTag state, String status) {
        state.putString("Status", status);
        state.putInt("ContentVersion", contentVersion);
        return state;
    }
}
