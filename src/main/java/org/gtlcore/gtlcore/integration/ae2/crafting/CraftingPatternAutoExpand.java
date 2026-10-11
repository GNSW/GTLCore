package org.gtlcore.gtlcore.integration.ae2.crafting;

import org.gtlcore.gtlcore.api.crafting.IAutoExpandSettings;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftIOPart;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.patternrelay.PatternRelayPart;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.helpers.patternprovider.PatternProviderLogic;

public final class CraftingPatternAutoExpand {

    private CraftingPatternAutoExpand() {}

    public static boolean canAutoExpand(boolean processingPattern, ICraftingProvider provider) {
        if (!processingPattern) {
            return false;
        }
        if (provider instanceof IMEPatternPartMachine || provider instanceof IMECraftIOPart) {
            return true;
        }
        // Pattern relays forward expanded batches to the downstream provider that accepts
        // them; capacity is negotiated per downstream in gtlcore$getMaxPatternOperations.
        if (provider instanceof PatternRelayPart relay) {
            return relay.isAccessMode();
        }
        if (provider instanceof IAutoExpandSettings settings) {
            return settings.isPatternAutoExpand();
        }
        return isPatternProviderAutoExpandEnabled() && provider instanceof PatternProviderLogic;
    }

    public static long getOperations(boolean processingPattern, ICraftingProvider provider,
                                     IPatternDetails pattern, long requestedOperations) {
        if (!canAutoExpand(processingPattern, provider) || requestedOperations <= 0) {
            return 1;
        }
        if (provider instanceof IPatternProviderAutoExpand capacityProvider) {
            return Math.max(1, Math.min(requestedOperations,
                    capacityProvider.gtlcore$getMaxPatternOperations(pattern, requestedOperations)));
        }
        return requestedOperations;
    }

    private static boolean isPatternProviderAutoExpandEnabled() {
        return ConfigHolder.INSTANCE != null && ConfigHolder.INSTANCE.ae.ae2PatternProviderAutoExpandDefault;
    }
}
