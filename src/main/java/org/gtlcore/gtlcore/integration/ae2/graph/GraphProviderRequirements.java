package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.network.chat.Component;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.CraftingService;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Main-thread confirmation check for conditions deliberately omitted from machine processing inputs. */
public final class GraphProviderRequirements {

    private final Iterator<IPatternDetails> patterns;
    private final List<Component> blocked = new ArrayList<>();

    public GraphProviderRequirements(AeGraphPlan plan) {
        patterns = plan.patternTimes().keySet().iterator();
    }

    public boolean advance(CraftingService service) {
        for (int work = 0; work < 64 && patterns.hasNext(); work++) {
            var pattern = patterns.next();
            Component missing = missing(pattern, service.getProviders(pattern));
            if (missing != null) blocked.add(missing);
            if (blocked.size() >= 8) return true;
        }
        return !patterns.hasNext();
    }

    public String message() {
        if (blocked.isEmpty()) return "";
        var message = Component.translatable("gtlcore.ae.graph.missing_shared_tools");
        for (var item : blocked) message.append("\n").append(item);
        return Component.Serializer.toJson(message);
    }

    public static Component missing(IPatternDetails pattern, Iterable<ICraftingProvider> providers) {
        Component blocked = null;
        for (var provider : providers) {
            // A busy provider still has the required tool; do not confuse queueing with missing equipment.
            if (!(provider instanceof GraphDispatchPreflight check) || check.gtlcore$canProduceGraphPattern(pattern)) return null;
            if ("MISSING_TOOL".equals(check.gtlcore$graphBlockReason(pattern)) && blocked == null)
                blocked = check.gtlcore$graphBlockMessage(pattern);
        }
        return blocked;
    }
}
