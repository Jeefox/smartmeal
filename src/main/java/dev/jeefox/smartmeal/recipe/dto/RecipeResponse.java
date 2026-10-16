package dev.jeefox.smartmeal.recipe.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecipeResponse(
        UUID id,
        String name,
        String description,
        List<RecipeIngredientResponse> ingredients,
        String instructions,
        Integer preparationTimeMin,
        Integer servings,
        Instant createdAt,
        Instant updatedAt
) {
}