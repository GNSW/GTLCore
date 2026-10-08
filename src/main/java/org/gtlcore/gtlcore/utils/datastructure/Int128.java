package org.gtlcore.gtlcore.utils.datastructure;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 128位有符号整数实现，使用两个long保存补码，支持原地修改
 * Exact方法会在结果溢出时报错
 * 实例与常量均为可变对象
 */
@SuppressWarnings({ "unused", "DuplicatedCode", "UnusedReturnValue", "LombokGetterMayBeUsed" })
public final class Int128 extends Number implements Comparable<Int128> {

    // 高64位和低64位
    private long high;
    private long low;

    // =================== 常量 ===================

    public static final Int128 MAX_VALUE = new Int128(0x7FFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL);
    public static final Int128 MIN_VALUE = new Int128(0x8000000000000000L, 0);

    public static Int128 ZERO() {
        return new Int128(0, 0);
    }

    public static Int128 ONE() {
        return new Int128(0, 1);
    }

    public static Int128 NEGATIVE_ONE() {
        return new Int128(-1L, -1L);
    }

    // =================== 构造/赋值 ===================

    public Int128(long high, long low) {
        this.high = high;
        this.low = low;
    }

    public Int128(long value) {
        this.high = value < 0 ? -1L : 0;
        this.low = value;
    }

    public Int128() {
        this.high = 0;
        this.low = 0;
    }

    public long getHigh() {
        return high;
    }

    public long getLow() {
        return low;
    }

    public Int128 set(long high, long low) {
        this.high = high;
        this.low = low;
        return this;
    }

    public Int128 set(Int128 other) {
        this.high = other.high;
        this.low = other.low;
        return this;
    }

    public Int128 copy() {
        return new Int128(this.high, this.low);
    }

    // =================== 基本数值转换 ===================

    @Override
    public int intValue() {
        long value = longValue();
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE :
                value < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
    }

    @Override
    public long longValue() {
        // 正常范围内的数字
        if ((high == 0 && low >= 0) || (high == -1L && low < 0)) {
            return low;
        }

        // 超出范围，饱和到边界值
        return isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
    }

    public long longValueExact() {
        if ((high == 0 && low >= 0) || (high == -1L && low < 0)) {
            return low;
        }
        throw new ArithmeticException("Int128 does not fit in a long");
    }

