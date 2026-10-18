package dev.jeefox.smartmeal.nutrition;

import dev.jeefox.smartmeal.user.entity.ActivityLevel;
import dev.jeefox.smartmeal.user.entity.Sex;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class DailyEnergyCalculator {
    private final RestingEnergyCalculator restingEnergyCalculator;

    private static final BigDecimal SEDENTARY_FACTOR = BigDecimal.valueOf(1.4);
    private static final BigDecimal LOW_FACTOR = BigDecimal.valueOf(1.6);
    private static final BigDecimal MODERATE_FACTOR = BigDecimal.valueOf(1.8);
    private static final BigDecimal HIGH_FACTOR = BigDecimal.valueOf(2.0);
    private static final BigDecimal VERY_HIGH_FACTOR = BigDecimal.valueOf(2.2);

    public DailyEnergyCalculator(RestingEnergyCalculator restingEnergyCalculator) {
        this.restingEnergyCalculator =
                Objects.requireNonNull(restingEnergyCalculator);
    }

    public BigDecimal calculate(
            BigDecimal weightKg,
            Integer heightCm,
            Short age,
            Sex sex,
            ActivityLevel activityLevel
    ){
        Objects.requireNonNull(weightKg, "Вес не должен быть null");
        Objects.requireNonNull(heightCm, "Рост не должен быть null");
        Objects.requireNonNull(age, "Возраст не должен быть null");
        Objects.requireNonNull(sex, "Пол не может быть null");
        Objects.requireNonNull(activityLevel, "Уровень активности не может быть null");

        BigDecimal restingResult = restingEnergyCalculator.calculate(weightKg, heightCm, age, sex);
        BigDecimal coefficient = switch (activityLevel) {
            case SEDENTARY -> SEDENTARY_FACTOR;
            case LOW -> LOW_FACTOR;
            case MODERATE -> MODERATE_FACTOR;
            case HIGH -> HIGH_FACTOR;
            case VERY_HIGH -> VERY_HIGH_FACTOR;
        };
        return restingResult.multiply(coefficient);
    }
}
