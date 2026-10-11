package org.gtlcore.gtlcore.api.recipe;

import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import java.math.BigDecimal;

public interface IAdvancedContentModifier {

    void setDivision(long numerator, long denominator);

    /** Applies a nonnegative recipe quantity without narrowing through double or saturating. */
    default long applyExact(long value) {
        var modifier = (ContentModifier) this;
        BigDecimal result = BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(modifier.getMultiplier()))
                .add(BigDecimal.valueOf(modifier.getAddition()));
        if (result.signum() < 0) throw new IllegalArgumentException("Negative modified amount");
        return result.toBigInteger().longValueExact();
    }

    static ContentModifier preciseDivision(long numerator, long denominator) {
        var modifier = new ContentModifier(0, 0);
        ((IAdvancedContentModifier) modifier).setDivision(numerator, denominator);
        return modifier;
    }

    static ContentModifier preciseMultiplier(long multiplier) {
        var modifier = new ContentModifier(multiplier, 0);
        ((IAdvancedContentModifier) modifier).setDivision(multiplier, 1);
        return modifier;
    }
}
