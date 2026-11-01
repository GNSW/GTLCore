package org.gtlcore.gtlcore.client.gui;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands decimal quantity suffixes before handing an expression to AE2's parser.
 * For example, {@code 1.5k + 2m} becomes {@code 1500 + 2000000}.
 */
public final class AmountExpressionNormalizer {

    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "(?<![\\w.])((?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)([kKmMgGtTpP])(?![\\w.])");

    private AmountExpressionNormalizer() {}

    public static String expandUnits(String expression) {
        if (expression == null || expression.isEmpty()) {
            return expression;
        }

        Matcher matcher = NUMBER_WITH_UNIT.matcher(expression);
        StringBuilder expanded = null;
        int previousEnd = 0;
        while (matcher.find()) {
            String replacement;
            try {
                BigDecimal amount = new BigDecimal(matcher.group(1));
                replacement = amount.scaleByPowerOfTen(unitExponent(matcher.group(2).charAt(0)))
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

    private static int unitExponent(char unit) {
        return switch (Character.toLowerCase(unit)) {
            case 'k' -> 3;
            case 'm' -> 6;
            case 'g' -> 9;
            case 't' -> 12;
            case 'p' -> 15;
            default -> throw new IllegalArgumentException("Unsupported quantity unit: " + unit);
        };
    }
}
