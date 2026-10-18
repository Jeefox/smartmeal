package dev.jeefox.smartmeal.mealplan.service;

import dev.jeefox.smartmeal.exception.*;
import dev.jeefox.smartmeal.mealplan.dto.AddMealPlanEntryRequest;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanEntryResponse;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanResponse;
import dev.jeefox.smartmeal.mealplan.dto.UpdateMealPlanEntryRequest;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class MealPlanEntryServiceTest {
    @Autowired private EntityManager em;
    @Autowired private MealPlanEntryService service;
    @Autowired private MealPlanService planService;
    @Autowired private WebApplicationContext context;

    private User owner;
    private MealPlan plan;
    private Recipe recipe;
    private Product product;

    @BeforeEach
    void setUp() {
        owner = new User("entry-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
        em.persist(owner);
        plan = new MealPlan(owner, LocalDate.of(2031, 1, 1));
        em.persist(plan);
        ProductCategory category = new ProductCategory("ENTRY_" + UUID.randomUUID(), "Тест");
        em.persist(category);
        product = new Product(category, "Крупа", null, 350,
                new BigDecimal("12.00"), new BigDecimal("3.00"), new BigDecimal("65.00"));
        em.persist(product);
        recipe = new Recipe("Каша", "Снимок", "Сварить.", 15, 3);
        em.persist(recipe);
        em.persist(new RecipeIngredient(recipe, product, new BigDecimal("150.00"), 1));
        em.flush();
    }

    @Test
    void persistsSnapshotAndCalculatesFractionalPortions() {
        MealPlanEntryResponse added = add(MealType.LUNCH, "1.50");
        assertEquals(1, added.position());
        assertEquals(new BigDecimal("262.50"), added.nutrition().calories());
        assertEquals(new BigDecimal("9.00"), added.nutrition().protein());
        assertEquals(new BigDecimal("2.25"), added.nutrition().fat());
        assertEquals(new BigDecimal("48.75"), added.nutrition().carbs());
        assertEquals(product.getId(), added.recipeSnapshot().ingredients().getFirst().productId());
        UUID planId = plan.getId();
        String email = owner.getEmail();
        em.flush();
        em.clear();
        assertEquals(added, service.findById(email, planId, added.id()));
    }

    @Test
    void allowsRepeatedRecipesAndAssignsPositionSeparatelyPerMeal() {
        MealPlanEntryResponse lunch1 = add(MealType.LUNCH, "1");
        MealPlanEntryResponse breakfast = add(MealType.BREAKFAST, "0.5");
        MealPlanEntryResponse lunch2 = add(MealType.LUNCH, "2");
        em.flush();
        assertEquals(1, breakfast.position());
        assertEquals(1, lunch1.position());
        assertEquals(2, lunch2.position());
        assertEquals(java.util.List.of(breakfast.id(), lunch1.id(), lunch2.id()),
                service.findAll(owner.getEmail(), plan.getId()).stream().map(MealPlanEntryResponse::id).toList());
    }

    @Test
    void keepsSnapshotAndNutritionAfterProductAndRecipeChanges() {
        MealPlanEntryResponse added = add(MealType.BREAKFAST, "1.00");
        product.updateProduct(product.getCategory(), "Новое имя", null, 700,
                new BigDecimal("24"), new BigDecimal("6"), new BigDecimal("130"));
        em.createNativeQuery("update recipes set name = 'Новое имя рецепта' where id = :id")
                .setParameter("id", recipe.getId()).executeUpdate();
        UUID planId = plan.getId();
        String email = owner.getEmail();
        em.flush();
        em.clear();
        assertEquals(added, service.findById(email, planId, added.id()));
    }

    @Test
    void scalesExactTotalsInsteadOfRoundedPerServingValues() {
        product.updateProduct(product.getCategory(), "Крупа", null, 1,
                new BigDecimal("1"), BigDecimal.ZERO, BigDecimal.ZERO);
        em.createQuery("update RecipeIngredient i set i.quantityG = :quantity where i.recipe.id = :recipeId")
                .setParameter("quantity", new BigDecimal("2.60"))
                .setParameter("recipeId", recipe.getId()).executeUpdate();
        em.flush();
        em.clear();
        MealPlanEntryResponse added = add(MealType.SNACK, "1.50");
        // Exact: 0.026 * 1.5 / 3 = 0.013 -> 0.01. Rounding totals first would give 0.02.
        assertEquals(new BigDecimal("0.01"), added.nutrition().calories());
        assertEquals(new BigDecimal("0.01"), added.nutrition().protein());
    }

    @Test
    void rejectsOtherOwnersForAddingListingAndReading() {
        MealPlanEntryResponse entry = add(MealType.DINNER, "1");
        String otherEmail = "other@example.invalid";
        assertThrows(MealPlanNotFoundException.class, () -> service.add(otherEmail, plan.getId(), request("1")));
        assertThrows(MealPlanNotFoundException.class, () -> service.findAll(otherEmail, plan.getId()));
        assertThrows(MealPlanNotFoundException.class, () -> service.findById(otherEmail, plan.getId(), entry.id()));
    }

    @Test
    void rejectsConfirmedPlan() {
        em.createNativeQuery("update meal_plans set status = 'CONFIRMED', confirmed_at = now(), "
                        + "daily_calorie_target_snapshot = 2200 where id = :id")
                .setParameter("id", plan.getId()).executeUpdate();
        UUID planId = plan.getId();
        String email = owner.getEmail();
        em.clear();
        assertThrows(MealPlanNotEditableException.class, () -> service.add(email, planId, request("1")));
    }

    @Test
    void rejectsMissingRecipeAndEntry() {
        assertThrows(RecipeNotFoundException.class, () -> service.add(owner.getEmail(), plan.getId(),
                new AddMealPlanEntryRequest(UUID.randomUUID(), MealType.LUNCH, BigDecimal.ONE)));
        assertThrows(MealPlanEntryNotFoundException.class,
                () -> service.findById(owner.getEmail(), plan.getId(), UUID.randomUUID()));
    }

    @Test
    void exposesCreateListAndReadThroughSecuredController() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/me/meal-plans/" + plan.getId() + "/entries";
        String body = "{\"recipeId\":\"" + recipe.getId()
                + "\",\"mealType\":\"LUNCH\",\"portions\":1.5}";
        String response = mvc.perform(post(path).with(user(owner.getEmail())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nutrition.calories").value(262.5))
                .andExpect(jsonPath("$.position").value(1))
                .andReturn().getResponse().getHeader("Location");
        assertNotNull(response);
        mvc.perform(get(response).with(user(owner.getEmail())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recipeSnapshot.name").value("Каша"));
        mvc.perform(get(path).with(user(owner.getEmail())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(path).with(user("other@example.invalid")))
                .andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user(owner.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidEntryRequestsWithBadRequest() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/me/meal-plans/" + plan.getId() + "/entries";
        for (String portions : new String[]{"null", "0", "-1", "1.001", "10000"}) {
            String body = "{\"recipeId\":\"" + recipe.getId()
                    + "\",\"mealType\":\"LUNCH\",\"portions\":" + portions + "}";
            mvc.perform(post(path).with(user(owner.getEmail())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(path).with(user(owner.getEmail())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertTrue(service.findAll(owner.getEmail(), plan.getId()).isEmpty());
    }

    @Test
    void returnsEmptyMenuWithZeroNutritionAndNoTarget() {
        MealPlanResponse response = planService.findByDate(owner.getEmail(), plan.getPlanDate());
        assertTrue(response.entries().isEmpty());
        assertEquals(new BigDecimal("0.00"), response.nutritionTotal().calories());
        assertEquals(new BigDecimal("0.00"), response.nutritionTotal().protein());
        assertNull(response.dailyCalorieTarget());
    }

    @Test
    void returnsEntriesInMealOrderAndSummedNutrition() {
        MealPlanEntryResponse dinner = add(MealType.DINNER, "2");
        MealPlanEntryResponse breakfast = add(MealType.BREAKFAST, "0.5");
        MealPlanResponse response = planService.findByDate(owner.getEmail(), plan.getPlanDate());
        assertEquals(java.util.List.of(breakfast.id(), dinner.id()),
                response.entries().stream().map(MealPlanEntryResponse::id).toList());
        assertEquals(new BigDecimal("437.50"), response.nutritionTotal().calories());
        assertEquals(new BigDecimal("15.00"), response.nutritionTotal().protein());
        assertEquals(new BigDecimal("3.75"), response.nutritionTotal().fat());
        assertEquals(new BigDecimal("81.25"), response.nutritionTotal().carbs());
    }

    @Test
    void roundsDailyTotalsAfterAddingExactContributions() {
        product.updateProduct(product.getCategory(), "Крупа", null, 1,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
        em.createQuery("update RecipeIngredient i set i.quantityG = :quantity where i.recipe.id = :recipeId")
                .setParameter("quantity", new BigDecimal("1.00"))
                .setParameter("recipeId", recipe.getId()).executeUpdate();
        em.flush();
        em.clear();
        add(MealType.BREAKFAST, "1");
        Recipe sixServings = new Recipe("Второй рецепт", null, "Сварить.", 10, 6);
        em.persist(sixServings);
        em.persist(new RecipeIngredient(sixServings, em.find(Product.class, product.getId()),
                new BigDecimal("1.00"), 1));
        service.add(owner.getEmail(), plan.getId(),
                new AddMealPlanEntryRequest(sixServings.getId(), MealType.LUNCH, BigDecimal.ONE));
        // 0.01 / 3 + 0.01 / 6 = 0.005 exactly: HALF_UP must yield 0.01.
        MealPlanResponse response = planService.findByDate(owner.getEmail(), plan.getPlanDate());
        assertEquals(new BigDecimal("0.00"), response.entries().getFirst().nutrition().calories());
        assertEquals(new BigDecimal("0.01"), response.nutritionTotal().calories());
        assertEquals(new BigDecimal("0.01"), response.nutritionTotal().protein());
    }

    @Test
    void usesCurrentTargetForDraftAndSnapshotForConfirmedMenu() {
        UserProfile profile = new UserProfile(2200, owner);
        em.persist(profile);
        assertEquals(2200, planService.findByDate(owner.getEmail(), plan.getPlanDate()).dailyCalorieTarget());
        profile.updateDailyCalorieTarget(2400);
        assertEquals(2400, planService.findByDate(owner.getEmail(), plan.getPlanDate()).dailyCalorieTarget());
        em.flush();
        em.createNativeQuery("update meal_plans set status = 'CONFIRMED', confirmed_at = now(), "
                        + "daily_calorie_target_snapshot = 2200 where id = :id")
                .setParameter("id", plan.getId()).executeUpdate();
        em.clear();
        MealPlanResponse confirmed = planService.findByDate(owner.getEmail(), plan.getPlanDate());
        assertEquals(2200, confirmed.dailyCalorieTarget());
        assertEquals(2200, confirmed.dailyCalorieTargetSnapshot());
    }

    @Test
    void updatesPortionsAndDayTotalsWithoutRefreshingSnapshot() {
        MealPlanEntryResponse entry = add(MealType.LUNCH, "1.00");
        product.updateProduct(product.getCategory(), "Изменённый продукт", null, 700,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        MealPlanEntryResponse updated = service.updatePortions(owner.getEmail(), plan.getId(), entry.id(),
                new UpdateMealPlanEntryRequest(new BigDecimal("1.50")));
        assertEquals(entry.recipeSnapshot(), updated.recipeSnapshot());
        assertEquals(entry.position(), updated.position());
        assertEquals(new BigDecimal("262.50"), updated.nutrition().calories());
        UUID planId = plan.getId();
        String email = owner.getEmail();
        em.flush();
        em.clear();
        assertEquals(new BigDecimal("1.50"), service.findById(email, planId, entry.id()).portions());
        assertEquals(new BigDecimal("262.50"),
                planService.findByDate(email, plan.getPlanDate()).nutritionTotal().calories());
    }

    @Test
    void deletesEntryWithoutDeletingRecipeAndKeepsRemainingPositions() {
        MealPlanEntryResponse first = add(MealType.LUNCH, "1");
        MealPlanEntryResponse second = add(MealType.LUNCH, "1");
        MealPlanEntryResponse third = add(MealType.LUNCH, "1");
        service.delete(owner.getEmail(), plan.getId(), second.id());
        em.flush();
        assertEquals(java.util.List.of(first.id(), third.id()),
                service.findAll(owner.getEmail(), plan.getId()).stream().map(MealPlanEntryResponse::id).toList());
        assertEquals(4, add(MealType.LUNCH, "1").position());
        assertNotNull(em.find(Recipe.class, recipe.getId()));
        assertThrows(MealPlanEntryNotFoundException.class,
                () -> service.findById(owner.getEmail(), plan.getId(), second.id()));
    }

    @Test
    void forbidsUpdatingAndDeletingOtherUsersOrOtherPlansEntries() {
        MealPlanEntryResponse entry = add(MealType.LUNCH, "1");
        UpdateMealPlanEntryRequest request = new UpdateMealPlanEntryRequest(BigDecimal.ONE);
        assertThrows(MealPlanNotFoundException.class,
                () -> service.updatePortions("other@example.invalid", plan.getId(), entry.id(), request));
        assertThrows(MealPlanNotFoundException.class,
                () -> service.delete("other@example.invalid", plan.getId(), entry.id()));
        MealPlan otherPlan = new MealPlan(owner, plan.getPlanDate().plusDays(1));
        em.persist(otherPlan);
        assertThrows(MealPlanEntryNotFoundException.class,
                () -> service.updatePortions(owner.getEmail(), otherPlan.getId(), entry.id(), request));
        assertThrows(MealPlanEntryNotFoundException.class,
                () -> service.delete(owner.getEmail(), otherPlan.getId(), entry.id()));
    }

    @Test
    void forbidsUpdatingAndDeletingConfirmedEntries() {
        MealPlanEntryResponse entry = add(MealType.LUNCH, "1");
        em.flush();
        em.createNativeQuery("update meal_plans set status = 'CONFIRMED', confirmed_at = now(), "
                        + "daily_calorie_target_snapshot = 2200 where id = :id")
                .setParameter("id", plan.getId()).executeUpdate();
        em.clear();
        assertThrows(MealPlanNotEditableException.class, () -> service.updatePortions(
                owner.getEmail(), plan.getId(), entry.id(), new UpdateMealPlanEntryRequest(BigDecimal.ONE)));
        assertThrows(MealPlanNotEditableException.class,
                () -> service.delete(owner.getEmail(), plan.getId(), entry.id()));
    }

    @Test
    void exposesUpdateDeleteAndDailyTotalsThroughController() throws Exception {
        MealPlanEntryResponse entry = add(MealType.LUNCH, "1");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/me/meal-plans/" + plan.getId() + "/entries/" + entry.id();
        for (String invalid : new String[]{"{}", "{\"portions\":null}", "{\"portions\":0}",
                "{\"portions\":-1}", "{\"portions\":1.001}", "{\"portions\":10000}"}) {
            mvc.perform(put(path).with(user(owner.getEmail())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(path).with(user(owner.getEmail())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"portions\":1.5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nutrition.calories").value(262.5));
        mvc.perform(get("/api/me/meal-plans").param("date", plan.getPlanDate().toString())
                        .with(user(owner.getEmail())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.nutritionTotal.calories").value(262.5));
        mvc.perform(delete(path).with(user(owner.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete(path).with(user("other@example.invalid")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path).with(user(owner.getEmail())).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/me/meal-plans").param("date", plan.getPlanDate().toString())
                        .with(user(owner.getEmail())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.nutritionTotal.calories").value(0));
    }

    private MealPlanEntryResponse add(MealType type, String portions) {
        return service.add(owner.getEmail(), plan.getId(),
                new AddMealPlanEntryRequest(recipe.getId(), type, new BigDecimal(portions)));
    }

    private AddMealPlanEntryRequest request(String portions) {
        return new AddMealPlanEntryRequest(recipe.getId(), MealType.LUNCH, new BigDecimal(portions));
    }
}
