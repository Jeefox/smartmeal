package dev.jeefox.smartmeal.household;

import static dev.jeefox.smartmeal.household.HouseholdModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.junit.jupiter.api.Test;

import java.math.*;
import java.util.*;

class HouseholdPortionsTest {
    RecipeSnapshot recipe() {
        var n =
                new NutritionValues(
                        new BigDecimal("600"),
                        new BigDecimal("30"),
                        new BigDecimal("20"),
                        new BigDecimal("75"));
        return new RecipeSnapshot(
                UUID.randomUUID(), "Каша", null, "Приготовить", 10, 2, List.of(), n, n);
    }

    @Test
    void differentNormsGiveDifferentPortionsAndConserveTheWholeDish() {
        var people =
                List.of(
                        new Participant(UUID.randomUUID(), "Вы", 2000, true),
                        new Participant(UUID.randomUUID(), "Анна", 1000, false));
        var shares = HouseholdPortions.allocate(new BigDecimal("3"), recipe(), people);
        assertEquals(0, shares.get(0).portions().compareTo(new BigDecimal("2")));
        assertEquals(0, shares.get(1).portions().compareTo(BigDecimal.ONE));
        assertEquals(new BigDecimal("600.00"), shares.get(0).nutrition().calories());
        assertEquals(new BigDecimal("300.00"), shares.get(1).nutrition().calories());
    }

    @Test
    void recurringRatiosNeverLoseGramsOrAddPortions() {
        var ps =
                List.of(
                        new Participant(UUID.randomUUID(), "Вы", 2000, true),
                        new Participant(UUID.randomUUID(), "Б", 1700, false),
                        new Participant(UUID.randomUUID(), "В", 2200, false));
        var total = new BigDecimal("1.25");
        assertEquals(
                0,
                HouseholdPortions.allocate(total, recipe(), ps).stream()
                        .map(PersonPortion::portions)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(total));
    }
}
