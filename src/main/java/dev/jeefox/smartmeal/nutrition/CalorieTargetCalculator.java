package dev.jeefox.smartmeal.nutrition;

import dev.jeefox.smartmeal.user.entity.Goal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class CalorieTargetCalculator {

    private static final BigDecimal LOSE_WEIGHT_FACTOR =
            BigDecimal.valueOf(0.9);
    private static final BigDecimal MAINTAIN_WEIGHT_FACTOR =
            BigDecimal.ONE;
    private static final BigDecimal GAIN_WEIGHT_FACTOR =
            BigDecimal.valueOf(1.1);

    public BigDecimal calculate(BigDecimal maintenanceCalories,
                                Goal goal){
        Objects.requireNonNull(maintenanceCalories, "Норма калорий не должна быть null");
        Objects.requireNonNull(goal, "Цель не должна быть null");

        if(maintenanceCalories.signum()<=0){
            throw new IllegalArgumentException("Количество калорий для поддержания веса должно быть больше 0");
        }

        BigDecimal coefficient = switch (goal){
            case LOSE_WEIGHT -> LOSE_WEIGHT_FACTOR;
            case MAINTAIN_WEIGHT -> MAINTAIN_WEIGHT_FACTOR;
            case GAIN_WEIGHT -> GAIN_WEIGHT_FACTOR;
        };
        return maintenanceCalories.multiply(coefficient);
    }
}
