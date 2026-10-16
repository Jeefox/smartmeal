package dev.jeefox.smartmeal.recipe.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record RecipeIngredientRequest(
        @NotNull
        @Positive
        Long productId,

        @NotNull
        @Positive
        @Digits(integer = 8, fraction = 2)
        BigDecimal quantityG
) {
}
