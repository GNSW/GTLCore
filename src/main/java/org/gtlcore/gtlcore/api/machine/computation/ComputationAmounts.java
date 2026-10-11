package org.gtlcore.gtlcore.api.machine.computation;

import org.gtlcore.gtlcore.api.recipe.IAdvancedContentModifier;
import org.gtlcore.gtlcore.api.recipe.IAdvancedOCResult;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;

import java.math.BigDecimal;

/** Exact recipe quantities. Capacity displays may saturate; recipe debits must never do so. */
public final class ComputationAmounts {

    public static final String TOTAL_CWU = "gtlcore_total_cwu";

    private ComputationAmounts() {}

    public static long read(Object value) {
        if (!(value instanceof Number) && !(value instanceof CharSequence))
            throw new IllegalArgumentException("CWU must be an integer: " + value);
        long amount = value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte ?
                ((Number) value).longValue() : new BigDecimal(value.toString()).longValueExact();
        if (amount < 0) throw new IllegalArgumentException("CWU must be nonnegative: " + value);
        return amount;
    }

    /** Keep the boxed Integer ABI for old recipes, while allowing long content on the erased capability API. */
    public static Number box(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative CWU");
        if (value <= Integer.MAX_VALUE) return Integer.valueOf((int) value);
        return Long.valueOf(value);
    }

    public static long modify(long value, ContentModifier modifier) {
        if (value < 0 || !Double.isFinite(modifier.getMultiplier()) || !Double.isFinite(modifier.getAddition()))
            throw new IllegalArgumentException("Invalid CWU modifier");
        long result = ((IAdvancedContentModifier) modifier).applyExact(value);
        if (result < 0) throw new IllegalArgumentException("Negative modified CWU");
        return result;
    }

    public static long total(GTRecipe recipe) {
        return recipe.data.contains(TOTAL_CWU) ? Math.max(1, recipe.data.getLong(TOTAL_CWU)) : Math.max(1, recipe.duration);
    }

    public static void applyOverclock(GTRecipe recipe, OCResult result) {
        if (!recipe.data.getBoolean("duration_is_total_cwu") || !recipe.data.contains(TOTAL_CWU)) return;
        var advanced = (IAdvancedOCResult) (Object) result;
        long total = ComputationMath.overclockTotal(total(recipe), recipe.duration, result.getDuration(),
                advanced.getDurationFactor(), advanced.getBaseOCLevel());
        // Recipe copies may share data with the cached original. Never discount it.
        recipe.data = recipe.data.copy();
        recipe.data.putLong(TOTAL_CWU, total);
    }

    public static int duration(long total) {
        if (total <= 0) throw new IllegalArgumentException("Total research CWU must be positive");
        return ComputationMath.toInt(total);
    }
}
