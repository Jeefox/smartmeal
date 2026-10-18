package dev.jeefox.smartmeal.nutrition;

import dev.jeefox.smartmeal.user.entity.Sex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestingEnergyCalculatorTest {

    private final RestingEnergyCalculator calculator = new RestingEnergyCalculator();

    @ParameterizedTest
    @CsvSource({
            "MALE, 1780",
            "FEMALE, 1614"
    })
    void calculatesRestingEnergyForBothSexes(Sex sex, String expected) {
        BigDecimal actual = calculator.calculate(new BigDecimal("80"), 180, (short) 30, sex);

        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @ParameterizedTest
    @CsvSource({
            "MALE, 1741.75",
            "FEMALE, 1575.75"
    })
    void preservesFractionalResultWithoutRounding(Sex sex, String expected) {
        BigDecimal actual = calculator.calculate(new BigDecimal("78.05"), 177, (short) 30, sex);

        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void acceptsAgeExactlyEighteen() {
        BigDecimal actual = calculator.calculate(new BigDecimal("80"), 180, (short) 18, Sex.MALE);

        assertEquals(0, new BigDecimal("1840").compareTo(actual));
    }

    @Test
    void rejectsMissingWeight() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculate(null, 180, (short) 30, Sex.MALE));
    }

    @Test
    void rejectsMissingHeight() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculate(new BigDecimal("80"), null, (short) 30, Sex.MALE));
    }

    @Test
    void rejectsMissingAge() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculate(new BigDecimal("80"), 180, null, Sex.MALE));
    }

    @Test
    void rejectsMissingSexInsteadOfUsingFemaleCorrection() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculate(new BigDecimal("80"), 180, (short) 30, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void rejectsNonPositiveWeightWithOtherArgumentsValid(String weight) {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal(weight), 180, (short) 30, Sex.MALE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -180})
    void rejectsNonPositiveHeightWithOtherArgumentsValid(int height) {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal("80"), height, (short) 30, Sex.MALE));
    }

    @ParameterizedTest
    @ValueSource(shorts = {17, 0, -1})
    void rejectsAgeBelowEighteenWithOtherArgumentsValid(short age) {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal("80"), 180, age, Sex.MALE));
    }
}
