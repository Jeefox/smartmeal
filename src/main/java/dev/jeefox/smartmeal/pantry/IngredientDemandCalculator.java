package dev.jeefox.smartmeal.pantry;

import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

/** Canonical purchase-mass demand, shared by reservations, shopping and actual deduction. */
public final class IngredientDemandCalculator {
    private IngredientDemandCalculator() {}

    public record Demand(
            String entryId,
            Long productId,
            String name,
            LocalDate date,
            int order,
            BigDecimal quantityG) {}

    public static List<Demand> calculate(
            String entryId, LocalDate date, int order, RecipeSnapshot recipe, BigDecimal portions) {
        Map<Long, BigDecimal> quantities = new TreeMap<>();
        Map<Long, String> names = new HashMap<>();
        for (var i : recipe.ingredients()) {
            quantities.merge(
                    i.productId(),
                    i.quantityG()
                            .multiply(portions)
                            .divide(
                                    BigDecimal.valueOf(recipe.servings()).multiply(i.edibleYield()),
                                    MathContext.DECIMAL128),
                    BigDecimal::add);
            names.merge(i.productId(), i.shoppingName(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        return quantities.entrySet().stream()
                .map(
                        e ->
                                new Demand(
                                        entryId,
                                        e.getKey(),
                                        names.get(e.getKey()),
                                        date,
                                        order,
                                        e.getValue().setScale(30, RoundingMode.HALF_UP)))
                .toList();
    }
}
