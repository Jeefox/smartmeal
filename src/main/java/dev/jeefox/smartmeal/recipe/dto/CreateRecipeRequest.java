package dev.jeefox.smartmeal.recipe.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record CreateRecipeRequest(
        @NotBlank
        String name,

        String description,

        @NotBlank
        String instructions,

        @PositiveOrZero
        @NotNull
        Integer preparationTimeMin,

        @Positive
        @NotNull
        Integer servings,

        @NotEmpty
        List<@NotNull @Valid RecipeIngredientRequest> ingredients
) {
}
