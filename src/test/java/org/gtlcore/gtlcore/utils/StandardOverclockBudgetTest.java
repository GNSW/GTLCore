package org.gtlcore.gtlcore.utils;

import java.math.BigInteger;
import java.util.Random;

/** Standalone numerical regression checks; does not bootstrap Minecraft. */
public final class StandardOverclockBudgetTest {

    private static int checks;

    public static void main(String[] args) {
        long parallelEUt = 15_728_640L;
        long supply = 17_179_869_184L;
        expect(5, StandardOverclockBudget.extend(4, parallelEUt, supply), "reported 8A MAX case");
        expect(4, StandardOverclockBudget.extend(4, parallelEUt, 16_106_127_359L), "one EU below fifth OC");
        expect(5, StandardOverclockBudget.extend(4, parallelEUt, 16_106_127_360L), "exact fifth OC threshold");
        expect(1, StandardOverclockBudget.extend(0, 15L, 64L), "fix original zero-budget bypass");
        expect(0, StandardOverclockBudget.extend(0, 15L, 59L), "insufficient power");
        expect(3, StandardOverclockBudget.extend(3, 7L, supply), "preserve ULV exception");
        expect(3, StandardOverclockBudget.extend(3, 8L, supply), "preserve exact ULV boundary");
        expect(-1, StandardOverclockBudget.extend(-1, 0L, supply), "preserve zero EUt");
        expect(3, StandardOverclockBudget.extend(3, -15L, supply), "preserve generators");
        expect(3, StandardOverclockBudget.extend(3, Long.MIN_VALUE, supply), "no abs overflow");
        expect(3, StandardOverclockBudget.extend(3, parallelEUt, 0L), "preserve absent supply");
        expect(9, StandardOverclockBudget.extend(9, parallelEUt, supply), "never shrink original budget");

        // BigInteger is an independent, overflow-free oracle for long boundaries.
        Random random = new Random(0x47544cL);
        for (int i = 0; i < 10_000; ++i) {
            long eut = 9L + random.nextLong(Long.MAX_VALUE - 8L);
            long voltage = random.nextLong(Long.MAX_VALUE);
            int original = random.nextInt(8);
            expect(Math.max(original, oracle(eut, voltage)),
                    StandardOverclockBudget.extend(original, eut, voltage), "random power boundary");
        }
        for (long eut : new long[] { 9L, 15L, parallelEUt, Long.MAX_VALUE / 4L, Long.MAX_VALUE }) {
            expect(oracle(eut, Long.MAX_VALUE),
                    StandardOverclockBudget.extend(0, eut, Long.MAX_VALUE), "long maximum boundary");
        }

        int budget = StandardOverclockBudget.extend(4, parallelEUt, supply);
        long actualEUt = parallelEUt;
        double duration = 400;
        int used = 0;
        while (budget-- > 0 && actualEUt <= supply / 4L) {
            actualEUt *= 4L;
            duration *= 0.5;
            ++used;
        }
        expect(5, used, "reported OC count");
        expect(16_106_127_360L, actualEUt, "reported final EUt");
        expect(12, (int) duration, "reported final duration ticks");
        if (actualEUt > supply || actualEUt <= supply / 4L) {
            throw new AssertionError("must fit supply and reject sixth OC");
        }
        System.out.println("PASS " + checks + " numerical regression checks");
        System.out.println("Reported case: " + actualEUt + " EU/t, " + (int) duration + " ticks, " + used + " OCs");
    }

    private static int oracle(long eut, long voltage) {
        BigInteger power = BigInteger.valueOf(eut);
        BigInteger limit = BigInteger.valueOf(voltage);
        int count = 0;
        while (power.shiftLeft(2).compareTo(limit) <= 0) {
            power = power.shiftLeft(2);
            ++count;
        }
        return count;
    }

    private static void expect(long expected, long actual, String name) {
        ++checks;
        if (expected != actual) throw new AssertionError(name + ": expected=" + expected + ", actual=" + actual);
    }
}
