package org.gtlcore.gtlcore.integration.jei.tree;

/**
 * Tracks the compounded chance of producing/consuming a branch, exactly like EMI's {@code ChanceState}.
 */
record ChanceState(float chance, boolean chanced) {

    static final ChanceState DEFAULT = new ChanceState(1, false);

    ChanceState produce(float value) {
        if (value == 1) {
            return this;
        }
        return new ChanceState(this.chance / value, true);
    }

    ChanceState consume(float value) {
        if (value == 1) {
            return this;
        }
        return new ChanceState(this.chance * value, true);
    }
}