    public int intValueExact() {
        long value = longValueExact();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ArithmeticException("Int128 does not fit in an int");
        }
        return (int) value;
    }

    @Override
    public float floatValue() {
        return (float) toFloatingPoint(24);
    }

    @Override
    public double doubleValue() {
        return toDouble();
    }

    // =================== 算术 ===================

    public Int128 add(long other) {
        long newLow = this.low + other;
        long newHigh = this.high;

        if (Long.compareUnsigned(newLow, this.low) < 0) {
            newHigh++; // 发生无符号进位
        }

        if (other < 0) {
            newHigh--;
        }

        this.low = newLow;
        this.high = newHigh;
        return this;
    }

    public Int128 add(Int128 other) {
        long newLow = this.low + other.low;
        long newHigh = this.high + other.high;

        if (Long.compareUnsigned(newLow, this.low) < 0) {
            newHigh++;
        }

        this.low = newLow;
        this.high = newHigh;
        return this;
    }

    public static Int128 add(Int128 a, Int128 b, Int128 result) {
        long newLow = a.low + b.low;
        long newHigh = a.high + b.high;

        if (Long.compareUnsigned(newLow, a.low) < 0) {
            newHigh++;
        }

        result.low = newLow;
        result.high = newHigh;
        return result;
    }

    public static Int128 sum(Int128 a, Int128 b) {
        return a.add(b);
    }

    public Int128 subtract(Int128 other) {
        long newLow = this.low - other.low;
        long newHigh = this.high - other.high;

        // 处理无符号借位
        if (Long.compareUnsigned(this.low, other.low) < 0) {
            newHigh--;
        }

        this.low = newLow;
        this.high = newHigh;
        return this;
    }

    public static Int128 subtract(Int128 a, Int128 b, Int128 result) {
        long newLow = a.low - b.low;
        long newHigh = a.high - b.high;

        if (Long.compareUnsigned(a.low, b.low) < 0) {
            newHigh--;
        }

        result.low = newLow;
        result.high = newHigh;
        return result;
    }

    public Int128 multiply(Int128 other) {
        return multiply(this, other, this);
    }

    public static Int128 multiply(Int128 a, Int128 b, Int128 result) {
        long newLow = a.low * b.low;
        long newHigh = unsignedMultiplyHigh(a.low, b.low) + a.high * b.low + a.low * b.high;
        return result.set(newHigh, newLow);
    }

    public Int128 multiply(long multiplier) {
        long newLow = low * multiplier;
        long newHigh = unsignedMultiplyHigh(low, multiplier) + high * multiplier - (multiplier < 0 ? low : 0);
        return set(newHigh, newLow);
    }

    public static Int128 multiply(Int128 a, long multiplier, Int128 result) {
        result.set(a.high, a.low);
        return result.multiply(multiplier);
    }

    private static long unsignedMultiplyHigh(long a, long b) {
        return Math.multiplyHigh(a, b) + ((a >> 63) & b) + ((b >> 63) & a);
    }

    /**
     * 向零截断，余数与被除数同号
     * 除数可以和商或余数共用对象，但商和余数必须是不同对象
     * MIN_VALUE / -1按补码回绕，需要拒绝溢出时使用divideExact
     */
    public Int128 divide(Int128 divisor, Int128 remainder) {
        Objects.requireNonNull(divisor, "divisor");
        Objects.requireNonNull(remainder, "remainder");
        // noinspection NumberEquality
        if (remainder == this) {
            throw new IllegalArgumentException("Quotient and remainder must be different objects");
        }
        if (divisor.isZero()) {
            throw new ArithmeticException("Division by zero");
        }

        boolean negativeDividend = high < 0;
        boolean negativeResult = negativeDividend != (divisor.high < 0);
        long dividendHigh = high;
        long dividendLow = low;
        long divisorHigh = divisor.high;
        long divisorLow = divisor.low;

        if (negativeDividend) {
            dividendLow = -dividendLow;
            dividendHigh = ~dividendHigh + (dividendLow == 0 ? 1 : 0);
        }
        if (divisorHigh < 0) {
            divisorLow = -divisorLow;
            divisorHigh = ~divisorHigh + (divisorLow == 0 ? 1 : 0);
        }

        long remainderHigh = 0;
        long remainderLow;
        if (divisorHigh == 0) {
            remainderLow = divideUnsignedByLong(dividendHigh, dividendLow, divisorLow, this);
        } else {
            int comparison = compareUnsigned(dividendHigh, dividendLow, divisorHigh, divisorLow);
            if (comparison < 0) {
                set(0, 0);
                remainderHigh = dividendHigh;
                remainderLow = dividendLow;
            } else if (comparison == 0) {
                set(0, 1);
                remainderLow = 0;
            } else {
                // 对齐最高有效位后移位减法，参照Abseil 20260107.1的DivModImpl算法。
                // 幅值至多2^127，除数至少2^64，所以商只需要低64位，shift在0..63。
                int shift = Long.numberOfLeadingZeros(divisorHigh) - Long.numberOfLeadingZeros(dividendHigh);
                long alignedHigh = divisorHigh;
                long alignedLow = divisorLow;
                if (shift != 0) {
                    alignedHigh = (divisorHigh << shift) | (divisorLow >>> (64 - shift));
                    alignedLow = divisorLow << shift;
                }
                remainderHigh = dividendHigh;
                remainderLow = dividendLow;
                long quotientLow = 0;
                for (int i = shift; i >= 0; i--) {
                    if (compareUnsigned(remainderHigh, remainderLow, alignedHigh, alignedLow) >= 0) {
                        long borrow = Long.compareUnsigned(remainderLow, alignedLow) < 0 ? 1 : 0;
                        remainderLow -= alignedLow;
                        remainderHigh -= alignedHigh + borrow;
                        quotientLow |= 1L << i;
                    }
                    alignedLow = (alignedLow >>> 1) | (alignedHigh << 63);
                    alignedHigh >>>= 1;
                }
                set(0, quotientLow);
            }
        }
        if (negativeResult) {
            negate();
        }
        remainder.set(remainderHigh, remainderLow);
        if (negativeDividend) {
            remainder.negate();
        }
        return this;
    }

    public Int128 divide(long divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("Division by zero");
        }
        boolean negativeResult = (high < 0) != (divisor < 0);
        long dividendHigh = high;
        long dividendLow = low;

        if (dividendHigh < 0) {
            dividendLow = -dividendLow;
            dividendHigh = ~dividendHigh + (dividendLow == 0 ? 1 : 0);
        }

        // Long.MIN_VALUE的绝对值应按无符号幅值2^63处理，不能当作正的有符号long
        long magnitude = divisor < 0 ? -divisor : divisor;
        divideUnsignedByLong(dividendHigh, dividendLow, magnitude, this);
        if (negativeResult) {
            negate();
        }
        return this;
    }

    public Int128 divideNew(long divisor) {
        return copy().divide(divisor);
    }

    private static int compareUnsigned(long aHigh, long aLow, long bHigh, long bLow) {
        int highComparison = Long.compareUnsigned(aHigh, bHigh);
        return highComparison != 0 ? highComparison : Long.compareUnsigned(aLow, bLow);
    }

    /** 将无符号128位整数除以无符号64位除数，返回无符号余数 */
    private static long divideUnsignedByLong(long high, long low, long divisor, Int128 quotient) {
        if (divisor == 0) {
            throw new ArithmeticException("Division by zero");
        }
        if (high == 0) {
            quotient.set(0, Long.divideUnsigned(low, divisor));
            return Long.remainderUnsigned(low, divisor);
        }
        if ((divisor & (divisor - 1)) == 0) {
            int shift = Long.numberOfTrailingZeros(divisor);
            if (shift == 0) {
                quotient.set(high, low);
                return 0;
            }
            quotient.set(high >>> shift, (low >>> shift) | (high << (64 - shift)));
            return low & (divisor - 1);
        }
        long quotientHigh = Long.divideUnsigned(high, divisor);
        long remainder = Long.remainderUnsigned(high, divisor);
        if (remainder == 0) {
            quotient.set(quotientHigh, Long.divideUnsigned(low, divisor));
            return Long.remainderUnsigned(low, divisor);
        }
        long quotientLow;
        if (Long.compareUnsigned(divisor, 0xFFFFFFFFL) <= 0) {
            long combined = (remainder << 32) | (low >>> 32);
            long upper = Long.divideUnsigned(combined, divisor);
            remainder = Long.remainderUnsigned(combined, divisor);
            combined = (remainder << 32) | (low & 0xFFFFFFFFL);
            quotientLow = (upper << 32) | Long.divideUnsigned(combined, divisor);
            remainder = Long.remainderUnsigned(combined, divisor);
        } else {
            // Adapted from Go 1.24.0 math/bits.Div64 (Copyright 2017 The Go Authors).
            // BSD-3-Clause notice: META-INF/licenses/int128-go.txt.
            // 先除高字保证remainder < divisor，再以2^32为基数估算并校正商的两个字。
            int shift = Long.numberOfLeadingZeros(divisor);
            long normalizedDivisor = divisor << shift;
            long divisorUpper = normalizedDivisor >>> 32;
            long divisorLower = normalizedDivisor & 0xFFFFFFFFL;
            // Java的long移位距离会取模64，必须单独处理shift == 0。
            long dividendUpper = (remainder << shift) | (shift == 0 ? 0 : low >>> (64 - shift));
            long dividendLower = low << shift;
            long middle = dividendLower >>> 32;
            long bottom = dividendLower & 0xFFFFFFFFL;
            long base = 1L << 32;

            long upper = Long.divideUnsigned(dividendUpper, divisorUpper);
            long estimateRemainder = dividendUpper - upper * divisorUpper;
            while (upper >= base || Long.compareUnsigned(upper * divisorLower, (estimateRemainder << 32) + middle) > 0) {
                upper--;
                estimateRemainder += divisorUpper;
                if (estimateRemainder >= base) {
                    break;
                }
            }

            long combined = (dividendUpper << 32) + middle - upper * normalizedDivisor;
            long lower = Long.divideUnsigned(combined, divisorUpper);
            estimateRemainder = combined - lower * divisorUpper;
            while (lower >= base || Long.compareUnsigned(lower * divisorLower, (estimateRemainder << 32) + bottom) > 0) {
                lower--;
                estimateRemainder += divisorUpper;
                if (estimateRemainder >= base) {
                    break;
                }
            }

            quotientLow = (upper << 32) | lower;
            remainder = ((combined << 32) + bottom - lower * normalizedDivisor) >>> shift;
        }
        quotient.set(quotientHigh, quotientLow);
        return remainder;
    }

    /** 精确计算x * numerator / denominator的向下取整值，只检查最终非负结果是否超出有符号128位范围 */
    public static Int128 multiplyDivideNonNegative(long high, long low, long numerator, long denominator) {
        if (high < 0 || numerator < 0 || denominator <= 0) {
            throw new ArithmeticException("Non-negative material ratio requires x >= 0, numerator >= 0 and denominator > 0");
        }
        if (numerator == 0 || (high == 0 && low == 0)) {
            return ZERO();
        }
        if (numerator == denominator) {
            return new Int128(high, low);
        }
        if (denominator == 1) {
            return multiplyExact(high, low, numerator);
        }

        Int128 result = new Int128();
        long remainder = divideUnsignedByLong(high, low, denominator, result);
        result.multiplyExact(numerator);

        if (remainder != 0) {
            // 两个因子都小于2^63，乘积小于2^126，无需更宽的中间值
            Int128 tail = new Int128(unsignedMultiplyHigh(remainder, numerator), remainder * numerator);
            tail.divide(denominator);
            result.addExact(tail);
        }
        return result;
    }

    // =================== 算术(Exact) ===================

    public Int128 addExact(long other) {
        return addExactWords(other < 0 ? -1L : 0, other);
    }

    public Int128 addExact(Int128 other) {
        return addExactWords(other.high, other.low);
    }

    public static Int128 addExact(long aHigh, long aLow, long bHigh, long bLow) {
        return new Int128(aHigh, aLow).addExactWords(bHigh, bLow);
    }

    private Int128 addExactWords(long otherHigh, long otherLow) {
        long newLow = low + otherLow;
        long carry = Long.compareUnsigned(newLow, low) < 0 ? 1 : 0;
        long newHigh = high + otherHigh + carry;
        if (((high ^ newHigh) & (otherHigh ^ newHigh)) < 0) {
            throw new ArithmeticException("Int128 addition overflow");
        }
        return set(newHigh, newLow);
    }

    public Int128 subtractExact(long other) {
        return subtractExactWords(other < 0 ? -1L : 0, other);
    }

    public Int128 subtractExact(Int128 other) {
        return subtractExactWords(other.high, other.low);
    }

    public static Int128 subtractExact(long aHigh, long aLow, long bHigh, long bLow) {
        return new Int128(aHigh, aLow).subtractExactWords(bHigh, bLow);
    }

    private Int128 subtractExactWords(long otherHigh, long otherLow) {
        long newLow = low - otherLow;
        long borrow = Long.compareUnsigned(low, otherLow) < 0 ? 1 : 0;
        long newHigh = high - otherHigh - borrow;
        if (((high ^ otherHigh) & (high ^ newHigh)) < 0) {
            throw new ArithmeticException("Int128 subtraction overflow");
        }
        return set(newHigh, newLow);
    }

    public Int128 multiplyExact(Int128 other) {
        return setMultiplyExact(high, low, other.high, other.low);
    }

    public Int128 multiplyExact(long other) {
        return setMultiplyExact(high, low, other < 0 ? -1L : 0, other);
    }

    public static Int128 multiplyExact(long aHigh, long aLow, long bHigh, long bLow) {
        return new Int128().setMultiplyExact(aHigh, aLow, bHigh, bLow);
    }

    public static Int128 multiplyExact(long high, long low, long multiplier) {
        return multiplyExact(high, low, multiplier < 0 ? -1L : 0, multiplier);
    }

    private Int128 setMultiplyExact(long aHigh, long aLow, long bHigh, long bLow) {
        boolean negative = (aHigh < 0) != (bHigh < 0);

        if (aHigh < 0) {
            aLow = -aLow;
            aHigh = ~aHigh + (aLow == 0 ? 1 : 0);
        }
        if (bHigh < 0) {
            bLow = -bLow;
            bHigh = ~bHigh + (bLow == 0 ? 1 : 0);
        }

        long word0 = aLow * bLow;
        long word1 = unsignedMultiplyHigh(aLow, bLow);
        if (aHigh == 0 && bHigh == 0) {
            return setSignedMagnitudeExact(word1, word0, negative);
        }

        long cross = aLow * bHigh;
        long sum = word1 + cross;
        long carry = Long.compareUnsigned(sum, word1) < 0 ? 1 : 0;
        word1 = sum;
        cross = aHigh * bLow;
        sum = word1 + cross;
        carry += Long.compareUnsigned(sum, word1) < 0 ? 1 : 0;
        word1 = sum;

        long word2 = unsignedMultiplyHigh(aLow, bHigh);
        cross = unsignedMultiplyHigh(aHigh, bLow);
        sum = word2 + cross;
        long upperCarry = Long.compareUnsigned(sum, word2) < 0 ? 1 : 0;
        word2 = sum;
        cross = aHigh * bHigh;
        sum = word2 + cross;
        upperCarry += Long.compareUnsigned(sum, word2) < 0 ? 1 : 0;
        word2 = sum;
        sum = word2 + carry;
        upperCarry += Long.compareUnsigned(sum, word2) < 0 ? 1 : 0;
        word2 = sum;
        long word3 = unsignedMultiplyHigh(aHigh, bHigh) + upperCarry;

        if (word2 != 0 || word3 != 0) {
            throw new ArithmeticException("Int128 multiplication overflow");
        }
        return setSignedMagnitudeExact(word1, word0, negative);
    }

    private Int128 setSignedMagnitudeExact(long word1, long word0, boolean negative) {
        if (word1 < 0 && (!negative || word1 != Long.MIN_VALUE || word0 != 0)) {
            throw new ArithmeticException("Int128 multiplication overflow");
        }
        if (negative) {
            word0 = -word0;
            word1 = ~word1 + (word0 == 0 ? 1 : 0);
        }
        return set(word1, word0);
    }

    public Int128 divideExact(long divisor) {
        if (high == Long.MIN_VALUE && low == 0 && divisor == -1) {
            throw new ArithmeticException("Int128 division overflow");
        }
        return divide(divisor);
    }

    public Int128 divideExact(Int128 divisor, Int128 remainder) {
        if (high == Long.MIN_VALUE && low == 0 && divisor.high == -1 && divisor.low == -1) {
            throw new ArithmeticException("Int128 division overflow");
        }
        return divide(divisor, remainder);
    }

    // =================== 位运算 ===================

    public Int128 shiftLeft(int n) {
        n &= 127; // 限制在0-127范围

        if (n >= 64) {
            this.high = this.low << (n - 64);
            this.low = 0;
        } else if (n > 0) {
            this.high = (this.high << n) | (this.low >>> (64 - n));
            this.low = this.low << n;
        }

        return this;
    }

    public Int128 shiftRight(int n) {
        n &= 127;

        if (n >= 64) {
            this.low = this.high >> (n - 64);
            this.high = this.high >> 63; // 符号扩展
        } else if (n > 0) {
            this.low = (this.low >>> n) | (this.high << (64 - n));
            this.high = this.high >> n;
        }

        return this;
    }

    public Int128 shiftRightUnsigned(int n) {
        n &= 127;

        if (n >= 64) {
            this.low = this.high >>> (n - 64);
            this.high = 0;
        } else if (n > 0) {
            this.low = (this.low >>> n) | (this.high << (64 - n));
            this.high = this.high >>> n;
        }

        return this;
    }

    public Int128 negate() {
        this.low = ~this.low;
        this.high = ~this.high;

        // 加1
        this.low++;
        if (this.low == 0) {
            this.high++;
        }

        return this;
    }

    // =================== 比较运算 ===================

    @Override
    public int compareTo(Int128 other) {
        boolean thisNeg = this.isNegative();
        boolean otherNeg = other.isNegative();

        if (thisNeg != otherNeg) {
            return thisNeg ? -1 : 1;
        }

        if (this.high != other.high) {
            return Long.compare(this.high, other.high);
        }

        return Long.compareUnsigned(this.low, other.low);
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Int128 other)) {
            return false;
        }
        return this.high == other.high && this.low == other.low;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(high) * 31 + Long.hashCode(low);
    }

    // =================== 状态与位访问 ===================

    public boolean isZero() {
        return high == 0 && low == 0;
    }

    public boolean isNegative() {
        return high < 0;
    }

    public boolean isPositive() {
        return !isNegative() && !isZero();
    }

    public boolean getBit(int index) {
        if (index < 64) {
            return (low & (1L << index)) != 0;
        } else {
            return (high & (1L << (index - 64))) != 0;
        }
    }

    public void setBit(int index, boolean value) {
        if (index < 64) {
            if (value) {
                low |= (1L << index);
            } else {
                low &= ~(1L << index);
            }
        } else {
            if (value) {
                high |= (1L << (index - 64));
            } else {
                high &= ~(1L << (index - 64));
            }
        }
    }

    // =================== 数值转换辅助方法 ===================

    /** 保留原有的低64位取值行为，需要检查数值范围时使用longValueExact */
    public long toLong() {
        return low;
    }

    public double toDouble() {
        return toFloatingPoint(53);
    }

    /** 对无符号幅值只舍入一次，避免负数高低位抵消和float二次舍入 */
    private double toFloatingPoint(int precision) {
        if (isZero()) {
            return 0;
        }

        boolean negative = high < 0;
        long magnitudeHigh = high;
        long magnitudeLow = low;
        if (negative) {
            magnitudeLow = -magnitudeLow;
            magnitudeHigh = ~magnitudeHigh + (magnitudeLow == 0 ? 1 : 0);
        }

        int bits = magnitudeHigh != 0 ?
                128 - Long.numberOfLeadingZeros(magnitudeHigh) :
                64 - Long.numberOfLeadingZeros(magnitudeLow);
        if (bits <= precision) {
            return negative ? -(double) magnitudeLow : (double) magnitudeLow;
        }

        int shift = bits - precision;
        long significand = getSignificand(shift, magnitudeLow, magnitudeHigh);
        double value = Math.scalb((double) significand, shift);
        return negative ? -value : value;
    }

    private static long getSignificand(int shift, long magnitudeLow, long magnitudeHigh) {
        long significand;
        boolean roundBit;
        boolean sticky;
        if (shift < 64) {
            significand = (magnitudeLow >>> shift) | (magnitudeHigh << (64 - shift));
            roundBit = (magnitudeLow & (1L << (shift - 1))) != 0;
            sticky = (magnitudeLow & ((1L << (shift - 1)) - 1)) != 0;
        } else if (shift == 64) {
            significand = magnitudeHigh;
            roundBit = magnitudeLow < 0;
            sticky = (magnitudeLow & Long.MAX_VALUE) != 0;
        } else {
            int highShift = shift - 64;
            significand = magnitudeHigh >>> highShift;
            roundBit = (magnitudeHigh & (1L << (highShift - 1))) != 0;
            sticky = magnitudeLow != 0 || (magnitudeHigh & ((1L << (highShift - 1)) - 1)) != 0;
        }
        if (roundBit && (sticky || (significand & 1) != 0)) {
            significand++;
        }
        return significand;
    }

    // =================== BigInteger 转换 ===================

    public BigInteger toBigInteger() {
        if (isZero()) {
            return BigInteger.ZERO;
        }

        byte[] bytes = new byte[16];

        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (high >>> (56 - i * 8));
        }

        for (int i = 0; i < 8; i++) {
            bytes[i + 8] = (byte) (low >>> (56 - i * 8));
        }

        return new BigInteger(bytes);
    }

    public static Int128 fromBigInteger(BigInteger value) {
        if (value == null) {
            return ZERO();
        }

        if (value.bitLength() > 127) {
            throw new ArithmeticException("BigInteger too large for Int128: " + value);
        }

        if (value.equals(BigInteger.ZERO)) {
            return new Int128(0, 0);
        }
        if (value.equals(BigInteger.ONE)) {
            return new Int128(0, 1);
        }

        byte[] bytes = value.toByteArray();

        long high = 0, low = 0;

        int len = bytes.length;

        for (int i = 0; i < Math.min(8, len); i++) {
            int byteIndex = len - 1 - i;
            if (byteIndex >= 0) {
                low |= ((long) (bytes[byteIndex] & 0xFF)) << (i * 8);
            }
        }

        for (int i = 8; i < Math.min(16, len); i++) {
            int byteIndex = len - 1 - i;
            if (byteIndex >= 0) {
                high |= ((long) (bytes[byteIndex] & 0xFF)) << ((i - 8) * 8);
            }
        }

        if (value.signum() < 0 && len < 16) {
            if (len <= 8) {
                if (len < 8) {
                    low |= (-1L << (len * 8));
                }
                high = -1L;
            } else {
                high |= (-1L << ((len - 8) * 8));
            }
        }

        return new Int128(high, low);
    }

    // =================== 十进制字符串转换 ===================

    @Override
    public String toString() {
        if (isZero()) {
            return "0";
        }

        if ((high == 0 && low >= 0) || (high == -1 && low < 0)) {
            return Long.toString(low);
        }

        return toStringFast();
    }

    private String toStringFast() {
        boolean negative = isNegative();
        Int128 working = copy();
        if (negative) {
            working.negate();
        }

        char[] digits = new char[40];
        int pos = digits.length;

        while (!working.isZero()) {
            long remainder = divideUnsignedByLong(working.high, working.low, 1_000_000_000L, working);
            int chunkDigits = working.isZero() ? 1 : 9;
            do {
                digits[--pos] = (char) ('0' + remainder % 10);
                remainder /= 10;
            } while (--chunkDigits > 0 || remainder != 0);
        }

        if (negative) {
            digits[--pos] = '-';
        }

        return new String(digits, pos, digits.length - pos);
    }

    public static Int128 fromString(String str) {
        str = Objects.requireNonNull(str, "str").trim();
        if (str.isEmpty()) {
            throw new NumberFormatException("empty string");
        }

        return fromDecimalString(str);
    }

    public static Int128 fromString(String str, Int128 defaultValue) {
        try {
            return fromString(str);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static Int128 fromDecimalString(String str) {
        boolean negative = false;
        int start = 0;

        if (str.charAt(0) == '-') {
            negative = true;
            start = 1;
        } else if (str.charAt(0) == '+') {
            start = 1;
        }

        if (start >= str.length()) {
            throw new NumberFormatException("no digits");
        }

        for (int i = start; i < str.length(); i++) {
            if (str.charAt(i) < '0' || str.charAt(i) > '9') {
                throw new NumberFormatException("invalid digit: " + str.charAt(i));
            }
        }
        if (str.length() - start <= 18) {
            long value = Long.parseLong(str.substring(start));
            return new Int128(negative ? -value : value);
        }

        Int128 limitQuotient = new Int128();
        long limitRemainder = divideUnsignedByLong(negative ? Long.MIN_VALUE : Long.MAX_VALUE,
                negative ? 0 : -1L, 10, limitQuotient);
        long parsedHigh = 0;
        long parsedLow = 0;

        for (int i = start; i < str.length(); i++) {
            char c = str.charAt(i);
            int digit = c - '0';
            int comparison = compareUnsigned(parsedHigh, parsedLow, limitQuotient.high, limitQuotient.low);
            if (comparison > 0 || (comparison == 0 && digit > limitRemainder)) {
                throw new NumberFormatException("Decimal value outside signed Int128 range");
            }
            long multipliedLow = parsedLow * 10;
            parsedHigh = parsedHigh * 10 + unsignedMultiplyHigh(parsedLow, 10);
            parsedLow = multipliedLow + digit;
            if (Long.compareUnsigned(parsedLow, multipliedLow) < 0) {
                parsedHigh++;
            }
        }
        Int128 result = new Int128(parsedHigh, parsedLow);

        return negative ? result.negate() : result;
    }

    // =================== 字符串格式化 ===================

    public String toHexString() {
        return String.format("%016X%016X", high, low);
    }

    /**
     * 格式化数字的toString版本，使用千分位分隔符
     * 
     * @param separator 分隔符，通常为 "," 或 " "
     * @return 格式化后的字符串
     */
    public String toFormattedString(String separator) {
        if (separator == null) {
            separator = ",";
        }

        String baseStr = this.toString();
        if (baseStr.length() <= 3) {
            return baseStr;
        }

        boolean negative = baseStr.startsWith("-");
        String digits = negative ? baseStr.substring(1) : baseStr;

        StringBuilder formatted = new StringBuilder();
        int len = digits.length();

        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                formatted.append(separator);
            }
            formatted.append(digits.charAt(i));
        }

        if (negative) {
            formatted.insert(0, "-");
        }

        return formatted.toString();
    }

    public String toFormattedString() {
        return toFormattedString(",");
    }

    /**
     * 紧凑格式，使用科学计数法显示大数字
     * 
     * @return 紧凑格式的字符串，如 "1.23E+15"
     */
    public String toCompactString() {
        if (isZero()) {
            return "0";
        }

        String str = this.toString();
        boolean negative = str.startsWith("-");
        String digits = negative ? str.substring(1) : str;

        if (digits.length() <= 6) {
            return str;
        }

        char firstDigit = digits.charAt(0);

        String mantissa = String.valueOf(firstDigit) + '.' + digits.substring(1, 4);

        int exponent = digits.length() - 1;
        String result = mantissa + "E+" + exponent;

        return negative ? "-" + result : result;
    }

    /**
     * 人类可读的格式，使用单位后缀
     * 
     * @return 人类可读的字符串，如 "1.23K", "4.56M", "7.89B"
     */
    public String toHumanReadableString() {
        if (isZero()) {
            return "0";
        }

        String[] units = { "", "K", "M", "B", "T", "P", "E", "Z", "Y" };

        String str = this.toString();
        boolean negative = str.startsWith("-");
        String digits = negative ? str.substring(1) : str;

        if (digits.length() <= 3) {
            return str;
        }

        int unitIndex = (digits.length() - 1) / 3;
        if (unitIndex >= units.length) {
            return toCompactString();
        }

        int significantDigits = digits.length() - (unitIndex * 3);
        String integerPart = digits.substring(0, significantDigits);

        StringBuilder result = new StringBuilder();
        if (negative) {
            result.append("-");
        }

        result.append(integerPart);

        int remainingDigits = digits.length() - significantDigits;
        if (remainingDigits > 0 && integerPart.length() < 3) {
            result.append(".");
            int decimalPlaces = Math.min(2, Math.min(remainingDigits, 3 - integerPart.length()));
            result.append(digits, significantDigits, significantDigits + decimalPlaces);
        }

        result.append(units[unitIndex]);

        return result.toString();
    }
}
