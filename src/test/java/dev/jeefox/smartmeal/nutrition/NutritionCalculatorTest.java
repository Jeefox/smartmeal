package dev.jeefox.smartmeal.nutrition;

import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NutritionCalculatorTest {

    @Test
    void calculatesAllNutrientsForOneIngredient() {
        Product product = product(350, "12", "2", "60");

        NutritionValues actual = NutritionCalculator.calculate(
                List.of(ingredient(product, "150")));

        assertEquals(values("525.00", "18.00", "3.00", "90.00"), actual);
    }

    @Test
    void sumsDifferentProductsAndQuantities() {
        List<RecipeIngredient> ingredients = List.of(
                ingredient(product(350, "12", "2", "60"), "150"),
                ingredient(product(100, "4", "3", "10"), "25"));

        assertEquals(values("550.00", "19.00", "3.75", "92.50"),
                NutritionCalculator.calculate(ingredients));
    }

    @Test
    void roundsOnlyAfterSummingIngredientContributions() {
        Product product = product(1, "1", "1", "1");
        List<RecipeIngredient> ingredients = List.of(
                ingredient(product, "0.40"), ingredient(product, "0.40"));

        assertEquals(values("0.01", "0.01", "0.01", "0.01"),
                NutritionCalculator.calculate(ingredients));
    }

    @Test
    void roundsHalfUpAtTwoDecimalPlaces() {
        Product product = product(1, "1", "1", "1");

        assertEquals(values("0.01", "0.01", "0.01", "0.01"),
                NutritionCalculator.calculate(List.of(ingredient(product, "0.50"))));
    }

    @Test
    void returnsScaledZerosForEmptyList() {
        assertEquals(values("0.00", "0.00", "0.00", "0.00"),
                NutritionCalculator.calculate(List.of()));
    }

    @Test
    void acceptsProductsWithZeroNutrients() {
        assertEquals(values("0.00", "0.00", "0.00", "0.00"),
                NutritionCalculator.calculate(
                        List.of(ingredient(product(0, "0", "0", "0"), "123.45"))));
    }

    @Test
    void rejectsNullListAndNullIngredient() {
        assertThrows(NullPointerException.class, () -> NutritionCalculator.calculate(null));
        assertThrows(NullPointerException.class,
                () -> NutritionCalculator.calculate(Collections.singletonList(null)));
    }

    @Test
    void rejectsMissingProductAndQuantity() {
        assertThrows(NullPointerException.class,
                () -> NutritionCalculator.calculate(List.of(ingredient(null, "100"))));
        assertThrows(NullPointerException.class,
                () -> NutritionCalculator.calculate(
                        List.of(ingredient(product(100, "1", "1", "1"), null))));
    }

    @Test
    void rejectsNonPositiveQuantity() {
        Product product = product(100, "1", "1", "1");
        for (String weight : List.of("0", "-0.01")) {
            assertThrows(IllegalArgumentException.class,
                    () -> NutritionCalculator.calculate(List.of(ingredient(product, weight))));
        }
    }

    @Test
    void rejectsMissingNutrientInsteadOfTreatingItAsZero() {
        List<Product> invalidProducts = List.of(
                product(null, "1", "1", "1"),
                product(100, null, "1", "1"),
                product(100, "1", null, "1"),
                product(100, "1", "1", null));
        for (Product product : invalidProducts) {
            assertThrows(NullPointerException.class,
                    () -> NutritionCalculator.calculate(List.of(ingredient(product, "100"))));
        }
    }

    @Test
    void calculatesNutrientsPerServing() {
        List<RecipeIngredient> ingredients = List.of(
                ingredient(product(350, "12", "2", "60"), "150"));

        assertEquals(values("175.00", "6.00", "1.00", "30.00"),
                NutritionCalculator.calculatePortion(ingredients, 3));
    }

    @Test
    void roundsRepeatingFractionsPerServing() {
        List<RecipeIngredient> ingredients = List.of(
                ingredient(product(100, "1", "2", "4"), "100"));

        assertEquals(values("33.33", "0.33", "0.67", "1.33"),
                NutritionCalculator.calculatePortion(ingredients, 3));
    }

    @Test
    void dividesUnroundedTotalsBeforeRoundingPortion() {
        // 0.026 / 2 -> 0.01; rounding the total first gives 0.03 / 2 -> 0.02.
        List<RecipeIngredient> ingredients = List.of(
                ingredient(product(1, "1", "1", "1"), "2.60"));
        assertEquals(values("0.01", "0.01", "0.01", "0.01"),
                NutritionCalculator.calculatePortion(ingredients, 2));
    }

    @Test
    void oneServingMatchesWholeRecipe() {
        List<RecipeIngredient> ingredients = List.of(
                ingredient(product(350, "12", "2", "60"), "150"));

        assertEquals(NutritionCalculator.calculate(ingredients),
                NutritionCalculator.calculatePortion(ingredients, 1));
    }

    @Test
    void returnsScaledZerosForEmptyPortion() {
        assertEquals(values("0.00", "0.00", "0.00", "0.00"),
                NutritionCalculator.calculatePortion(List.of(), 3));
    }

    @Test
    void rejectsMissingOrNonPositiveServingCount() {
        List<RecipeIngredient> ingredients = List.of(
                ingredient(product(100, "1", "1", "1"), "100"));

        assertThrows(IllegalArgumentException.class,
                () -> NutritionCalculator.calculatePortion(ingredients, null));
        assertThrows(IllegalArgumentException.class,
                () -> NutritionCalculator.calculatePortion(ingredients, 0));
        assertThrows(IllegalArgumentException.class,
                () -> NutritionCalculator.calculatePortion(ingredients, -1));
    }

    private static Product product(Integer calories, String protein, String fat, String carbs) {
        return new Product(new ProductCategory("TEST", "Тестовая категория"),
                "Тестовый продукт", null, calories, decimal(protein), decimal(fat), decimal(carbs));
    }

    private static RecipeIngredient ingredient(Product product, String quantity) {
        Recipe recipe = new Recipe("Тестовый рецепт", null, "Инструкции", 0, 1);
        return new RecipeIngredient(recipe, product, decimal(quantity), 1);
    }

    private static NutritionValues values(String calories, String protein, String fat, String carbs) {
        return new NutritionValues(decimal(calories), decimal(protein), decimal(fat), decimal(carbs));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
