package dev.jeefox.smartmeal.recipe.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Set;

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
        List<@NotNull @Valid RecipeIngredientRequest> ingredients,

        Set<@NotNull MealType> mealTypes
) {
    public CreateRecipeRequest {
        mealTypes = mealTypes == null ? Set.of() : mealTypes;
    }
}
