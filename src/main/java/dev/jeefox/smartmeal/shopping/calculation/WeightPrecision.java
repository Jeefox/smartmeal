package dev.jeefox.smartmeal.shopping.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Tolerance for DECIMAL128 arithmetic, far below the smallest weight allowed by recipe inputs. */
public final class WeightPrecision {
    private WeightPrecision() {}

    public static boolean isArithmeticNoise(BigDecimal value, BigDecimal basis) {
        var tolerance = basis.abs().multiply(new BigDecimal("1E-30")).max(new BigDecimal("1E-24"));
        return value.abs().compareTo(tolerance) <= 0;
    }

    public static BigDecimal shortage(BigDecimal required, BigDecimal available) {
        var result = required.subtract(available).max(BigDecimal.ZERO);
        return isArithmeticNoise(result, required) ? BigDecimal.ZERO : result;
    }

    public static int packages(BigDecimal required, BigDecimal packageWeight) {
        var whole = required.divide(packageWeight, 0, RoundingMode.FLOOR);
        var remainder = required.subtract(whole.multiply(packageWeight));
        if (remainder.signum() == 0
                || (whole.signum() > 0 && isArithmeticNoise(remainder, required)))
            return whole.intValueExact();
        return whole.add(BigDecimal.ONE).intValueExact();
    }
}
