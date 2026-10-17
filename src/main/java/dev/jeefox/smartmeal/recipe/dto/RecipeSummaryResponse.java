package dev.jeefox.smartmeal.recipe.dto;

import java.util.UUID;

public record RecipeSummaryResponse(
        UUID id,

        String name,

        String description,

        Integer preparationTimeMin,

        Integer servings
) {
}
