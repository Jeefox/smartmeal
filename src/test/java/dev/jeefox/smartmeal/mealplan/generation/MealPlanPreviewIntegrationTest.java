package dev.jeefox.smartmeal.mealplan.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.mealplan.dto.PreviewMealPlanRequest;
import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.service.MealPlanPreviewService;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@SpringBootTest
@Transactional
class MealPlanPreviewIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private WebApplicationContext context;
    @Autowired private MealPlanPreviewService service;
    @Autowired private dev.jeefox.smartmeal.mealplan.service.SaveMealPlanPreviewService saves;
    private MockMvc mvc;
    private User owner;
    private Product product;
    private Recipe breakfast;
    private Recipe lunch;
    private Recipe dinner;
    private final LocalDate date = LocalDate.of(2033, 2, 1);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = new User("preview-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
        em.persist(owner);
        em.persist(new UserProfile(2000, owner));
        ProductCategory category = new ProductCategory("PREVIEW_" + UUID.randomUUID(), "Тест");
        em.persist(category);
        product =
                new Product(
                        category,
                        "Продукт",
                        null,
                        100,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE);
        em.persist(product);
        breakfast = recipe("Завтрак", 10, 500, MealType.BREAKFAST);
        lunch = recipe("Обед", 30, 800, MealType.LUNCH);
        dinner = recipe("Ужин", 40, 700, MealType.DINNER);
        recipe("Долгий завтрак", 60, 500, MealType.BREAKFAST);
        em.flush();
        // Isolate the fixture from any user-maintained recipes in the shared local database.
        em.createNativeQuery(
                        """
                        insert into user_excluded_products(user_id, product_id)
                        select :owner, p.id from products p where p.id <> :product
                        """)
                .setParameter("owner", owner.getId())
                .setParameter("product", product.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    void previewsDayWithoutPersistingMenuAndRespectsTimes() throws Exception {
        long before =
                em.createQuery("select count(p) from MealPlan p where p.user.id=:id", Long.class)
                        .setParameter("id", owner.getId())
                        .getSingleResult();
        mvc.perform(
                        post("/api/me/meal-plans/preview")
                                .with(user(owner.getEmail()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"planDate\":\"" + date + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCalorieTarget").value(2000))
                .andExpect(jsonPath("$.nutritionTotal.calories").value(2000))
                .andExpect(jsonPath("$.calorieDeviation").value(0))
                .andExpect(jsonPath("$.withinTargetTolerance").value(true))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].recipeId").value(breakfast.getId().toString()))
                .andExpect(jsonPath("$.entries[1].recipeId").value(lunch.getId().toString()))
                .andExpect(jsonPath("$.entries[2].recipeId").value(dinner.getId().toString()));
        assertEquals(
                before,
                em.createQuery("select count(p) from MealPlan p where p.user.id=:id", Long.class)
                        .setParameter("id", owner.getId())
                        .getSingleResult());
    }

    @Test
    void recentOwnMenuPenalizesRecipeAndOtherUsersHistoryDoesNot() {
        Recipe fresh = recipe("Другой завтрак", 10, 500, MealType.BREAKFAST);
        User other =
                new User("other-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
        em.persist(other);
        history(owner, breakfast, date.minusDays(1));
        history(other, fresh, date.minusDays(1));
        em.flush();
        em.clear();
        var response = preview();
        assertEquals(fresh.getId(), response.entries().getFirst().recipeId());
        assertFalse(response.warnings().contains("RECENT_RECIPES_USED"));
    }

    @Test
    void marksUnavoidableRecentRecipesAndUnreachableCalorieTarget() {
        history(owner, breakfast, date.minusDays(21));
        em.find(UserProfile.class, owner.getId()).updateDailyCalorieTarget(10000);
        em.flush();
        em.clear();
        var response = preview();
        assertFalse(response.withinTargetTolerance());
        assertTrue(response.warnings().contains("TARGET_NOT_REACHED"));
        assertTrue(response.warnings().contains("RECENT_RECIPES_USED"));
    }

    @Test
    void ignoresHistoryOutsideWindowAndMenusOnRequestedDate() {
        history(owner, breakfast, date.minusDays(22));
        history(owner, dinner, date);
        em.flush();
        em.clear();
        assertFalse(preview().warnings().contains("RECENT_RECIPES_USED"));
    }

    @Test
    void missingCalorieTargetAndTooStrictTimeLimitsReturnConflict() throws Exception {
        mvc.perform(
                        post("/api/me/meal-plans/preview")
                                .with(user(owner.getEmail()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"planDate\":\""
                                                + date
                                                + "\",\"breakfastMaxPreparationTimeMin\":0}"))
                .andExpect(status().isConflict());
        em.find(UserProfile.class, owner.getId()).updateDailyCalorieTarget(null);
        em.flush();
        mvc.perform(
                        post("/api/me/meal-plans/preview")
                                .with(user(owner.getEmail()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"planDate\":\"" + date + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void respectsExplicitProductExclusion() {
        em.createNativeQuery("insert into user_excluded_products(user_id,product_id) values(:u,:p)")
                .setParameter("u", owner.getId())
                .setParameter("p", product.getId())
                .executeUpdate();
        assertThrows(
                dev.jeefox.smartmeal.exception.MenuGenerationUnavailableException.class,
                this::preview);
    }

    @Test
    void respectsAllergensSavedForCurrentUser() {
        String code = "ALLERGY_" + UUID.randomUUID();
        em.createNativeQuery("insert into allergens(code,name) values(:c,'Тест')")
                .setParameter("c", code)
                .executeUpdate();
        Long id =
                ((Number)
                                em.createNativeQuery("select id from allergens where code=:c")
                                        .setParameter("c", code)
                                        .getSingleResult())
                        .longValue();
        em.createNativeQuery("insert into product_allergens(product_id,allergen_id) values(:p,:a)")
                .setParameter("p", product.getId())
                .setParameter("a", id)
                .executeUpdate();
        em.createNativeQuery("insert into user_allergies(user_id,allergen_id) values(:u,:a)")
                .setParameter("u", owner.getId())
                .setParameter("a", id)
                .executeUpdate();
        assertThrows(
                dev.jeefox.smartmeal.exception.MenuGenerationUnavailableException.class,
                this::preview);
    }

    @Test
    void requiresRecipeEquipmentAndAcceptsItWhenUserOwnsIt() {
        String code = "EQUIP_" + UUID.randomUUID();
        em.createNativeQuery("insert into equipment(code,name) values(:c,'Тест')")
                .setParameter("c", code)
                .executeUpdate();
        Long id =
                ((Number)
                                em.createNativeQuery("select id from equipment where code=:c")
                                        .setParameter("c", code)
                                        .getSingleResult())
                        .longValue();
        em.createNativeQuery("insert into recipe_equipment(recipe_id,equipment_id) values(:r,:e)")
                .setParameter("r", dinner.getId())
                .setParameter("e", id)
                .executeUpdate();
        assertThrows(
                dev.jeefox.smartmeal.exception.MenuGenerationUnavailableException.class,
                this::preview);
        em.createNativeQuery("insert into user_equipment(user_id,equipment_id) values(:u,:e)")
                .setParameter("u", owner.getId())
                .setParameter("e", id)
                .executeUpdate();
        assertEquals(3, preview().entries().size());
    }

    @Test
    void validatesRequestAndRequiresSessionAndCsrf() throws Exception {
        mvc.perform(
                        post("/api/me/meal-plans/preview")
                                .with(user(owner.getEmail()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/me/meal-plans/preview")
                                .with(user(owner.getEmail()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"planDate\":\""
                                                + date
                                                + "\",\"breakfastMaxPreparationTimeMin\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/me/meal-plans/preview")
                                .with(user(owner.getEmail()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"planDate\":\"" + date + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/me/meal-plans/preview")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"planDate\":\"" + date + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void budgetsSharedIngredientOnceAndReturnsPackagesAndRemainder() {
        Long storeId = pricedStore("40.00");
        var result =
                service.preview(
                        owner.getEmail(),
                        new PreviewMealPlanRequest(
                                date, null, null, null, storeId, new BigDecimal("50.00")));
        var budget = result.budgetEstimate();
        assertEquals("WITHIN_BUDGET", budget.status());
        assertEquals(new BigDecimal("40.00"), budget.shoppingEstimate().knownPurchaseCost());
        assertEquals(1, budget.shoppingEstimate().items().size());
        var item = budget.shoppingEstimate().items().getFirst();
        assertEquals(1, item.packageCount());
        assertEquals(new BigDecimal("2000.000000000000000000000000000000"), item.requiredWeightG());
        assertEquals(0, item.remainderG().signum());
        assertTrue(result.withinTargetTolerance());
    }

    @Test
    void choosesCheaperRecipeUsingActualStoreOffers() {
        Long storeId = pricedStore("40.00");
        em.createQuery("update StoreProduct s set s.packageWeightG=500 where s.store.id=:id")
                .setParameter("id", storeId)
                .executeUpdate();
        Product cheap =
                new Product(
                        em.find(ProductCategory.class, product.getCategory().getId()),
                        "Дешёвый продукт",
                        null,
                        100,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE);
        em.persist(cheap);
        Recipe alternative = new Recipe("Дешёвый завтрак", null, "Приготовить", 10, 1);
        alternative.changeMealTypes(Set.of(MealType.BREAKFAST));
        em.persist(alternative);
        em.persist(new RecipeIngredient(alternative, cheap, new BigDecimal("500"), 1));
        var sku =
                new dev.jeefox.smartmeal.store.entity.StoreProduct(
                        em.find(dev.jeefox.smartmeal.store.entity.Store.class, storeId),
                        cheap,
                        "Дешёвая упаковка",
                        2000);
        em.persist(sku);
        em.persist(
                new dev.jeefox.smartmeal.price.entity.PriceObservation(
                        sku,
                        BigDecimal.ONE,
                        java.time.Instant.now().minusSeconds(60),
                        dev.jeefox.smartmeal.price.entity.PriceSource.MANUAL,
                        false));
        em.flush();
        em.clear();
        var result =
                service.preview(
                        owner.getEmail(),
                        new PreviewMealPlanRequest(
                                date, null, null, null, storeId, new BigDecimal("125.00")));
        assertEquals(alternative.getId(), result.entries().getFirst().recipeId());
        assertEquals("WITHIN_BUDGET", result.budgetEstimate().status());
        assertTrue(result.withinTargetTolerance());
    }

    @Test
    void budgetPreviewCanBeSavedWithOriginalOptions() {
        Long storeId = pricedStore("40.00");
        var options =
                new PreviewMealPlanRequest(
                        date, null, null, null, storeId, new BigDecimal("50.00"));
        var preview = service.preview(owner.getEmail(), options);
        var selections =
                preview.entries().stream()
                        .map(
                                e ->
                                        new dev.jeefox.smartmeal.mealplan.dto
                                                .SaveMealPlanPreviewRequest.Entry(
                                                e.mealType(),
                                                e.recipeId(),
                                                e.portions(),
                                                e.recipeFingerprint()))
                        .toList();
        var saved =
                saves.save(
                        owner.getEmail(),
                        new dev.jeefox.smartmeal.mealplan.dto.SaveMealPlanPreviewRequest(
                                options, preview.dailyCalorieTarget(), selections));
        assertEquals(3, saved.entries().size());
        assertEquals(preview.nutritionTotal(), saved.nutritionTotal());
    }

    @Test
    void edibleYieldIncreasesPurchaseWeightWithoutIncreasingCalories() {
        Long storeId = pricedStore("40.00");
        em.find(Product.class, product.getId())
                .updateShoppingDetails("Банан", new BigDecimal("0.5"));
        em.flush();
        var result =
                service.preview(
                        owner.getEmail(),
                        new PreviewMealPlanRequest(
                                date, null, null, null, storeId, new BigDecimal("100.00")));
        var item = result.budgetEstimate().shoppingEstimate().items().getFirst();
        assertEquals("Банан", item.productName());
        assertEquals(new BigDecimal("4000.000000000000000000000000000000"), item.requiredWeightG());
        assertEquals(2, item.packageCount());
        assertEquals(new BigDecimal("80.00"), item.purchaseCost());
        assertEquals(new BigDecimal("2000.00"), result.nutritionTotal().calories());
    }

    @Test
    void budgetIsAvailableThroughHttpAndDoesNotPersistPlan() throws Exception {
        Long storeId = pricedStore("40.00");
        mvc.perform(
                        post("/api/me/meal-plans/preview")
                                .with(user(owner.getEmail()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"planDate\":\""
                                                + date
                                                + "\",\"storeId\":"
                                                + storeId
                                                + ",\"budget\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetEstimate.status").value("WITHIN_BUDGET"))
                .andExpect(
                        jsonPath("$.budgetEstimate.shoppingEstimate.knownPurchaseCost").value(40))
                .andExpect(
                        jsonPath("$.budgetEstimate.shoppingEstimate.items[0].packageCount")
                                .value(1));
        assertEquals(
                0L,
                em.createQuery("select count(p) from MealPlan p where p.user.id=:id", Long.class)
                        .setParameter("id", owner.getId())
                        .getSingleResult());
    }

    @Test
    void insufficientBudgetIsExplicitAndKeepsNutritionWithinTolerance() {
        Long storeId = pricedStore("40.00");
        var result =
                service.preview(
                        owner.getEmail(),
                        new PreviewMealPlanRequest(
                                date, null, null, null, storeId, new BigDecimal("1.00")));
        assertEquals("OVER_BUDGET", result.budgetEstimate().status());
        assertEquals(new BigDecimal("39.00"), result.budgetEstimate().excess());
        assertTrue(result.warnings().contains("BUDGET_EXCEEDED"));
        assertTrue(result.withinTargetTolerance());
    }

    @Test
    void absentAndInvalidatedPricesNeverGuaranteeBudget() {
        Long storeId = pricedStore("40.00");
        em.createQuery(
                        "select p from PriceObservation p where p.storeProduct.store.id=:id",
                        dev.jeefox.smartmeal.price.entity.PriceObservation.class)
                .setParameter("id", storeId)
                .getResultList()
                .forEach(p -> p.invalidate("Тест", owner.getEmail()));
        em.flush();
        var result =
                service.preview(
                        owner.getEmail(),
                        new PreviewMealPlanRequest(
                                date, null, null, null, storeId, new BigDecimal("100.00")));
        assertEquals("UNKNOWN", result.budgetEstimate().status());
        assertFalse(result.budgetEstimate().shoppingEstimate().complete());
        assertTrue(result.warnings().contains("PRICES_INCOMPLETE"));
        assertNull(result.budgetEstimate().shoppingEstimate().items().getFirst().purchaseCost());
    }

    @Test
    void budgetRequiresBothParametersAndPositiveAmount() throws Exception {
        for (String extra :
                List.of(
                        ",\"budget\":50",
                        ",\"storeId\":11",
                        ",\"storeId\":11,\"budget\":0",
                        ",\"storeId\":11,\"budget\":1.001",
                        ",\"storeId\":-1,\"budget\":50")) {
            mvc.perform(
                            post("/api/me/meal-plans/preview")
                                    .with(user(owner.getEmail()))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"planDate\":\"" + date + "\"" + extra + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void budgetRejectsUnknownAndInactiveStore() {
        assertThrows(
                dev.jeefox.smartmeal.exception.StoreNotFoundException.class,
                () ->
                        service.preview(
                                owner.getEmail(),
                                new PreviewMealPlanRequest(
                                        date, null, null, null, Long.MAX_VALUE, BigDecimal.TEN)));
        Long storeId = pricedStore("40.00");
        em.createNativeQuery("update stores set active=false where id=:id")
                .setParameter("id", storeId)
                .executeUpdate();
        em.clear();
        assertThrows(
                dev.jeefox.smartmeal.exception.StoreInactiveException.class,
                () ->
                        service.preview(
                                owner.getEmail(),
                                new PreviewMealPlanRequest(
                                        date, null, null, null, storeId, BigDecimal.TEN)));
    }

    private Long pricedStore(String price) {
        var chain =
                new dev.jeefox.smartmeal.store.entity.RetailChain(
                        "BUDGET_" + UUID.randomUUID(), "Тест");
        em.persist(chain);
        var store = new dev.jeefox.smartmeal.store.entity.Store(chain, "Тула", "Тест");
        em.persist(store);
        var sku =
                new dev.jeefox.smartmeal.store.entity.StoreProduct(
                        store, em.find(Product.class, product.getId()), "Упаковка", 2000);
        em.persist(sku);
        em.persist(
                new dev.jeefox.smartmeal.price.entity.PriceObservation(
                        sku,
                        new BigDecimal(price),
                        java.time.Instant.now().minusSeconds(60),
                        dev.jeefox.smartmeal.price.entity.PriceSource.MANUAL,
                        false));
        em.flush();
        return store.getId();
    }

    private dev.jeefox.smartmeal.mealplan.dto.MealPlanPreviewResponse preview() {
        return service.preview(
                owner.getEmail(), new PreviewMealPlanRequest(date, null, null, null));
    }

    private Recipe recipe(String name, int minutes, int grams, MealType type) {
        Recipe recipe = new Recipe(name, null, "Приготовить", minutes, 1);
        recipe.changeMealTypes(Set.of(type));
        em.persist(recipe);
        em.persist(new RecipeIngredient(recipe, product, BigDecimal.valueOf(grams), 1));
        return recipe;
    }

    private void history(User owner, Recipe recipe, LocalDate day) {
        MealPlan plan = new MealPlan(owner, day);
        em.persist(plan);
        NutritionValues zero =
                new NutritionValues(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        RecipeSnapshot snapshot =
                new RecipeSnapshot(
                        recipe.getId(),
                        recipe.getName(),
                        null,
                        "Приготовить",
                        10,
                        1,
                        List.of(),
                        zero,
                        zero);
        em.persist(
                new MealPlanEntry(plan, recipe, MealType.BREAKFAST, BigDecimal.ONE, 1, snapshot));
    }
}
