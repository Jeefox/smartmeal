package dev.jeefox.smartmeal.nutrition;

import dev.jeefox.smartmeal.user.entity.Goal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalorieTargetCalculatorTest {

    private final CalorieTargetCalculator calculator = new CalorieTargetCalculator();

    @ParameterizedTest
    @CsvSource({
            "LOSE_WEIGHT, 2250",
            "MAINTAIN_WEIGHT, 2500",
            "GAIN_WEIGHT, 2750"
    })
    void calculatesTargetForEachGoal(Goal goal, String expected) {
        BigDecimal actual = calculator.calculate(new BigDecimal("2500"), goal);

        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void ordersTargetsBelowAndAboveMaintenance() {
        BigDecimal maintenance = new BigDecimal("1800");

        assertTrue(calculator.calculate(maintenance, Goal.LOSE_WEIGHT).compareTo(maintenance) < 0);
        assertEquals(0, calculator.calculate(maintenance, Goal.MAINTAIN_WEIGHT).compareTo(maintenance));
        assertTrue(calculator.calculate(maintenance, Goal.GAIN_WEIGHT).compareTo(maintenance) > 0);
    }

    @ParameterizedTest
    @CsvSource({
            "LOSE_WEIGHT, 2250.009",
            "MAINTAIN_WEIGHT, 2500.01",
            "GAIN_WEIGHT, 2750.011"
    })
    void preservesFractionalCaloriesWithoutRounding(Goal goal, String expected) {
        BigDecimal actual = calculator.calculate(new BigDecimal("2500.01"), goal);

        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void rejectsMissingMaintenanceCalories() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculate(null, Goal.MAINTAIN_WEIGHT));
    }

    @Test
    void rejectsMissingGoal() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculate(new BigDecimal("2500"), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void rejectsNonPositiveMaintenanceCalories(String maintenance) {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal(maintenance), Goal.MAINTAIN_WEIGHT));
    }
}
