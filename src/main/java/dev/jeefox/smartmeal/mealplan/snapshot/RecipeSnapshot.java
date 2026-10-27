package dev.jeefox.smartmeal.mealplan.snapshot;

import dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.Component;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import java.util.*;

public record RecipeSnapshot(
        UUID recipeId,
        String name,
        String description,
        String instructions,
        Integer preparationTimeMin,
        Integer servings,
        List<RecipeIngredientSnapshot> ingredients,
        NutritionValues nutritionTotal,
        NutritionValues nutritionPerServing,
        List<Component> components) {
    public RecipeSnapshot {
        ingredients = List.copyOf(ingredients);
        components = components == null ? List.of() : List.copyOf(components);
    }

    public RecipeSnapshot(
            UUID recipeId,
            String name,
            String description,
            String instructions,
            Integer preparationTimeMin,
            Integer servings,
            List<RecipeIngredientSnapshot> ingredients,
            NutritionValues nutritionTotal,
            NutritionValues nutritionPerServing) {
        this(
                recipeId,
                name,
                description,
                instructions,
                preparationTimeMin,
                servings,
                ingredients,
                nutritionTotal,
                nutritionPerServing,
                List.of());
    }
}
