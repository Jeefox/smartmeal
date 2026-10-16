package dev.jeefox.smartmeal.recipe.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RecipeIngredientResponse(
        UUID id,
        Long productId,
        String productName,
        BigDecimal quantityG,
        Integer position
) {
}
