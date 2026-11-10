package org.gtlcore.gtlcore.client.gui;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands quantity suffixes and scientific notation before handing an expression to a parser.
 * For example, {@code 1.5k + 2m} becomes {@code 1500 + 2000000}, {@code 1e6} becomes
 * {@code 1000000}, and {@code 1e3k} becomes {@code 1000000}.
 * <p>
 * An {@code e}/{@code E} that is not followed by an exponent is treated as the Exa suffix
 * ({@code 1e} becomes {@code 1000000000000000000}).
 */
public final class AmountExpressionNormalizer {

    /**
     * Matches one numeric literal, optionally followed by either a scientific exponent
     * (with an optional unit after it) or a quantity suffix.
     */
    private static final Pattern AMOUNT_LITERAL = Pattern.compile(
            "(?<![\\w.])((?:\\d+(?:\\.\\d*)?|\\.\\d+))" +
                    "(?:[eE]([+-]?\\d+)([kKmMgGtTpPeEzZ]?)|([kKmMgGtTpPeEzZ]?))" +
                    "(?![\\w.])");

    /**
     * Limits expansion so a malformed input cannot allocate an arbitrarily large decimal string.
     * Long values only need exponents up to 19, so this is already far beyond practical inputs.
     */
    private static final int MAX_EXPONENT = 1_000;

    private AmountExpressionNormalizer() {}

    public static String normalize(String expression) {
        if (expression == null || expression.isEmpty()) {
            return expression;
        }

        Matcher matcher = AMOUNT_LITERAL.matcher(expression);
        StringBuilder expanded = null;
        int previousEnd = 0;
        while (matcher.find()) {
            String mantissa = matcher.group(1);
            String exponent = matcher.group(2);
            String unit = exponent != null ? matcher.group(3) : matcher.group(4);
            boolean scientific = exponent != null;
            boolean hasUnit = unit != null && !unit.isEmpty();
            if (!scientific && !hasUnit) {
                continue;
            }
            if (!scientific && hasUnit && isExa(unit.charAt(0)) && isIncompleteExponent(expression, matcher.end())) {
                // "1e-"/"1e+" is the beginning of scientific notation, not the Exa suffix.
                continue;
            }

            int scale = 0;
            if (scientific) {
                try {
                    scale = Integer.parseInt(exponent);
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            if (hasUnit) {
                scale += unitExponent(unit.charAt(0));
            }
            if (Math.abs(scale) > MAX_EXPONENT) {
                continue;
            }

            String replacement;
            try {
                BigDecimal amount = new BigDecimal(mantissa);
                if (scale != 0) {
                    amount = amount.scaleByPowerOfTen(scale);
                }
                replacement = amount.toPlainString();
            } catch (ArithmeticException | NumberFormatException ignored) {
                continue;
            }

            if (expanded == null) {
                expanded = new StringBuilder(expression.length() + 16);
            }
            expanded.append(expression, previousEnd, matcher.start())
                    .append(replacement);
            previousEnd = matcher.end();
        }

        if (expanded == null) {
            return expression;
        }
        return expanded.append(expression, previousEnd, expression.length()).toString();
    }

    private static boolean isExa(char unit) {
        return unit == 'e' || unit == 'E';
    }

    private static boolean isIncompleteExponent(String expression, int end) {
        if (end >= expression.length()) {
            return false;
        }
        char next = expression.charAt(end);
        return next == '+' || next == '-';
    }

    private static int unitExponent(char unit) {
        return switch (Character.toLowerCase(unit)) {
            case 'k' -> 3;
            case 'm' -> 6;
            case 'g' -> 9;
            case 't' -> 12;
            case 'p' -> 15;
            case 'e' -> 18;
            case 'z' -> 21;
            default -> 0;
        };
    }
}
