package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanSummaryEntry;

import com.gregtechceu.gtceu.api.item.IGTTool;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import org.cgse.core.ExactAmounts;
import org.cgse.core.GraphRecipe;
import org.cgse.core.PlanSummary;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The confirmation table and graph browser describe the same immutable plan. */
public final class GraphPlanSummary {

    private GraphPlanSummary() {}

    public static List<CraftingPlanSummaryEntry> entries(AeGraphPlan plan) {
        Map<String, Map<AEKey, Long>> declared = new LinkedHashMap<>();
        var summary = PlanSummary.entries(plan.graph(), plan.emittedExact(), recipe -> products(plan, recipe, declared));
        Map<DisplayIdentity, Group> groups = new LinkedHashMap<>();
        for (var row : summary) {
            // A specifically requested worn state remains identifiable. Other
            // tools merge only when wear/charge are their sole differences.
            boolean target = row.key().equals(plan.graph().target());
            var identity = new DisplayIdentity(target ? row.key() : toolIdentity(row.key()), target);
            var group = groups.computeIfAbsent(identity, ignored -> new Group(row.key()));
            group.add(row, plan);
        }
        List<CraftingPlanSummaryEntry> entries = new ArrayList<>(groups.size());
        for (var group : groups.values()) {
            // Clamp only at the UI boundary, after all exact arithmetic. Adding
            // separately capped used/missing amounts can wrap even a tiny stock
            // into a negative extraction request for a Long.MAX_VALUE order.
            var entry = new CraftingPlanSummaryEntry(group.key, ExactAmounts.capped(group.missing), ExactAmounts.capped(group.stored),
                    ExactAmounts.capped(group.crafted));
            var details = (ICraftingPlanSummaryEntry) entry;
            details.gtlcore$setCraftTimes(ExactAmounts.capped(group.runs));
            details.gtlcore$setGraphSeed(ExactAmounts.capped(group.seed));
            details.gtlcore$setMissingGraphSeed(group.missingSeed);
            if (group.states > 1) {
                details.gtlcore$setGroupedToolStates(group.states);
                details.gtlcore$setMissingKeys(group.missingKeys);
            }
            entries.add(entry);
        }
        return entries;
    }

    private static Map<AEKey, Long> products(AeGraphPlan plan, GraphRecipe<AEKey> recipe,
                                             Map<String, Map<AEKey, Long>> declared) {
        var outputs = declared.computeIfAbsent(recipe.binding(), binding -> {
            Map<AEKey, Long> result = new LinkedHashMap<>();
            for (var output : plan.bindings().get(binding).getOutputs())
                result.merge(output.what(), output.amount(), Math::addExact);
            return result;
        });
        Map<AEKey, Long> displayed = new LinkedHashMap<>();
        recipe.outputs().forEach((key, amount) -> {
            // Physical state returns stay in the executable graph. They are
            // neither new tool manufacture nor another tool to extract. Keep
            // explicit tool products, including processing-pattern byproducts.
            long product = key instanceof AEItemKey item && item.getItem() instanceof IGTTool && !key.equals(plan.graph().target()) ?
                    Math.min(amount, outputs.getOrDefault(key, 0L)) : amount;
            if (product > 0) displayed.put(key, product);
        });
        return displayed;
    }

    /** UI identity only. Never use this key to extract, dispatch or match a return. */
    static AEKey toolIdentity(AEKey key) {
        if (!(key instanceof AEItemKey item) || !(item.getItem() instanceof IGTTool tool)) return key;
        CompoundTag payload = item.toTag().copy();
        if (payload.contains("tag") && !payload.contains("tag", Tag.TAG_COMPOUND)) return key;
        CompoundTag tag = payload.getCompound("tag");
        if (tag.contains("GT.Tool") && !tag.contains("GT.Tool", Tag.TAG_COMPOUND)) return key;
        CompoundTag state = tag.getCompound("GT.Tool");
        if (state.contains("Damage") && !state.contains("Damage", Tag.TAG_INT)) return key;
        if (state.contains("MaxDamage") && !state.contains("MaxDamage", Tag.TAG_INT)) return key;
        if (tool.isElectric() && tag.contains("Charge") && !tag.contains("Charge", Tag.TAG_LONG)) return key;
        // GT can lazily materialize MaxDamage on the first wear. Inspect a
        // detached stack so that this harmless cache does not split the group.
        if (!state.contains("MaxDamage")) state.putInt("MaxDamage", item.toStack().getMaxDamage());
        state.remove("Damage");
        tag.put("GT.Tool", state);
        if (tool.isElectric()) tag.remove("Charge");
        payload.put("tag", tag);
        AEItemKey identity = AEItemKey.fromTag(payload);
        return identity == null ? key : identity;
    }

    private record DisplayIdentity(AEKey key, boolean target) {}

    private static final class Group {

        final AEKey key;
        BigInteger missing = BigInteger.ZERO, stored = BigInteger.ZERO, crafted = BigInteger.ZERO;
        BigInteger runs = BigInteger.ZERO, seed = BigInteger.ZERO;
        final List<AEKey> missingKeys = new ArrayList<>();
        int states;
        boolean missingSeed;

        Group(AEKey key) {
            this.key = key;
        }

        void add(PlanSummary.Entry<AEKey> row, AeGraphPlan plan) {
            states++;
            missing = missing.add(row.missing());
            stored = stored.add(row.stored());
            crafted = crafted.add(row.crafted());
            runs = runs.add(row.runs());
            long retained = plan.graph().seeds().getOrDefault(row.key(), 0L);
            seed = seed.add(BigInteger.valueOf(retained));
            missingSeed |= retained > 0 && row.missing().signum() > 0;
            if (row.missing().signum() > 0) missingKeys.add(row.key());
        }
    }
}
