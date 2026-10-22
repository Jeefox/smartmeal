package dev.jeefox.smartmeal.shopping.calculation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class WeightPrecisionTest {
    @Test
    void preservesRealPackageOverflow() {
        assertEquals(2, WeightPrecision.packages(new BigDecimal("900.001"), new BigDecimal("900")));
        assertEquals(
                0,
                new BigDecimal("0.001")
                        .compareTo(
                                WeightPrecision.shortage(
                                        new BigDecimal("900.001"), new BigDecimal("900"))));
    }

    @Test
    void suppressesOnlyDecimalArithmeticNoiseAtTheBoundary() {
        assertEquals(
                1,
                WeightPrecision.packages(
                        new BigDecimal("2.000000000000000000000000000001"), new BigDecimal("2")));
        assertEquals(
                0,
                WeightPrecision.shortage(
                                new BigDecimal("2.000000000000000000000000000001"),
                                new BigDecimal("2"))
                        .signum());
    }

    @Test
    void smallestRealFractionStillRequiresAPurchaseWhenNoStockExists() {
        var quantity = new BigDecimal("0.00000000000005");
        assertEquals(1, WeightPrecision.packages(quantity, new BigDecimal("900")));
        assertEquals(quantity, WeightPrecision.shortage(quantity, BigDecimal.ZERO));
    }
}
