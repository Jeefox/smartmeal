package dev.jeefox.smartmeal.product.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateProductRequest (
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
        BigDecimal carbsPer100g
)
{}
