package dev.jeefox.smartmeal.household;

import static dev.jeefox.smartmeal.household.HouseholdModels.*;

import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import java.math.*;
import java.util.*;

public final class HouseholdPortions {
    private HouseholdPortions() {}

    public static List<PersonPortion> allocate(
            BigDecimal portions, RecipeSnapshot recipe, List<Participant> people) {
        if (people.isEmpty()) return List.of();
        if (!recipe.components().isEmpty())
            return people.stream()
                    .map(
                            p -> {
                                var n =
                                        dev.jeefox.smartmeal.mealplan.balanced.CompositeMeals
                                                .forPerson(recipe, p.id())
                                                .scaled(portions);
                                return new PersonPortion(
                                        p.id(),
                                        p.name(),
                                        recipe.nutritionTotal().calories().signum() == 0
                                                ? BigDecimal.ZERO
                                                : n.calories()
                                                        .divide(
                                                                recipe.nutritionTotal().calories(),
                                                                MathContext.DECIMAL128),
                                        n.rounded());
                            })
                    .toList();
        int target = people.stream().mapToInt(Participant::dailyCalorieTarget).sum();
        List<PersonPortion> result = new ArrayList<>();
        BigDecimal left = portions;
        for (int i = 0; i < people.size(); i++) {
            var person = people.get(i);
            BigDecimal share =
                    i == people.size() - 1
                            ? left
                            : portions.multiply(BigDecimal.valueOf(person.dailyCalorieTarget()))
                                    .divide(BigDecimal.valueOf(target), 12, RoundingMode.DOWN);
            left = left.subtract(share);
            var n = recipe.nutritionTotal();
            BigDecimal ratio =
                    share.divide(BigDecimal.valueOf(recipe.servings()), MathContext.DECIMAL128);
            result.add(
                    new PersonPortion(
                            person.id(), person.name(), share, n.scaled(ratio).rounded()));
        }
        return List.copyOf(result);
    }

    public static List<PersonNutrition> totals(
            List<Participant> people, List<PersonPortion> portions) {
        return people.stream()
                .map(
                        p -> {
                            var values =
                                    portions.stream()
                                            .filter(v -> v.participantId().equals(p.id()))
                                            .map(PersonPortion::nutrition)
                                            .toList();
                            return new PersonNutrition(
                                    p.id(),
                                    p.name(),
                                    p.dailyCalorieTarget(),
                                    new NutritionValues(
                                            sum(values, 0),
                                            sum(values, 1),
                                            sum(values, 2),
                                            sum(values, 3),
                                            values.stream()
                                                    .map(NutritionValues::knownFiber)
                                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                                    .setScale(2, RoundingMode.HALF_UP),
                                            values.stream()
                                                    .allMatch(NutritionValues::fiberComplete)),
                                    p.proteinTargetG(),
                                    p.fiberTargetG());
                        })
                .toList();
    }

    private static BigDecimal round(BigDecimal n) {
        return n.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<NutritionValues> ns, int field) {
        return ns.stream()
                .map(
                        n ->
                                switch (field) {
                                    case 0 -> n.calories();
                                    case 1 -> n.protein();
                                    case 2 -> n.fat();
                                    default -> n.carbs();
                                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
