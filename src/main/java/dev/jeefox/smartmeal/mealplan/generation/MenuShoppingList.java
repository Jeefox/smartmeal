package dev.jeefox.smartmeal.mealplan.generation;

import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListItemResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListResponse;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Raw edible ingredient weights are converted to purchased weights before aggregation. */
public final class MenuShoppingList {
    private MenuShoppingList() {
    }

    public static ShoppingListResponse calculate(LocalDate date, List<DayMenuSelector.Choice> choices,
                                                 Map<UUID, List<RecipeIngredient>> ingredients) {
        Map<Long, BigDecimal> quantities = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (var choice : choices) {
            for (var ingredient : ingredients.getOrDefault(choice.recipe().recipeId(), List.of())) {
                var product = ingredient.getProduct();
                BigDecimal quantity = ingredient.getQuantityG().multiply(choice.portions())
                        .divide(BigDecimal.valueOf(choice.recipe().servings()).multiply(product.getEdibleYield()),
                                MathContext.DECIMAL128);
                quantities.merge(product.getId(), quantity, BigDecimal::add);
                names.put(product.getId(), product.getShoppingName());
            }
        }
        var items = quantities.entrySet().stream().map(item -> new ShoppingListItemResponse(item.getKey(),
                        names.get(item.getKey()), item.getValue().setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(ShoppingListItemResponse::productName)
                        .thenComparing(ShoppingListItemResponse::productId)).toList();
        return new ShoppingListResponse(date, date, false, items);
    }
}
