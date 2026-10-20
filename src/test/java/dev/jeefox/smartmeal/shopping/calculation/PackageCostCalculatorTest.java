package dev.jeefox.smartmeal.shopping.calculation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PackageCostCalculatorTest {
    private final PackageCostCalculator calculator = new PackageCostCalculator();

    @ParameterizedTest
    @CsvSource({
            "1000, 800, 90.00, 2, 180.00, 600",
            "800, 800, 90.00, 1, 90.00, 0",
            "1600, 800, 90.00, 2, 180.00, 0",
            "100, 800, 90.00, 1, 90.00, 700",
            "1600.01, 800, 89.90, 3, 269.70, 799"
    })
    void buysWholePackagesAndCalculatesCost(String required, int packageWeight, String price,
                                            int count, String cost, String remainder) {
        PackageCostResult result = calculator.calculate(new BigDecimal(required), packageWeight, new BigDecimal(price));

        assertEquals(count, result.packageCount());
        assertEquals(0, new BigDecimal(cost).compareTo(result.purchaseCost()));
        assertEquals(0, new BigDecimal(remainder).compareTo(result.remainderG()));
    }

    @ParameterizedTest
    @CsvSource({"1000.25, 599", "799.50, 0"})
    void roundsFractionalRemainderDownAsCurrentlySpecified(String required, String remainder) {
        PackageCostResult result = calculator.calculate(new BigDecimal(required), 800, new BigDecimal("90"));

        assertEquals(0, new BigDecimal(remainder).compareTo(result.remainderG()));
        assertEquals(0, result.remainderG().scale());
    }

    @Test
    void rejectsMissingRequiredWeight() {
        assertThrows(NullPointerException.class, () -> calculator.calculate(null, 800, BigDecimal.ONE));
    }

    @Test
    void rejectsMissingPackageWeight() {
        assertThrows(NullPointerException.class, () -> calculator.calculate(BigDecimal.ONE, null, BigDecimal.ONE));
    }

    @Test
    void rejectsMissingPrice() {
        assertThrows(NullPointerException.class, () -> calculator.calculate(BigDecimal.ONE, 800, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void rejectsNonPositiveRequiredWeight(String required) {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal(required), 800, BigDecimal.ONE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositivePackageWeight(int weight) {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(BigDecimal.ONE, weight, BigDecimal.ONE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void rejectsNonPositivePrice(String price) {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(BigDecimal.ONE, 800, new BigDecimal(price)));
    }

    @Test
    void acceptsMaximumIntegerPackageCount() {
        PackageCostResult result = calculator.calculate(
                new BigDecimal("2147483647"), 1, new BigDecimal("0.01"));

        assertEquals(Integer.MAX_VALUE, result.packageCount());
        assertEquals(0, new BigDecimal("21474836.47").compareTo(result.purchaseCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.remainderG()));
    }

    @Test
    void rejectsPackageCountOverflowInsteadOfTruncatingIt() {
        assertThrows(ArithmeticException.class,
                () -> calculator.calculate(new BigDecimal("2147483648"), 1, BigDecimal.ONE));
    }

    @Test
    void calculatesPurchasedMassWithoutIntegerMultiplicationOverflow() {
        PackageCostResult result = calculator.calculate(
                new BigDecimal("4294967294"), Integer.MAX_VALUE, new BigDecimal("90"));

        assertEquals(2, result.packageCount());
        assertEquals(0, new BigDecimal("180").compareTo(result.purchaseCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.remainderG()));
    }
}
