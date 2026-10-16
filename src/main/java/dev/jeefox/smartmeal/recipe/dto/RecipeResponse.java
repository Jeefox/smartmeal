package dev.jeefox.smartmeal.recipe.dto;

import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

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
        NutritionValues nutritionTotal,
        NutritionValues nutritionPerServing,
        Instant createdAt,
        Instant updatedAt
) {
}