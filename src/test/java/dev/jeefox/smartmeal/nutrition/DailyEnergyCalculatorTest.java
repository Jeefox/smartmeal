package dev.jeefox.smartmeal.nutrition;

import dev.jeefox.smartmeal.user.entity.ActivityLevel;
import dev.jeefox.smartmeal.user.entity.Goal;
import dev.jeefox.smartmeal.user.entity.Sex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DailyEnergyCalculatorTest {

    private final DailyEnergyCalculator calculator =
            new DailyEnergyCalculator(new RestingEnergyCalculator());

    @ParameterizedTest
    @CsvSource({
            "SEDENTARY, 2492",
            "LOW, 2848",
            "MODERATE, 3204",
            "HIGH, 3560",
            "VERY_HIGH, 3916"
    })
    void calculatesMaintenanceForEachActivityLevel(ActivityLevel activity, String expected) {
        BigDecimal actual = calculator.calculate(new BigDecimal("80"), 180, (short) 30, Sex.MALE, activity);

        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void preservesPrecisionThroughRestingMaintenanceAndTargetCalculations() {
        BigDecimal maintenance = calculator.calculate(
                new BigDecimal("78.05"), 177, (short) 30, Sex.FEMALE, ActivityLevel.MODERATE);
        BigDecimal target = new CalorieTargetCalculator().calculate(maintenance, Goal.LOSE_WEIGHT);

        assertEquals(0, new BigDecimal("2836.35").compareTo(maintenance));
        assertEquals(0, new BigDecimal("2552.715").compareTo(target));
    }

    @Test
    void rejectsMissingActivityLevel() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculate(new BigDecimal("80"), 180, (short) 30, Sex.MALE, null));
    }

    @Test
    void rejectsMissingRestingCalculator() {
        assertThrows(NullPointerException.class, () -> new DailyEnergyCalculator(null));
    }

    @Test
    void rejectsInvalidWeightBeforeReturningMaintenance() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal("-5"), 180, (short) 30,
                        Sex.MALE, ActivityLevel.MODERATE));
    }
}
