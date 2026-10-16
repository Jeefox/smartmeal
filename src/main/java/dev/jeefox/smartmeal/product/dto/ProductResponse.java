package dev.jeefox.smartmeal.product.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        Long categoryId,
        String name,
        String description,
        Integer caloriesPer100g,
        BigDecimal proteinPer100g,
        BigDecimal fatPer100g,
        BigDecimal carbsPer100g,
        Instant createdAt,
        Instant updatedAt
) {
}
