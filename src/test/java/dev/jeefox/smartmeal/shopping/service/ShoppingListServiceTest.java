package dev.jeefox.smartmeal.shopping.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.exception.InvalidShoppingListPeriodException;
import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeIngredientSnapshot;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListItemResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListResponse;
import dev.jeefox.smartmeal.user.entity.User;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Transactional
class ShoppingListServiceTest {
    @Autowired private EntityManager em;
    @Autowired private ShoppingListService service;
    private User owner;
    private final LocalDate start = LocalDate.of(2033, 1, 1);

    @BeforeEach
    void setUp() {
        owner = user();
    }

    @Test
    void returnsEmptyListWhenNoMenusExistAndAllowsOneDay() {
        ShoppingListResponse response = service.calculate(owner.getEmail(), start, start);
        assertEquals(start, response.from());
        assertEquals(start, response.to());
        assertFalse(response.containsDrafts());
        assertTrue(response.items().isEmpty());
    }

    @Test
    void emptyDraftStillMarksListAsPreliminary() {
        plan(owner, start, false);
        ShoppingListResponse response = service.calculate(owner.getEmail(), start, start);
        assertTrue(response.containsDrafts());
        assertTrue(response.items().isEmpty());
    }

    @Test
    void sumsProductsAcrossMenusAndUsesLatestName() {
        MealPlan first = plan(owner, start, false);
        MealPlan last = plan(owner, start.plusDays(1), true);
        entry(
                first,
                2,
                "1.5",
                new RecipeIngredientSnapshot(1L, "Старое имя", new BigDecimal("100")));
        entry(
                last,
                3,
                "2",
                new RecipeIngredientSnapshot(1L, "Рис", new BigDecimal("150")),
                new RecipeIngredientSnapshot(2L, "Гречка", new BigDecimal("60")));
        ShoppingListResponse response = read(start, start.plusDays(1));
        assertTrue(response.containsDrafts());
        assertEquals(
                List.of(
                        new ShoppingListItemResponse(
                                2L, "Гречка", new BigDecimal("40.000000000000000000000000000000")),
                        new ShoppingListItemResponse(
                                1L, "Рис", new BigDecimal("175.000000000000000000000000000000"))),
                response.items());
    }

    @Test
    void choosesAlphabeticalNameOnSameDateAndSortsByNameThenId() {
        MealPlan plan = plan(owner, start, true);
        entry(plan, 1, "1", new RecipeIngredientSnapshot(2L, "Яблоко", BigDecimal.ONE));
        entry(
                plan,
                1,
                "1",
                new RecipeIngredientSnapshot(2L, "Крупа", BigDecimal.ONE),
                new RecipeIngredientSnapshot(1L, "Крупа", BigDecimal.ONE));
        ShoppingListResponse response = read(start, start);
        assertFalse(response.containsDrafts());
        assertEquals(
                List.of(
                        new ShoppingListItemResponse(
                                1L, "Крупа", new BigDecimal("1.000000000000000000000000000000")),
                        new ShoppingListItemResponse(
                                2L, "Крупа", new BigDecimal("2.000000000000000000000000000000"))),
                response.items());
    }

    @Test
    void includesBothBoundaryDatesAndExcludesForeignAndOutsideMenus() {
        User other = user();
        entry(plan(owner, start, true), 1, "1", ingredient("10"));
        entry(plan(owner, start.plusDays(2), true), 1, "1", ingredient("20"));
        entry(plan(owner, start.minusDays(1), false), 1, "1", ingredient("100"));
        entry(plan(owner, start.plusDays(3), false), 1, "1", ingredient("100"));
        entry(plan(other, start, false), 1, "1", ingredient("1000"));
        ShoppingListResponse response = read(start, start.plusDays(2));
        assertFalse(response.containsDrafts());
        assertEquals(
                new BigDecimal("30.000000000000000000000000000000"),
                response.items().getFirst().quantityG());
    }

    @Test
    void keepsCanonicalPrecisionUntilPackagingAndDisplay() {
        MealPlan plan = plan(owner, start, false);
        entry(plan, 10, "1", ingredient("0.04"));
        entry(plan, 10, "1", ingredient("0.04"));
        assertEquals(
                new BigDecimal("0.008000000000000000000000000000"),
                read(start, start).items().getFirst().quantityG());
    }

