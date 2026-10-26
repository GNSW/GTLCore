package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.network.chat.Component;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.CraftingService;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Main-thread confirmation check for conditions deliberately omitted from machine processing inputs. */
public final class GraphProviderRequirements {

    private static final int MAX_SLICE_WORK = 2048;
    private static final long SLICE_NANOS = 1_000_000;
    private final Iterator<IPatternDetails> patterns;
    private final List<Component> blocked = new ArrayList<>();
    private final LongSupplier clock;
    private IPatternDetails current;
    private Iterator<ICraftingProvider> providers;
    private Component problem;
    private boolean complete;

    public GraphProviderRequirements(AeGraphPlan plan) {
        this(plan.patternTimes().keySet().iterator(), System::nanoTime);
    }

    GraphProviderRequirements(Iterator<IPatternDetails> patterns, LongSupplier clock) {
        this.patterns = patterns;
        this.clock = clock;
    }

    public boolean advance(CraftingService service) {
        return advance(service::getProviders);
    }

    boolean advance(Function<IPatternDetails, Iterable<ICraftingProvider>> lookup) {
        if (complete) return true;
        long started = clock.getAsLong();
        for (int work = 0; work < MAX_SLICE_WORK; work++) {
            // World callbacks cannot be preempted, but a large provider list
            // must not force the remainder of this scan into the same tick.
            if (work > 0 && clock.getAsLong() - started >= SLICE_NANOS) return false;
            if (providers == null) {
                if (!patterns.hasNext()) return complete = true;
                current = patterns.next();
                var alternatives = lookup.apply(current).iterator();
                if (!alternatives.hasNext()) continue;
                // Most providers have no shared-tool constraint. Do not copy
                // or even enumerate the remaining alternatives in that case.
                problem = problem(current, alternatives.next());
                if (problem == null) continue;
                // AE's provider list can change between slices. Pin this one
                // pattern's remaining alternatives, rather than a live cursor.
                var captured = new ArrayList<ICraftingProvider>();
                alternatives.forEachRemaining(captured::add);
                providers = captured.iterator();
            } else if (providers.hasNext()) {
                Component missing = problem(current, providers.next());
                if (missing == null) {
                    providers = null; // An alternative has no shared-tool deficit.
                    problem = null;
                } else if (problem == null) problem = missing;
            } else {
                if (problem != null) blocked.add(problem);
                providers = null;
                problem = null;
                if (blocked.size() >= 8) return complete = true;
            }
        }
        return false;
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
            Component missing = problem(pattern, provider);
            if (missing == null) return null;
            if (blocked == null) blocked = missing;
        }
        return blocked;
    }

    private static Component problem(IPatternDetails pattern, ICraftingProvider provider) {
        return provider instanceof GraphDispatchPreflight check ? check.gtlcore$graphSharedToolProblem(pattern) : null;
    }
}
