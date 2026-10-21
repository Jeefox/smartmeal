package dev.jeefox.smartmeal.recipe.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealType;

import java.util.List;
import java.util.UUID;

public record RecipeSummaryResponse(
        UUID id,

        String name,

        String description,

        Integer preparationTimeMin,

        Integer servings,
        List<MealType> mealTypes
) {
}
