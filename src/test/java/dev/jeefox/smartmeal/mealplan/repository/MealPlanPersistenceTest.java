package dev.jeefox.smartmeal.mealplan.repository;

import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanStatus;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeIngredientSnapshot;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class MealPlanPersistenceTest {

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private MealPlanRepository plans;
    @Autowired
    private MealPlanEntryRepository entries;

    @Test
    void roundTripsSnapshotThroughJsonbAndKeepsItWhenRecipeChanges() {
        User user = user();
        Recipe recipe = new Recipe("Каша", null, "Сварить крупу.", 15, 2);
        entityManager.persist(recipe);
        MealPlan plan = plans.save(new MealPlan(user, LocalDate.of(2030, 1, 15)));
        RecipeSnapshot snapshot = new RecipeSnapshot(recipe.getId(), "Каша", null,
                "Сварить крупу.", 15, 2,
                List.of(new RecipeIngredientSnapshot(42L, "Крупа", new BigDecimal("100.25"))),
                nutrition("350.50", "12.00", "3.00", "65.00"),
                nutrition("175.25", "6.00", "1.50", "32.50"));
        MealPlanEntry entry = entries.save(new MealPlanEntry(plan, recipe, MealType.BREAKFAST,
                new BigDecimal("1.50"), 1, snapshot));
        UUID entryId = entry.getId();
        UUID planId = plan.getId();
        UUID userId = user.getId();
        UUID recipeId = recipe.getId();
        entityManager.flush();
        entityManager.clear();

        MealPlanEntry loaded = entries.findById(entryId).orElseThrow();
        assertEquals(snapshot, loaded.getRecipeSnapshot());
        assertEquals(new BigDecimal("1.50"), loaded.getPortions());
        assertEquals(MealType.BREAKFAST, loaded.getMealType());
        assertEquals(1, loaded.getPosition());
        assertEquals(planId, loaded.getMealPlan().getId());
        assertEquals(recipeId, loaded.getRecipe().getId());
        assertEquals("object", entityManager.createNativeQuery(
                "select jsonb_typeof(recipe_snapshot) from meal_plan_entries where id = :id", String.class)
                .setParameter("id", entryId).getSingleResult());
        MealPlan loadedPlan = plans.findByUser_IdAndPlanDate(userId, LocalDate.of(2030, 1, 15))
                .orElseThrow();
        assertEquals(planId, loadedPlan.getId());
        assertEquals(MealPlanStatus.DRAFT, loadedPlan.getStatus());
        assertNull(loadedPlan.getConfirmedAt());
        assertNull(loadedPlan.getDailyCalorieTargetSnapshot());
        assertNotNull(loadedPlan.getCreatedAt());
        assertNotNull(loadedPlan.getUpdatedAt());

        entityManager.createNativeQuery("update recipes set name = :name where id = :id")
                .setParameter("name", "Изменённый рецепт").setParameter("id", recipeId).executeUpdate();
        entityManager.clear();
        MealPlanEntry afterChange = entries.findById(entryId).orElseThrow();
        assertEquals("Изменённый рецепт", afterChange.getRecipe().getName());
        assertEquals(snapshot, afterChange.getRecipeSnapshot());
    }

    @Test
    void findsPlansByBothOwnerAndDate() {
        User first = user();
        User second = user();
        LocalDate date = LocalDate.of(2030, 2, 1);
        MealPlan firstPlan = plans.save(new MealPlan(first, date));
        MealPlan secondPlan = plans.save(new MealPlan(second, date));
        UUID firstId = first.getId();
        UUID secondId = second.getId();
        UUID firstPlanId = firstPlan.getId();
        UUID secondPlanId = secondPlan.getId();
        entityManager.flush();
        entityManager.clear();

        assertEquals(firstPlanId, plans.findByUser_IdAndPlanDate(firstId, date).orElseThrow().getId());
        assertEquals(secondPlanId, plans.findByUser_IdAndPlanDate(secondId, date).orElseThrow().getId());
        assertTrue(plans.findByUser_IdAndPlanDate(firstId, date.plusDays(1)).isEmpty());
        assertTrue(plans.findByUser_IdAndPlanDate(UUID.randomUUID(), date).isEmpty());
    }

    private User user() {
        User user = new User("menu-test-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
        entityManager.persist(user);
        return user;
    }

    private static NutritionValues nutrition(String calories, String protein, String fat, String carbs) {
        return new NutritionValues(new BigDecimal(calories), new BigDecimal(protein),
                new BigDecimal(fat), new BigDecimal(carbs));
    }
}
