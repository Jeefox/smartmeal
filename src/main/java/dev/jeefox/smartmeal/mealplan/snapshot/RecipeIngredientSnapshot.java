package dev.jeefox.smartmeal.mealplan.snapshot;

import java.math.BigDecimal;

public record RecipeIngredientSnapshot(
        Long productId,
        String productName,
        BigDecimal quantityG
) {
}
