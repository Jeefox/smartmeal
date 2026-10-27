package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.pantry.*;
import dev.jeefox.smartmeal.shopping.calculation.WeightPrecision;

import java.math.BigDecimal;
import java.util.*;

/**
 * Counts distinct base products that really need buying, never packages or ingredient appearances.
 */
public final class GroceryPolicy {
    private GroceryPolicy() {}

    public static long count(Basket basket) {
        return basket.items().stream()
                .filter(i -> i.needToBuyG().signum() > 0)
                .map(BasketItem::productId)
                .distinct()
                .count();
    }

    public static long count(PlanningContext c, Options o, List<Selection> entries) {
        var demand = new ArrayList<IngredientDemandCalculator.Demand>();
        for (var e : entries)
            demand.addAll(
                    IngredientDemandCalculator.calculate(
                            e.id(), e.date(), e.mealType().ordinal(), e.recipe(), e.portions()));
        var totals = new TreeMap<Long, BigDecimal>();
        demand.forEach(d -> totals.merge(d.productId(), d.quantityG(), BigDecimal::add));
        var covered =
                o.usePantry()
                        ? PantryAllocationCalculator.allocate(demand, c.pantry()).coveredG()
                        : Map.<Long, BigDecimal>of();
        return totals.entrySet().stream()
                .filter(
                        e ->
                                WeightPrecision.shortage(
                                                        e.getValue(),
                                                        covered.getOrDefault(
                                                                e.getKey(), BigDecimal.ZERO))
                                                .signum()
                                        > 0)
                .count();
    }

    public static boolean fits(PlanningContext c, Options o, List<Selection> entries) {
        return o.maxShoppingProducts() == null || count(c, o, entries) <= o.maxShoppingProducts();
    }

    public static boolean fits(Options o, Basket b) {
        return o.maxShoppingProducts() == null || count(b) <= o.maxShoppingProducts();
    }
}
