package org.gtlcore.gtlcore.integration.monitor;

import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.*;

import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.*;

/** Shared recipe quantity serialization for Jade and the machine monitor. Never executes recipe IO. */
public final class MachineMonitorEntries {

    public static final String BATCH_INPUTS = "gtlcore_monitor_inputs";

    private MachineMonitorEntries() {}

    public static ListTag inputs(GTRecipe recipe) {
        if (recipe.data.contains(BATCH_INPUTS)) return recipe.data.getList(BATCH_INPUTS, Tag.TAG_COMPOUND).copy();
        return entries(recipe.inputs, recipe.tickInputs);
    }

    public static ListTag outputs(GTRecipe recipe) {
        return entries(recipe.outputs, recipe.tickOutputs);
    }

    private static ListTag entries(Map<RecipeCapability<?>, List<Content>> once, Map<RecipeCapability<?>, List<Content>> ticking) {
        ListTag rows = new ListTag();
        append(rows, once, false);
        append(rows, ticking, true);
        return merge(rows);
    }

    private static void append(ListTag rows, Map<RecipeCapability<?>, List<Content>> contents, boolean tick) {
        for (var content : contents.getOrDefault(ItemRecipeCapability.CAP, List.of())) {
            Ingredient ingredient = (Ingredient) content.content;
            Ingredient icons = ingredient;
            var row = base(content, tick);
            long amount;
            if (ingredient instanceof LongIngredient sized) amount = sized.getActualAmount();
            else if (ingredient instanceof SizedIngredient sized) amount = sized.getAmount();
            else if (ingredient instanceof IntProviderIngredient range) {
                icons = range.getInner();
                amount = range.getCountProvider().getMinValue();
                row.putLong("MaxAmount", range.getCountProvider().getMaxValue());
            } else amount = 1;
            ItemStack[] stacks = icons.getItems();
            if (stacks.length == 0) continue;
            if (!(ingredient instanceof SizedIngredient) && !(ingredient instanceof IntProviderIngredient)) amount = stacks[0].getCount();
            ListTag alternatives = new ListTag();
            for (var stack : stacks) {
                if (!stack.isEmpty()) alternatives.add(stack.copyWithCount(1).save(new CompoundTag()));
            }
            if (alternatives.isEmpty()) continue;
            row.put("Items", alternatives);
            row.putLong("Amount", amount);
            rows.add(row);
        }
        for (var content : contents.getOrDefault(FluidRecipeCapability.CAP, List.of())) {
            var ingredient = (FluidIngredient) content.content;
            var stacks = ingredient.getStacks();
            if (stacks.length == 0) continue;
            var row = base(content, tick);
            ListTag alternatives = new ListTag();
            for (var fluid : stacks) if (!fluid.isEmpty()) alternatives.add(fluid.copy(1).saveToTag(new CompoundTag()));
            if (alternatives.isEmpty()) continue;
            row.put("Fluids", alternatives);
            row.putLong("Amount", ingredient.getAmount());
            rows.add(row);
        }
    }

    private static CompoundTag base(Content content, boolean tick) {
        var row = new CompoundTag();
        row.putBoolean("Tick", tick);
        row.putBoolean("NonConsumed", content.chance == 0);
        row.putInt("Chance", content.chance);
        row.putInt("MaxChance", content.maxChance);
        row.putInt("TierChanceBoost", content.tierChanceBoost);
        return row;
    }

    public static ListTag merge(ListTag rows) {
        Map<CompoundTag, CompoundTag> grouped = new LinkedHashMap<>();
        for (Tag tag : rows) {
            var row = (CompoundTag) tag;
            var key = row.copy();
            key.remove("Amount");
            key.remove("MaxAmount");
            var previous = grouped.get(key);
            if (previous == null) grouped.put(key, row.copy());
            else if (row.getBoolean("NonConsumed")) {
                // Shared catalysts are required, not consumed once per subrecipe.
                previous.putLong("Amount", Math.max(previous.getLong("Amount"), row.getLong("Amount")));
            } else {
                previous.putLong("Amount", NumberUtils.saturatedAdd(previous.getLong("Amount"), row.getLong("Amount")));
                if (row.contains("MaxAmount")) previous.putLong("MaxAmount", NumberUtils.saturatedAdd(previous.getLong("MaxAmount"), row.getLong("MaxAmount")));
            }
        }
        ListTag result = new ListTag();
        result.addAll(grouped.values());
        return result;
    }

    /** Keeps Jade's long-count wire format; the monitor retains the richer rows as well. */
    public static void writeJadeOutputs(CompoundTag data, GTRecipe recipe) {
        ListTag items = new ListTag(), fluids = new ListTag();
        for (var tag : outputs(recipe)) {
            var row = (CompoundTag) tag;
            if (row.getBoolean("Tick")) continue;
            var alternatives = row.getList("Items", Tag.TAG_COMPOUND);
            if (!alternatives.isEmpty()) {
                var item = alternatives.getCompound(0).copy();
                item.putLong("Count", row.getLong("Amount"));
                items.add(item);
            } else {
                var fluid = row.getList("Fluids", Tag.TAG_COMPOUND).getCompound(0).copy();
                fluid.putLong("Amount", row.getLong("Amount"));
                fluids.add(fluid);
            }
        }
        if (!items.isEmpty()) data.put("OutputItems", items);
        if (!fluids.isEmpty()) data.put("OutputFluids", fluids);
    }
}
