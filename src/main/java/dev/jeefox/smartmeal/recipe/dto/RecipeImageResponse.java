package dev.jeefox.smartmeal.recipe.dto;

import java.util.UUID;

public record RecipeImageResponse(
        UUID id,
        String url,
        String altText,
        Integer width,
        Integer height
) {
}
