package dev.jeefox.smartmeal.product.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record UpdateProductRequest(
        @Positive
        @NotNull
        Long categoryId,
        @NotBlank
        String name,
        @Size(max = 300)
        String description,
        @NotNull
        @PositiveOrZero
        Integer caloriesPer100g,
        @NotNull
        @PositiveOrZero
        @Digits(integer = 5, fraction = 2)
        BigDecimal proteinPer100g,
        @NotNull
        @PositiveOrZero
        @Digits(integer = 5, fraction = 2)
        BigDecimal fatPer100g,
        @NotNull
        @PositiveOrZero
        @Digits(integer = 5, fraction = 2)
        BigDecimal carbsPer100g,
        @Pattern(regexp = "(?s).*\\S.*", message = "не должно быть пустым")
        String shoppingName,
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("1")
        @Digits(integer = 1, fraction = 6)
        BigDecimal edibleYield
) {
}
