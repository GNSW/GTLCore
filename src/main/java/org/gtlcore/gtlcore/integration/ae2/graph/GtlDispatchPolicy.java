package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.api.crafting.IAutoExpandSettings;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftIOPart;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.crafting.IPatternProviderAutoExpand;
import org.gtlcore.gtlcore.integration.ae2.patternrelay.PatternRelayPart;

import net.minecraft.core.registries.BuiltInRegistries;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.helpers.patternprovider.PatternProviderLogic;

/** Native graph policy; provider interfaces expose capacity, never a legacy CPU loop. */
final class GtlDispatchPolicy {

    private GtlDispatchPolicy() {}

    static boolean configuration(AEKey key) {
        if (reusable(key)) return true;
        // Non-GT keys cannot be its programmed circuit. Do not initialize GT's
        // registry holders merely to classify vanilla inputs or fluid templates.
        return key instanceof AEItemKey item && BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace().equals("gtceu") &&
                AEUtils.isIntegratedCircuit(key);
    }

    static boolean reusable(AEKey key) {
        return key instanceof AEItemKey item && BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals("gtlcore:virtual_ingredient") &&
                VirtualIngredientBehavior.isMarked(item.getReadOnlyStack()) && !VirtualIngredientBehavior.canonicalStack(item.getReadOnlyStack()).isEmpty();
    }

    static boolean expanded(IPatternDetails pattern, ICraftingProvider provider) {
        if (!pattern.supportsPushInputsToExternalInventory()) return false;
        if (provider instanceof IMEPatternPartMachine || provider instanceof IMECraftIOPart) return true;
        if (provider instanceof PatternRelayPart relay) return relay.isAccessMode();
        if (provider instanceof IAutoExpandSettings settings) return settings.isPatternAutoExpand();
        return ConfigHolder.INSTANCE != null && ConfigHolder.INSTANCE.ae2PatternProviderAutoExpandDefault &&
                provider instanceof PatternProviderLogic;
    }

    static long capacity(IPatternDetails pattern, ICraftingProvider provider, long requested, boolean expanded) {
        if (requested <= 0) return 0;
        if (!expanded) return 1;
        if (provider instanceof IPatternProviderAutoExpand capacity)
            return Math.max(0, Math.min(requested, capacity.gtlcore$getMaxPatternOperations(pattern, requested)));
        return requested;
    }

    static double power(double inputPower, long operations, boolean expanded) {
        if (operations <= 0 || !Double.isFinite(inputPower) || inputPower < 0) throw new IllegalArgumentException("Invalid graph energy quote");
        return expanded ? inputPower / operations : inputPower;
    }
}
