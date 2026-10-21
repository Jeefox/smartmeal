package dev.jeefox.smartmeal.mealplan.snapshot;

import dev.jeefox.smartmeal.nutrition.NutritionCalculator;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;

import java.util.Comparator;
import java.util.List;

public final class RecipeSnapshotFactory {
    private RecipeSnapshotFactory() {
    }

    public static RecipeSnapshot create(Recipe recipe, List<RecipeIngredient> ingredients) {
        List<RecipeIngredient> ordered = ingredients.stream()
                .sorted(Comparator.comparing(RecipeIngredient::getPosition)).toList();
        return new RecipeSnapshot(recipe.getId(), recipe.getName(), recipe.getDescription(), recipe.getInstructions(),
                recipe.getPreparationTimeMin(), recipe.getServings(), ordered.stream()
                .map(item -> new RecipeIngredientSnapshot(item.getProduct().getId(), item.getProduct().getName(),
                        item.getQuantityG(), item.getProduct().getShoppingName(), item.getProduct().getEdibleYield())).toList(),
                NutritionCalculator.calculateTotals(ordered), NutritionCalculator.calculatePortion(ordered, recipe.getServings()));
    }
}