    @Test
    void acceptsThirtyOneDaysAndRejectsInvalidPeriods() {
        assertTrue(
                service.calculate(owner.getEmail(), start, start.plusDays(30)).items().isEmpty());
        assertThrows(
                InvalidShoppingListPeriodException.class,
                () -> service.calculate(owner.getEmail(), start, start.plusDays(31)));
        assertThrows(
                InvalidShoppingListPeriodException.class,
                () -> service.calculate(owner.getEmail(), start, start.minusDays(1)));
        assertThrows(
                InvalidShoppingListPeriodException.class,
                () -> service.calculate(owner.getEmail(), null, start));
        assertThrows(
                InvalidShoppingListPeriodException.class,
                () -> service.calculate(owner.getEmail(), start, null));
    }

    @Test
    void readsSnapshotAfterClearingContextAndIgnoresRecipeEdits() {
        MealPlan plan = plan(owner, start, true);
        Recipe recipe = entry(plan, 3, "1.5", ingredient("100"));
        em.flush();
        em.createNativeQuery(
                        "update recipes set name = 'Изменённый рецепт', servings = 9 where id ="
                                + " :id")
                .setParameter("id", recipe.getId())
                .executeUpdate();
        em.clear();
        ShoppingListResponse response = service.calculate(owner.getEmail(), start, start);
        assertEquals(
                new BigDecimal("50.000000000000000000000000000000"),
                response.items().getFirst().quantityG());
    }

    @Test
    void convertsEdibleWeightToPurchaseWeightBeforeSumming() {
        MealPlan plan = plan(owner, start, false);
        entry(
                plan,
                2,
                "1",
                new RecipeIngredientSnapshot(
                        1L,
                        "Банан без кожуры",
                        new BigDecimal("100"),
                        "Бананы",
                        new BigDecimal("0.5")));
        entry(
                plan,
                1,
                "1",
                new RecipeIngredientSnapshot(
                        1L,
                        "Банан без кожуры",
                        new BigDecimal("25"),
                        "Бананы",
                        new BigDecimal("0.5")));
        ShoppingListResponse result = read(start, start);
        assertEquals(
                new ShoppingListItemResponse(
                        1L, "Бананы", new BigDecimal("150.000000000000000000000000000000")),
                result.items().getFirst());
    }

    @Test
    void readsLegacyJsonWithoutShoppingFields() {
        MealPlan plan = plan(owner, start, false);
        entry(plan, 1, "1", ingredient("100"));
        em.flush();
        em.createNativeQuery(
                        """
update meal_plan_entries
set recipe_snapshot = jsonb_set(recipe_snapshot, '{ingredients}',
    jsonb_build_array((recipe_snapshot -> 'ingredients' -> 0) - 'shoppingName' - 'edibleYield'))
where meal_plan_id = :planId
""")
                .setParameter("planId", plan.getId())
                .executeUpdate();
        em.clear();
        ShoppingListResponse result = service.calculate(owner.getEmail(), start, start);
        assertEquals(
                new ShoppingListItemResponse(
                        1L, "Рис", new BigDecimal("100.000000000000000000000000000000")),
                result.items().getFirst());
    }

    private ShoppingListResponse read(LocalDate from, LocalDate to) {
        em.flush();
        em.clear();
        return service.calculate(owner.getEmail(), from, to);
    }

    private User user() {
        User user =
                new User("shopping-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
        em.persist(user);
        return user;
    }

    private MealPlan plan(User user, LocalDate date, boolean confirmed) {
        MealPlan plan = new MealPlan(user, date);
        if (confirmed) plan.confirm(2200);
        em.persist(plan);
        return plan;
    }

    private Recipe entry(
            MealPlan plan, int servings, String portions, RecipeIngredientSnapshot... ingredients) {
        Recipe recipe = new Recipe("Тестовый рецепт", null, "Приготовить.", 10, servings);
        em.persist(recipe);
        NutritionValues zero =
                new NutritionValues(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        RecipeSnapshot snapshot =
                new RecipeSnapshot(
                        recipe.getId(),
                        recipe.getName(),
                        null,
                        recipe.getInstructions(),
                        10,
                        servings,
                        List.of(ingredients),
                        zero,
                        zero);
        em.persist(
                new MealPlanEntry(
                        plan,
                        recipe,
                        MealType.LUNCH,
                        new BigDecimal(portions),
                        em.createQuery(
                                                "select count(e) from MealPlanEntry e where"
                                                        + " e.mealPlan.id = :id",
                                                Long.class)
                                        .setParameter("id", plan.getId())
                                        .getSingleResult()
                                        .intValue()
                                + 1,
                        snapshot));
        return recipe;
    }

    private RecipeIngredientSnapshot ingredient(String quantity) {
        return new RecipeIngredientSnapshot(1L, "Рис", new BigDecimal(quantity));
    }
}
