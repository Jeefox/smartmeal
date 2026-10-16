package dev.jeefox.smartmeal.nutrition.dto;

import java.math.BigDecimal;

public record NutritionValues(
        BigDecimal calories,
        BigDecimal protein,
        BigDecimal fat,
        BigDecimal carbs
) {
}
