package dev.jeefox.smartmeal.mealplan.snapshot;

import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import java.util.List;
import java.util.UUID;

public record RecipeSnapshot (
        UUID recipeId,
        String name,
        String description,
        String instructions,
        Integer preparationTimeMin,
        Integer servings,
        List<RecipeIngredientSnapshot> ingredients,
        NutritionValues nutritionTotal,
        NutritionValues nutritionPerServing
){
    public RecipeSnapshot {
        ingredients = List.copyOf(ingredients);
    }
}
