package org.gtlcore.gtlcore.utils;

/** Integer-only power budget for the standard fourfold-EU/t overclock rules. */
public final class StandardOverclockBudget {

    private StandardOverclockBudget() {}

    /**
     * Only extends the existing tier budget. The selected overclock logic still
     * enforces its own voltage, duration, temperature and sub-tick constraints.
     * Callers must restrict this to standard fourfold-EU/t overclock rules.
     */
    public static int extend(int originalBudget, long eut, long maxVoltage) {
        // Preserve the existing ULV exception and all non-consumer behavior.
        if (eut <= 8L || maxVoltage <= 0L) return originalBudget;

        int powerBudget = 0;
        long candidateEUt = eut;
        // Divide first, so the multiplication cannot overflow a positive long.
        while (candidateEUt <= maxVoltage / 4L) {
            candidateEUt *= 4L;
            ++powerBudget;
        }
        return Math.max(originalBudget, powerBudget);
    }
}
