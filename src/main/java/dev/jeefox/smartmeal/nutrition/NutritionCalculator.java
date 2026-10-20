package dev.jeefox.smartmeal.nutrition;


import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

public class NutritionCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static NutritionValues calculate(List<RecipeIngredient> ingredients) {

        NutritionValues result = calculateTotals(ingredients);

        return new NutritionValues(
                result.calories().setScale(2, RoundingMode.HALF_UP),
                result.protein().setScale(2, RoundingMode.HALF_UP),
                result.fat().setScale(2, RoundingMode.HALF_UP),
                result.carbs().setScale(2, RoundingMode.HALF_UP)
        );
    }

    // Exact totals are also stored in recipe snapshots to avoid rounding before portion scaling.
    public static NutritionValues calculateTotals(List<RecipeIngredient> ingredients) {
               Objects.requireNonNull(ingredients, "Список ингредиентов не может быть null");

        if (ingredients.isEmpty()) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            return new NutritionValues(zero, zero, zero, zero);
        }

        BigDecimal totalCalories = BigDecimal.ZERO;
        BigDecimal totalProtein = BigDecimal.ZERO;
        BigDecimal totalFat = BigDecimal.ZERO;
        BigDecimal totalCarbs = BigDecimal.ZERO;

        for (RecipeIngredient ingredient : ingredients) {

            Objects.requireNonNull(ingredient, "Ингредиент не может быть null");
            Product product = Objects.requireNonNull(ingredient.getProduct(), "Продукт ингредиента не может быть null");

            Objects.requireNonNull(product.getProteinPer100g(), "Белки продукта не могут быть null");
            Objects.requireNonNull(product.getFatPer100g(), "Жиры продукта не могут быть null");
            Objects.requireNonNull(product.getCarbsPer100g(), "Углеводы продукта не могут быть null");
            Objects.requireNonNull(product.getCaloriesPer100g(), "Калории продукта не могут быть null");

            BigDecimal weight = Objects.requireNonNull(
                    ingredient.getQuantityG(),
                    "Вес ингредиента не может быть null"
            );

            if (weight.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Вес ингредиента должен быть строго больше нуля: " + weight
                );
            }

            BigDecimal weightCoefficient = weight.divide(HUNDRED);

            totalProtein = totalProtein.add(product.getProteinPer100g().multiply(weightCoefficient));
            totalFat = totalFat.add(product.getFatPer100g().multiply(weightCoefficient));
            totalCarbs = totalCarbs.add(product.getCarbsPer100g().multiply(weightCoefficient));
            totalCalories = totalCalories.add(BigDecimal.valueOf(product.getCaloriesPer100g()).multiply(weightCoefficient));
        }

        return new NutritionValues(
                totalCalories,
                totalProtein,
                totalFat,
                totalCarbs
        );
    }

    public static NutritionValues calculatePortion(List<RecipeIngredient>ingredients, Integer servings){

        if (servings == null || servings <= 0) {
            throw new IllegalArgumentException("Количество порций должно быть больше нуля");
        }

        NutritionValues result = calculateTotals(ingredients);

        BigDecimal servingsBd = BigDecimal.valueOf(servings);

        return new NutritionValues(
                result.calories().divide(servingsBd, 2, RoundingMode.HALF_UP),
                result.protein().divide(servingsBd, 2, RoundingMode.HALF_UP),
                result.fat().divide(servingsBd, 2, RoundingMode.HALF_UP),
                result.carbs().divide(servingsBd, 2, RoundingMode.HALF_UP)
        );
    }
}
