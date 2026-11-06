package org.gtlcore.gtlcore.client.gui;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands quantity suffixes and uppercase-E scientific notation before handing an expression to AE2's parser.
 * For example, {@code 1.5k + 2m} becomes {@code 1500 + 2000000}, and {@code 1E10} becomes
 * {@code 10000000000}. A suffix without an exponent, such as {@code 1e}, denotes Exa.
 */
public final class AmountExpressionNormalizer {

    private static final Pattern AMOUNT_LITERAL = Pattern.compile(
            "(?<![\\w.])((?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:E[+-]?\\d+)?)([kKmMgGtTpPeEzZ]?)(?![\\w.])");

    private AmountExpressionNormalizer() {}

    public static String normalize(String expression) {
        if (expression == null || expression.isEmpty()) {
            return expression;
        }

        Matcher matcher = AMOUNT_LITERAL.matcher(expression);
        StringBuilder expanded = null;
        int previousEnd = 0;
        while (matcher.find()) {
            String number = matcher.group(1);
            String unit = matcher.group(2);
            if (unit.isEmpty() && !hasExponent(number)) {
                continue;
            }

            String replacement;
            try {
                BigDecimal amount = new BigDecimal(number);
                replacement = amount.scaleByPowerOfTen(unit.isEmpty() ? 0 : unitExponent(unit.charAt(0)))
                        .toPlainString();
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

    private static boolean hasExponent(String number) {
        return number.indexOf('E') >= 0;
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
            default -> throw new IllegalArgumentException("Unsupported quantity unit: " + unit);
        };
    }
}
