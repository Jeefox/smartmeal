package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanRepository;
import dev.jeefox.smartmeal.price.entity.*;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.recipe.entity.*;
import dev.jeefox.smartmeal.store.entity.*;
import dev.jeefox.smartmeal.user.entity.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@SpringBootTest
@Transactional
class PeriodPlanningIntegrationTest {
    @Autowired EntityManager em;
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper mapper;
    @Autowired dev.jeefox.smartmeal.pantry.PantryService pantry;
    @Autowired PeriodPlanningService service;
    @Autowired GenerationCommitService saves;
    @Autowired MealPlanRepository plans;
    @Autowired MenuGenerationRepository generations;
    MockMvc mvc;
    User owner;
    Product product;
    RetailChain chain;
    StoreProduct storeProduct;
    final LocalDate from = LocalDate.of(2038, 3, 1);

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        owner = new User("period-" + UUID.randomUUID() + "@example.invalid", "test-hash");
        em.persist(owner);
        em.persist(new UserProfile(2000, owner));
        var category = new ProductCategory("PERIOD_" + UUID.randomUUID(), "Тест подбора");
        em.persist(category);
        product =
                new Product(
                        category,
                        "Крупа для теста",
                        null,
                        100,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE);
        em.persist(product);
        for (MealType meal : PeriodMenuEvaluator.MEALS)
            for (int i = 0; i < 4; i++) {
                var recipe = new Recipe("Тест " + meal + " " + i, null, "Приготовить", 10, 1);
                recipe.changeMealTypes(Set.of(meal));
                em.persist(recipe);
                em.persist(
                        new RecipeIngredient(
                                recipe,
                                product,
                                BigDecimal.valueOf(
                                        meal == MealType.BREAKFAST
                                                ? 500
                                                : meal == MealType.LUNCH ? 800 : 700),
                                1));
            }
        chain = new RetailChain("PERIOD_" + UUID.randomUUID(), "Тестовая сеть");
        em.persist(chain);
        var store = new Store(chain, "Тула", "Тестовая улица");
        em.persist(store);
        storeProduct = new StoreProduct(store, product, "Пачка 1 кг", 1000);
        em.persist(storeProduct);
        em.persist(
                new PriceObservation(
                        storeProduct,
                        new BigDecimal("100.00"),
                        Instant.now().minusSeconds(60),
                        PriceSource.MANUAL,
                        false));
        em.flush();
        em.createNativeQuery(
                        "insert into user_excluded_products(user_id,product_id) select :u,id from"
                                + " products where id<>:p")
                .setParameter("u", owner.getId())
                .setParameter("p", product.getId())
                .executeUpdate();
    }

    Options options(int days) {
        return new Options(
                from,
                from.plusDays(days - 1),
                new BigDecimal("2000"),
                List.of(chain.getId()),
                "Тула",
                null,
                null,
                null,
                null,
                true,
                null,
                0L);
    }

    @Test
    void persistsReloadsAndAtomicallySavesWholeWeekIdempotently() throws Exception {
        var generated = service.create(owner.getEmail(), options(7));
        assertEquals(21, generated.result().entries().size());
        assertEquals(BudgetStatus.WITHIN, generated.result().shopping().budgetStatus());
        UUID id = generated.id();
        em.flush();
        em.clear();
        var restored = service.get(owner.getEmail(), id);
        assertEquals(7, restored.result().days().size());
        var key = UUID.randomUUID();
        var saved = saves.commit(owner.getEmail(), id, key, new Commit(restored.revision(), true));
        assertEquals(7, saved.savedPlanIds().size());
        var duplicate =
                saves.commit(owner.getEmail(), id, key, new Commit(restored.revision(), true));
        assertEquals(saved.savedPlanIds(), duplicate.savedPlanIds());
        assertEquals(
                7,
                plans.findByUser_IdAndPlanDateBetweenOrderByPlanDateAsc(
                                owner.getId(), from, from.plusDays(6))
                        .size());
    }

    @Test
    void replacementLockAndStaleRevisionAreHandled() {
        var view = service.create(owner.getEmail(), options(1));
        var entry = view.result().entries().getFirst();
        var alternatives = service.alternatives(owner.getEmail(), view.id(), entry.id());
        assertFalse(alternatives.isEmpty());
        var alternative = alternatives.getFirst();
        var changed =
                service.replace(
                        owner.getEmail(),
                        view.id(),
                        entry.id(),
                        new Replacement(
                                view.revision(), alternative.recipeId(), alternative.portions()));
        assertTrue(changed.revision() > view.revision());
        assertEquals(
                alternative.recipeId(), changed.result().entries().getFirst().recipe().recipeId());
        var conflict =
                assertThrows(
                        PlanningException.class,
                        () ->
                                service.lock(
                                        owner.getEmail(),
                                        view.id(),
                                        entry.id(),
                                        new Lock(view.revision(), true)));
        assertEquals("GENERATION_REVISION_CONFLICT", conflict.code());
        var locked =
                service.lock(
                        owner.getEmail(),
                        view.id(),
                        entry.id(),
                        new Lock(changed.revision(), true));
        var regenerated = service.regenerate(owner.getEmail(), view.id(), locked.revision());
        assertTrue(
                regenerated.result().entries().stream()
                        .anyMatch(
                                e ->
                                        e.id().equals(entry.id())
                                                && e.locked()
                                                && e.recipe()
                                                        .recipeId()
                                                        .equals(alternative.recipeId())));
    }

    @Test
    void commitDoesNotOverwriteDatesAndRollsBackWholePeriod() {
        var view = service.create(owner.getEmail(), options(3));
        em.persist(new MealPlan(owner, from.plusDays(1)));
        em.flush();
        var conflict =
                assertThrows(
                        PlanningException.class,
                        () ->
                                saves.commit(
                                        owner.getEmail(),
                                        view.id(),
                                        UUID.randomUUID(),
                                        new Commit(view.revision(), true)));
        assertEquals("MEAL_PLAN_DATE_CONFLICT", conflict.code());
        assertEquals(
                1,
                plans.findByUser_IdAndPlanDateBetweenOrderByPlanDateAsc(
                                owner.getId(), from, from.plusDays(2))
                        .size());
    }

    @Test
    void changedPriceRequiresRefreshButDoesNotChangeSelectedMeals() {
        var view = service.create(owner.getEmail(), options(1));
        em.persist(
                new PriceObservation(
                        storeProduct,
                        new BigDecimal("120.00"),
                        Instant.now(),
                        PriceSource.MANUAL,
                        false));
        em.flush();
        var conflict =
                assertThrows(
                        PlanningException.class,
                        () ->
                                saves.commit(
                                        owner.getEmail(),
                                        view.id(),
                                        UUID.randomUUID(),
                                        new Commit(view.revision(), true)));
        assertEquals("SHOPPING_ESTIMATE_CHANGED", conflict.code());
        var fresh = service.refresh(owner.getEmail(), view.id(), view.revision());
        assertEquals(view.result().entries(), fresh.result().entries());
        assertEquals(new BigDecimal("240.00"), fresh.result().shopping().knownPurchaseCost());
    }

    @Test
    void ownershipCsrfAndInputValidationAreEnforced() throws Exception {
        var view = service.create(owner.getEmail(), options(1));
        mvc.perform(
                        get("/api/me/meal-plan-generations/{id}", view.id())
                                .with(user("another@example.invalid").roles("CATALOG_EDITOR")))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/me/meal-plan-generations")
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(options(1))))
                .andExpect(status().isForbidden());
        var invalid =
                new Options(
                        from,
                        from.plusDays(14),
                        null,
                        List.of(chain.getId()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        0L);
        mvc.perform(
                        post("/api/me/meal-plan-generations")
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAllergenDataIsNotAssumedSafe() {
        var allergenId =
                ((Number)
                                em.createNativeQuery(
                                                "insert into allergens(code,name)"
                                                    + " values(:c,'Тестовый аллерген') returning"
                                                    + " id")
                                        .setParameter("c", "period-" + UUID.randomUUID())
                                        .getSingleResult())
                        .longValue();
        em.createNativeQuery("insert into user_allergies(user_id,allergen_id) values(:u,:a)")
                .setParameter("u", owner.getId())
                .setParameter("a", allergenId)
                .executeUpdate();
        var blocked = service.create(owner.getEmail(), options(1));
        assertEquals("NO_FEASIBLE_FOUND", blocked.result().status());
        assertTrue(
                blocked.result().issues().stream()
                        .anyMatch(i -> i.code().equals("ALLERGEN_DATA_INCOMPLETE")));
        em.createNativeQuery("update products set allergen_data_complete=true where id=:id")
                .setParameter("id", product.getId())
                .executeUpdate();
        var unblocked = service.create(owner.getEmail(), options(1));
        assertEquals(3, unblocked.result().entries().size());
    }

    @Test
    void changedGoalAndExpiredPreviewCannotBeSaved() {
        var view = service.create(owner.getEmail(), options(1));
        em.find(UserProfile.class, owner.getId()).updateDailyCalorieTarget(2100);
        em.flush();
        var conflict =
                assertThrows(
                        PlanningException.class,
                        () ->
                                saves.commit(
                                        owner.getEmail(),
                                        view.id(),
                                        UUID.randomUUID(),
                                        new Commit(view.revision(), true)));
        assertEquals("PLANNING_CONTEXT_CHANGED", conflict.code());
    }

    @Test
    void httpCreateReadReplaceAndCommit() throws Exception {
        String body =
                mvc.perform(
                                post("/api/me/meal-plan-generations")
                                        .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(mapper.writeValueAsString(options(1))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.result.days.length()").value(1))
                        .andExpect(
                                jsonPath("$.result.shopping.priceQuality")
                                        .value("COMPLETE_OBSERVED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var view = mapper.readValue(body, View.class);
        mvc.perform(
                        post("/api/me/meal-plan-generations/{id}/commit", view.id())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                new Commit(view.revision(), true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedPlanIds.length()").value(1));
    }

    @Test
    void expiredPreviewsReturnGone() throws Exception {
        var view = service.create(owner.getEmail(), options(1));
        em.createNativeQuery(
                        "update meal_plan_generations set created_at=now()-interval '3"
                                + " hours',expires_at=now()-interval '1 hour' where id=:id")
                .setParameter("id", view.id())
                .executeUpdate();
        em.clear();
        mvc.perform(
                        get("/api/me/meal-plan-generations/{id}", view.id())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GENERATION_EXPIRED"));
    }

    @Test
    void oldManualPriceAndPromotionalPriceAreNotTrustedAsRegularPrices() {
        em.createNativeQuery(
                        "update price_observations set observed_at=now()-interval '30 days' where"
                                + " store_product_id=:id")
                .setParameter("id", storeProduct.getId())
                .executeUpdate();
        em.persist(
                new PriceObservation(
                        storeProduct,
                        new BigDecimal("1.00"),
                        Instant.now(),
                        PriceSource.MANUAL,
                        true));
        em.flush();
        var result = service.create(owner.getEmail(), options(1)).result();
        assertEquals(PriceQuality.PARTIAL, result.shopping().priceQuality());
        assertEquals(BudgetStatus.UNKNOWN, result.shopping().budgetStatus());
        assertNull(result.shopping().estimatedTotal());
    }

    @Test
    void catalogueMaintenanceValidatesPortionsAndAllergenEvidence() throws Exception {
        var view = service.create(owner.getEmail(), options(1));
        var recipe = view.result().entries().getFirst().recipe().recipeId();
        mvc.perform(
                        put("/api/recipes/{id}/planning", recipe)
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"minPortions\":2,\"maxPortions\":1,\"portionStep\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        put("/api/products/{id}/planning", product.getId())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"allergenDataComplete\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        put("/api/products/{id}/planning", product.getId())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"allergenDataComplete\":true,\"verificationNote\":\"Проверена"
                                            + " маркировка изготовителя\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void personalPreferencesAreEditableValidatedAndInvalidateOldPreview() throws Exception {
        var view = service.create(owner.getEmail(), options(1));
        mvc.perform(
                        get("/api/planning-restrictions")
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergens").isArray())
                .andExpect(jsonPath("$.equipment").isArray());
        String preferences =
                mvc.perform(
                                get("/api/me/planning-preferences")
                                        .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var current =
                mapper.readValue(preferences, PlanningPreferencesController.PreferencesView.class);
        var exclusions = new ArrayList<>(current.values().excludedProductIds());
        exclusions.add(product.getId());
        var changed =
                new PlanningPreferencesController.Preferences(
                        current.values().allergenIds(),
                        exclusions,
                        current.values().equipmentIds());
        mvc.perform(
                        put("/api/me/planning-preferences")
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(changed)))
                .andExpect(status().isOk());
        var conflict =
                assertThrows(
                        PlanningException.class,
                        () -> service.refresh(owner.getEmail(), view.id(), view.revision()));
        assertEquals("PLANNING_CONTEXT_CHANGED", conflict.code());
        mvc.perform(
                        put("/api/me/planning-preferences")
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"allergenIds\":[],\"excludedProductIds\":[9223372036854775807],\"equipmentIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pantryChangeRequiresRefreshAndDraftCommitDoesNotReserveOrConsume() {
        var generated = service.create(owner.getEmail(), options(1));
        pantry.addLot(
                owner.getEmail(),
                UUID.randomUUID(),
                new dev.jeefox.smartmeal.pantry.PantryModels.AddLot(
                        0, product.getId(), new BigDecimal("300"), pantry.today(), null));
        var conflict =
                assertThrows(
                        PlanningException.class,
                        () ->
                                saves.commit(
                                        owner.getEmail(),
                                        generated.id(),
                                        UUID.randomUUID(),
                                        new Commit(generated.revision(), true)));
        assertEquals("PANTRY_CHANGED", conflict.code());
        var refreshed = service.refresh(owner.getEmail(), generated.id(), generated.revision());
        assertEquals(1L, refreshed.result().pantryRevision());
        var saved =
                saves.commit(
                        owner.getEmail(),
                        refreshed.id(),
                        UUID.randomUUID(),
                        new Commit(refreshed.revision(), true));
        assertEquals(1, saved.savedPlanIds().size());
        var lot = pantry.view(owner.getEmail()).lots().getFirst();
        assertEquals(0, new BigDecimal("300").compareTo(lot.quantityOnHandG()));
        assertEquals(0, lot.reservedG().signum());
    }

    @Test
    void replacementPreviewIsReadOnlyAndChangesExactlyOneMeal() {
        var v = service.create(owner.getEmail(), options(2));
        var e = v.result().entries().getFirst();
        var a = service.alternatives(owner.getEmail(), v.id(), e.id()).getFirst();
        assertNotNull(a.comparisonKey());
        assertNotNull(a.impact());
        assertTrue(a.goals().contains(ReplacementGoal.SAME_PRODUCTS));
        assertEquals(v.revision(), service.get(owner.getEmail(), v.id()).revision());
        var changed =
                service.replace(
                        owner.getEmail(),
                        v.id(),
                        e.id(),
                        new Replacement(
                                v.revision(),
                                a.recipeId(),
                                a.portions(),
                                a.candidateKey(),
                                null,
                                a.comparisonKey()));
        assertEquals(a.newPurchaseCost(), changed.result().shopping().estimatedTotal());
        for (var unchanged : v.result().entries())
            if (!unchanged.id().equals(e.id())) {
                var other =
                        changed.result().entries().stream()
                                .filter(x -> x.id().equals(unchanged.id()))
                                .findFirst()
                                .orElseThrow();
                assertEquals(unchanged.recipeFingerprint(), other.recipeFingerprint());
                assertEquals(unchanged.portions(), other.portions());
            }
    }

    @Test
    void changedStockRequiresReviewBeforeAReplacementIsApplied() {
        var v = service.create(owner.getEmail(), options(1));
        var e = v.result().entries().getFirst();
        var a = service.alternatives(owner.getEmail(), v.id(), e.id()).getFirst();
        pantry.addLot(
                owner.getEmail(),
                UUID.randomUUID(),
                new dev.jeefox.smartmeal.pantry.PantryModels.AddLot(
                        pantry.view(owner.getEmail()).revision(),
                        product.getId(),
                        BigDecimal.TEN,
                        pantry.today(),
                        from.plusDays(7)));
        var error =
                assertThrows(
                        PlanningException.class,
                        () ->
                                service.replace(
                                        owner.getEmail(),
                                        v.id(),
                                        e.id(),
                                        new Replacement(
                                                v.revision(),
                                                a.recipeId(),
                                                a.portions(),
                                                a.candidateKey(),
                                                null,
                                                a.comparisonKey())));
        assertEquals("REPLACEMENT_COMPARISON_CHANGED", error.code());
        assertEquals(v.revision(), service.get(owner.getEmail(), v.id()).revision());
    }

    @Test
    void savedMenusDoNotOfferReplacementOfPotentiallyPreparedFood() {
        var v = service.create(owner.getEmail(), options(1));
        saves.commit(owner.getEmail(), v.id(), UUID.randomUUID(), new Commit(v.revision(), true));
        var error =
                assertThrows(
                        PlanningException.class,
                        () ->
                                service.alternatives(
                                        owner.getEmail(),
                                        v.id(),
                                        v.result().entries().getFirst().id()));
        assertEquals("GENERATION_ALREADY_SAVED", error.code());
    }

    @Test
    void changedPricesRequireReviewRatherThanApplyingAnOldEstimate() {
        var v = service.create(owner.getEmail(), options(1));
        var e = v.result().entries().getFirst();
        var a = service.alternatives(owner.getEmail(), v.id(), e.id()).getFirst();
        em.persist(
                new PriceObservation(
                        storeProduct,
                        new BigDecimal("500"),
                        Instant.now(),
                        PriceSource.MANUAL,
                        false));
        em.flush();
        var error =
                assertThrows(
                        PlanningException.class,
                        () ->
                                service.replace(
                                        owner.getEmail(),
                                        v.id(),
                                        e.id(),
                                        new Replacement(
                                                v.revision(),
                                                a.recipeId(),
                                                a.portions(),
                                                a.candidateKey(),
                                                null,
                                                a.comparisonKey())));
        assertEquals("REPLACEMENT_COMPARISON_CHANGED", error.code());
        assertEquals(v.revision(), service.get(owner.getEmail(), v.id()).revision());
    }

    @Test
    void aFasterCheaperDishReportsTheAddedProductAndWholeBasketSaving() {
        em.createNativeQuery("update store_products set package_weight_g=100 where id=:id")
                .setParameter("id", storeProduct.getId())
                .executeUpdate();
        em.refresh(storeProduct);
        var cheaperProduct =
                new Product(
                        product.getCategory(),
                        "Другой продукт",
                        null,
                        100,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE);
        em.persist(cheaperProduct);
        var cheapRecipe = new Recipe("Быстрый вариант", null, "Приготовить", 5, 1);
        cheapRecipe.changeMealTypes(Set.of(MealType.BREAKFAST));
        em.persist(cheapRecipe);
        em.persist(new RecipeIngredient(cheapRecipe, cheaperProduct, new BigDecimal("500"), 1));
        var cheapSku =
                new StoreProduct(storeProduct.getStore(), cheaperProduct, "Пачка 100 г", 100);
        em.persist(cheapSku);
        em.persist(
                new PriceObservation(
                        cheapSku,
                        BigDecimal.ONE,
                        Instant.now().minusSeconds(1),
                        PriceSource.MANUAL,
                        false));
        em.flush();
        var v = service.create(owner.getEmail(), options(1));
        var costly =
                em.createQuery("select r from Recipe r where r.name=:n", Recipe.class)
                        .setParameter("n", "Тест BREAKFAST 0")
                        .getSingleResult();
        var slot =
                v.result().entries().stream()
                        .filter(e -> e.mealType() == MealType.BREAKFAST)
                        .findFirst()
                        .orElseThrow();
        if (!slot.recipe().recipeId().equals(costly.getId()))
            v =
                    service.replace(
                            owner.getEmail(),
                            v.id(),
                            slot.id(),
                            new Replacement(v.revision(), costly.getId(), BigDecimal.ONE));
        var a =
                service.alternatives(owner.getEmail(), v.id(), slot.id()).stream()
                        .filter(x -> x.recipeId().equals(cheapRecipe.getId()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(a.goals().contains(ReplacementGoal.CHEAPER));
        assertTrue(a.goals().contains(ReplacementGoal.FASTER));
        assertFalse(a.goals().contains(ReplacementGoal.SAME_PRODUCTS));
        assertTrue(a.deltaPurchaseCost().signum() < 0);
        assertEquals(cheaperProduct.getId(), a.impact().addedProducts().getFirst().productId());
        assertEquals(5, a.impact().minutesSaved());
        var next =
                service.replace(
                        owner.getEmail(),
                        v.id(),
                        slot.id(),
                        new Replacement(
                                v.revision(),
                                a.recipeId(),
                                a.portions(),
                                a.candidateKey(),
                                null,
                                a.comparisonKey()));
        assertEquals(a.newPurchaseCost(), next.result().shopping().estimatedTotal());
    }

    @Test
    void retryAfterALostReplacementResponseDoesNotApplyAgain() {
        var v = service.create(owner.getEmail(), options(1));
        var entry = v.result().entries().getFirst();
        var a = service.alternatives(owner.getEmail(), v.id(), entry.id()).getFirst();
        var request =
                new Replacement(
                        v.revision(),
                        a.recipeId(),
                        a.portions(),
                        a.candidateKey(),
                        null,
                        a.comparisonKey());
        var changed = service.replace(owner.getEmail(), v.id(), entry.id(), request);
        var error =
                assertThrows(
                        PlanningException.class,
                        () -> service.replace(owner.getEmail(), v.id(), entry.id(), request));
        assertEquals("GENERATION_REVISION_CONFLICT", error.code());
        assertEquals(changed.revision(), service.get(owner.getEmail(), v.id()).revision());
    }
}
