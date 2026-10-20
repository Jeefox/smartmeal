package dev.jeefox.smartmeal.mealplan.service;

import dev.jeefox.smartmeal.exception.DailyCalorieTargetNotSetException;
import dev.jeefox.smartmeal.exception.MealPlanNotEditableException;
import dev.jeefox.smartmeal.exception.MealPlanNotFoundException;
import dev.jeefox.smartmeal.exception.MenuEntryNotExistsException;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanResponse;
import dev.jeefox.smartmeal.mealplan.dto.UpdateMealPlanEntryRequest;
import dev.jeefox.smartmeal.mealplan.dto.AddMealPlanEntryRequest;
import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class MealPlanConfirmationTest {
    @Autowired private EntityManager em;
    @Autowired private MealPlanService plans;
    @Autowired private MealPlanEntryService entries;
    @Autowired private WebApplicationContext context;

    private User owner;
    private MealPlan plan;

    @BeforeEach
    void setUp() {
        owner = new User("confirm-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
        em.persist(owner);
        plan = new MealPlan(owner, LocalDate.of(2032, 1, 1));
        em.persist(plan);
        em.flush();
    }

    @Test
    void confirmsAndPersistsGoalDateAndUnchangedSnapshots() {
        givenTarget(2200);
        givenEntry();
        MealPlanResponse before = read();

        MealPlanResponse confirmed = plans.confirmMenu(owner.getEmail(), plan.getId());

        assertEquals(MealPlanStatus.CONFIRMED, confirmed.status());
        assertEquals(2200, confirmed.dailyCalorieTargetSnapshot());
        assertEquals(2200, confirmed.dailyCalorieTarget());
        assertNotNull(confirmed.confirmedAt());
        assertEquals(before.entries(), confirmed.entries());
        assertEquals(before.nutritionTotal(), confirmed.nutritionTotal());
        em.flush();
        em.clear();
        MealPlanResponse loaded = read();
        assertEquals(MealPlanStatus.CONFIRMED, loaded.status());
        assertEquals(2200, loaded.dailyCalorieTargetSnapshot());
        assertNotNull(loaded.confirmedAt());
        assertEquals(confirmed.entries(), loaded.entries());
        assertEquals(confirmed.nutritionTotal(), loaded.nutritionTotal());
    }

    @Test
    void rejectsEmptyMenuWithoutChangingDraft() {
        givenTarget(2200);

        assertThrows(MenuEntryNotExistsException.class,
                () -> plans.confirmMenu(owner.getEmail(), plan.getId()));

        assertDraft();
    }

    @Test
    void rejectsMissingProfileWithoutChangingDraft() {
        givenEntry();

        assertThrows(DailyCalorieTargetNotSetException.class,
                () -> plans.confirmMenu(owner.getEmail(), plan.getId()));

        assertDraft();
    }

    @Test
    void rejectsUnsetGoalWithoutChangingDraft() {
        givenTarget(null);
        givenEntry();

        assertThrows(DailyCalorieTargetNotSetException.class,
                () -> plans.confirmMenu(owner.getEmail(), plan.getId()));

        assertDraft();
    }

    @Test
    void rejectsOtherOwnerAndMissingMenu() {
        givenTarget(2200);
        givenEntry();

        assertThrows(MealPlanNotFoundException.class,
                () -> plans.confirmMenu("other@example.invalid", plan.getId()));
        assertThrows(MealPlanNotFoundException.class,
                () -> plans.confirmMenu(owner.getEmail(), UUID.randomUUID()));
        assertDraft();
    }

    @Test
    void rejectsRepeatConfirmationAndKeepsOriginalGoal() {
        UserProfile profile = givenTarget(2200);
        givenEntry();
        MealPlanResponse first = plans.confirmMenu(owner.getEmail(), plan.getId());
        profile.updateDailyCalorieTarget(2500);

        assertThrows(MealPlanNotEditableException.class,
                () -> plans.confirmMenu(owner.getEmail(), plan.getId()));

        MealPlanResponse after = read();
        assertEquals(2200, after.dailyCalorieTargetSnapshot());
        assertEquals(first.confirmedAt(), after.confirmedAt());
    }

    @Test
    void laterProfileAndRecipeChangesDoNotChangeConfirmedMenu() {
        UserProfile profile = givenTarget(2200);
        MealPlanEntry entry = givenEntry();
        MealPlanResponse confirmed = plans.confirmMenu(owner.getEmail(), plan.getId());
        profile.updateDailyCalorieTarget(2500);
        em.createNativeQuery("update recipes set name = 'Изменено' where id = :id")
                .setParameter("id", entry.getRecipe().getId()).executeUpdate();
        em.flush();
        em.clear();

        MealPlanResponse after = read();
        assertEquals(2200, after.dailyCalorieTarget());
        assertEquals(confirmed.entries(), after.entries());
        assertEquals(confirmed.nutritionTotal(), after.nutritionTotal());
    }

    @Test
    void preventsAllEntryMutationsAfterActualConfirmation() {
        givenTarget(2200);
        MealPlanEntry entry = givenEntry();
        plans.confirmMenu(owner.getEmail(), plan.getId());

        assertThrows(MealPlanNotEditableException.class, () -> entries.add(owner.getEmail(), plan.getId(),
                new AddMealPlanEntryRequest(entry.getRecipe().getId(), MealType.LUNCH, BigDecimal.ONE)));
        assertThrows(MealPlanNotEditableException.class, () -> entries.updatePortions(owner.getEmail(),
                plan.getId(), entry.getId(), new UpdateMealPlanEntryRequest(new BigDecimal("2"))));
        assertThrows(MealPlanNotEditableException.class,
                () -> entries.delete(owner.getEmail(), plan.getId(), entry.getId()));
        assertEquals(1, read().entries().size());
    }

    @Test
    void controllerReturnsConflictForEmptyMenuAndUnsetTarget() throws Exception {
        MockMvc mvc = mvc();
        String path = path();
        mvc.perform(post(path).with(user(owner.getEmail())).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Меню пустое"));
        givenEntry();
        mvc.perform(post(path).with(user(owner.getEmail())).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Суточная цель не задана"));
    }

    @Test
    void controllerConfirmsAndEnforcesAccessAndCsrf() throws Exception {
        givenTarget(2200);
        givenEntry();
        MockMvc mvc = mvc();
        mvc.perform(post(path()).with(user(owner.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(post(path()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).with(user("other@example.invalid")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(path()).with(user(owner.getEmail())).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.dailyCalorieTargetSnapshot").value(2200))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());
        mvc.perform(post(path()).with(user(owner.getEmail())).with(csrf()))
                .andExpect(status().isConflict());
    }

    private UserProfile givenTarget(Integer target) {
        UserProfile profile = new UserProfile(target, owner);
        em.persist(profile);
        return profile;
    }

    private MealPlanEntry givenEntry() {
        Recipe recipe = new Recipe("Снимок блюда", null, "Приготовить.", 10, 2);
        em.persist(recipe);
        NutritionValues total = new NutritionValues(new BigDecimal("400.00"),
                new BigDecimal("20.00"), new BigDecimal("10.00"), new BigDecimal("50.00"));
        NutritionValues perServing = new NutritionValues(new BigDecimal("200.00"),
                new BigDecimal("10.00"), new BigDecimal("5.00"), new BigDecimal("25.00"));
        RecipeSnapshot snapshot = new RecipeSnapshot(recipe.getId(), recipe.getName(), null,
                recipe.getInstructions(), 10, 2, List.of(), total, perServing);
        MealPlanEntry entry = new MealPlanEntry(plan, recipe, MealType.LUNCH,
                new BigDecimal("1.50"), 1, snapshot);
        em.persist(entry);
        em.flush();
        return entry;
    }

    private MealPlanResponse read() {
        return plans.findByDate(owner.getEmail(), plan.getPlanDate());
    }

    private void assertDraft() {
        assertEquals(MealPlanStatus.DRAFT, plan.getStatus());
        assertNull(plan.getDailyCalorieTargetSnapshot());
        assertNull(plan.getConfirmedAt());
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String path() {
        return "/api/me/meal-plans/" + plan.getId() + "/confirm";
    }
}
