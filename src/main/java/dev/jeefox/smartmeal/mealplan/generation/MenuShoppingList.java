package dev.jeefox.smartmeal.mealplan.generation;

import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshotFactory;
import dev.jeefox.smartmeal.pantry.*;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListItemResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * The legacy single-day preview uses the same raw demand and pantry allocation as period planning.
 */
public final class MenuShoppingList {
    private MenuShoppingList() {}

    public static ShoppingListResponse calculate(
            LocalDate date,
            List<DayMenuSelector.Choice> choices,
            Map<UUID, List<RecipeIngredient>> ingredients) {
        return calculate(date, choices, ingredients, PantryModels.Snapshot.empty());
    }

    public static ShoppingListResponse calculate(
            LocalDate date,
            List<DayMenuSelector.Choice> choices,
            Map<UUID, List<RecipeIngredient>> ingredients,
            PantryModels.Snapshot stock) {
        var demands = new ArrayList<IngredientDemandCalculator.Demand>();
        for (var choice : choices) {
            var content = ingredients.getOrDefault(choice.recipe().recipeId(), List.of());
            if (content.isEmpty()) continue;
            var snapshot = RecipeSnapshotFactory.create(content.getFirst().getRecipe(), content);
            demands.addAll(
                    IngredientDemandCalculator.calculate(
                            choice.mealType().name(),
                            date,
                            choice.mealType().ordinal(),
                            snapshot,
                            choice.portions()));
        }
        var allocation = PantryAllocationCalculator.allocate(demands, stock);
        Map<Long, BigDecimal> quantities = new TreeMap<>();
        Map<Long, String> names = new HashMap<>();
        for (var d : demands) {
            quantities.merge(d.productId(), d.quantityG(), BigDecimal::add);
            names.merge(d.productId(), d.name(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        var items =
                quantities.entrySet().stream()
                        .map(
                                e -> {
                                    var covered =
                                            allocation
                                                    .coveredG()
                                                    .getOrDefault(e.getKey(), BigDecimal.ZERO);
                                    return new ShoppingListItemResponse(
                                            e.getKey(),
                                            names.get(e.getKey()),
                                            dev.jeefox.smartmeal.shopping.calculation
                                                    .WeightPrecision.shortage(
                                                    e.getValue(), covered),
                                            e.getValue(),
                                            covered);
                                })
                        .sorted(
                                Comparator.comparing(ShoppingListItemResponse::productName)
                                        .thenComparing(ShoppingListItemResponse::productId))
                        .toList();
        return new ShoppingListResponse(date, date, false, items);
    }
}
