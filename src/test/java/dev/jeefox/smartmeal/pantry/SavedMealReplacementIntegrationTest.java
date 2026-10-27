package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.generation.period.*;
import dev.jeefox.smartmeal.mealplan.service.MealPlanService;
import dev.jeefox.smartmeal.price.entity.*;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.recipe.entity.*;
import dev.jeefox.smartmeal.shopping.purchase.*;
import dev.jeefox.smartmeal.shopping.service.ShoppingListService;
import dev.jeefox.smartmeal.store.entity.*;
import dev.jeefox.smartmeal.user.entity.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@SpringBootTest
@Transactional
class SavedMealReplacementIntegrationTest {
    @Autowired EntityManager em;
    @Autowired PantryService pantry;
    @Autowired SavedMealReplacementService swaps;
    @Autowired PeriodPlanningService planner;
    @Autowired GenerationCommitService saves;
    @Autowired MealPlanService plans;
    @Autowired ShoppingSessionService sessions;
    @Autowired ShoppingPurchaseService purchases;
    @Autowired ShoppingListService shopping;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired dev.jeefox.smartmeal.household.HouseholdService household;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    User user;
    Product a, b;
    Recipe expensive, other;
    RetailChain chain;
    UUID entry, plan;
    View generation;
    LocalDate today;

    BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    long revision() {
        return pantry.view(user.getEmail()).revision();
    }

    @BeforeEach
    void setup() {
        today = pantry.today();
        user = new User("saved-swap-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(user);
        em.persist(new UserProfile(2000, user));
        var cat = new ProductCategory("SWAP_" + UUID.randomUUID(), "Тест");
        em.persist(cat);
        a = new Product(cat, "Продукт A", null, 100, n("10"), n("5"), n("20"));
        b = new Product(cat, "Продукт B", null, 100, n("10"), n("5"), n("20"));
        em.persist(a);
        em.persist(b);
        for (var meal : PeriodMenuEvaluator.MEALS)
            for (int i = 0; i < 4; i++) {
                var recipe = new Recipe("Saved swap " + meal + " " + i, null, "Приготовить", 10, 1);
                recipe.changeMealTypes(Set.of(meal));
                em.persist(recipe);
                em.persist(
                        new RecipeIngredient(
                                recipe,
                                a,
                                n(
                                        meal == MealType.BREAKFAST
                                                ? "500"
                                                : meal == MealType.LUNCH ? "800" : "700"),
                                1));
                if (meal == MealType.BREAKFAST && i == 0) expensive = recipe;
            }
        other = new Recipe("Быстрое блюдо B", null, "Приготовить", 5, 1);
        other.changeMealTypes(Set.of(MealType.BREAKFAST));
        em.persist(other);
        em.persist(new RecipeIngredient(other, b, n("500"), 1));
        chain = new RetailChain("SWAP_" + UUID.randomUUID(), "Сеть");
        em.persist(chain);
        var store = new Store(chain, "Тула", "Тест");
        em.persist(store);
        for (var p : List.of(a, b)) {
            var sku = new StoreProduct(store, p, "Пачка", 1000);
            em.persist(sku);
            em.persist(
                    new PriceObservation(
                            sku,
                            n(p == a ? "100" : "200"),
                            Instant.now().minusSeconds(1),
                            PriceSource.MANUAL,
                            false));
        }
        em.flush();
        jdbc.update(
                "insert into user_excluded_products(user_id,product_id) select :u,id from products"
                    + " where id not in (:a,:b)",
                Map.of("u", user.getId(), "a", a.getId(), "b", b.getId()));
        var options =
                new Options(
                        today,
                        today.plusDays(1),
                        null,
                        List.of(chain.getId()),
                        "Тула",
                        null,
                        null,
                        14,
                        0,
                        true,
                        null,
                        0L);
        var view = planner.create(user.getEmail(), options);
        var first = view.result().entries().getFirst();
        if (!first.recipe().recipeId().equals(expensive.getId()))
            view =
                    planner.replace(
                            user.getEmail(),
                            view.id(),
                            first.id(),
                            new Replacement(view.revision(), expensive.getId(), BigDecimal.ONE));
        generation =
                saves.commit(
                        user.getEmail(),
                        view.id(),
                        UUID.randomUUID(),
                        new Commit(view.revision(), true));
        plan = generation.savedPlanIds().getFirst();
        entry =
                jdbc.queryForObject(
                        "select id from meal_plan_entries where meal_plan_id=:p and"
                            + " meal_type='BREAKFAST'",
                        Map.of("p", plan),
                        UUID.class);
    }

    SavedMealReplacementService.Apply request() {
        var p = swaps.preview(user.getEmail(), entry);
        var c =
                p.alternatives().stream()
                        .filter(x -> x.recipeId().equals(other.getId()))
                        .findFirst()
                        .orElseThrow();
        return new SavedMealReplacementService.Apply(
                p.revision(), c.recipeId(), c.portions(), c.candidateKey(), c.comparisonKey());
    }

    String recipe() {
        return jdbc.queryForObject(
                "select recipe_snapshot->>'name' from meal_plan_entries where id=:e",
                Map.of("e", entry),
                String.class);
    }

    @Test
    void changesOneSavedMealAndUndoRestoresItWithoutChangingStockOrHousehold() {
        plans.confirmMenu(user.getEmail(), plan);
        em.flush();
        pantry.addLot(
                user.getEmail(),
                UUID.randomUUID(),
                new PantryModels.AddLot(
                        revision(), a.getId(), n("6000"), today, today.plusDays(10)));
        var before =
                jdbc.queryForList(
                        "select id,recipe_snapshot::text snapshot,portions from meal_plan_entries"
                            + " where meal_plan_id in (:ids) and id<>:e order by id",
                        Map.of("ids", generation.savedPlanIds(), "e", entry));
        var household =
                jdbc.queryForObject(
                        "select participants::text from meal_plan_households where meal_plan_id=:p",
                        Map.of("p", plan),
                        String.class);
        var r = request();
        var key = UUID.randomUUID();
        var result = swaps.apply(user.getEmail(), key, entry, r);
        assertEquals(result, swaps.apply(user.getEmail(), key, entry, r));
        assertEquals(other.getName(), recipe());
        assertEquals(
                0, pantry.view(user.getEmail()).lots().getFirst().reservedG().compareTo(n("1500")));
        assertEquals(
                before,
                jdbc.queryForList(
                        "select id,recipe_snapshot::text snapshot,portions from meal_plan_entries"
                            + " where meal_plan_id in (:ids) and id<>:e order by id",
                        Map.of("ids", generation.savedPlanIds(), "e", entry)));
        assertEquals(
                household,
                jdbc.queryForObject(
                        "select participants::text from meal_plan_households where meal_plan_id=:p",
                        Map.of("p", plan),
                        String.class));
        assertEquals(
                0,
                pantry.view(user.getEmail())
                        .lots()
                        .getFirst()
                        .quantityOnHandG()
                        .compareTo(n("6000")));
        var status = swaps.status(user.getEmail(), entry);
        assertNotNull(status.undoId());
        var undoKey = UUID.randomUUID();
        var undo = new PantryModels.Command(status.revision());
        var restored = swaps.undo(user.getEmail(), undoKey, entry, status.undoId(), undo);
        assertEquals(restored, swaps.undo(user.getEmail(), undoKey, entry, status.undoId(), undo));
        assertEquals(expensive.getName(), recipe());
        assertEquals(
                0, pantry.view(user.getEmail()).lots().getFirst().reservedG().compareTo(n("2000")));
        assertEquals(
                0,
                pantry.view(user.getEmail())
                        .lots()
                        .getFirst()
                        .quantityOnHandG()
                        .compareTo(n("6000")));
    }

    @Test
    void boughtGoodsAndPaymentHistoryRemainWhilePersistentShoppingListIsUpdated() {
        var session =
                sessions.open(
                        user.getEmail(),
                        UUID.randomUUID(),
                        new ShoppingPurchaseModels.Open(generation.id()));
        var item =
                session.items().stream()
                        .filter(i -> i.snapshot().productId().equals(a.getId()))
                        .findFirst()
                        .orElseThrow();
        session =
                purchases.purchase(
                        user.getEmail(),
                        session.id(),
                        UUID.randomUUID(),
                        new ShoppingPurchaseModels.Purchase(
                                session.revision(),
                                session.pantryRevision(),
                                List.of(
                                        new ShoppingPurchaseModels.Line(
                                                item.id(),
                                                n("5000"),
                                                n("500"),
                                                null,
                                                today,
                                                today.plusDays(10)))));
        var price = session.knownActualTotalRub();
        var paidRows =
                jdbc.queryForObject(
                        "select count(*) from shopping_purchase_lines where item_id=:i",
                        Map.of("i", item.id()),
                        Integer.class);
        var action = swaps.apply(user.getEmail(), UUID.randomUUID(), entry, request());
        var after = sessions.get(user.getEmail(), session.id());
        assertEquals(price, after.knownActualTotalRub());
        assertEquals(
                paidRows,
                jdbc.queryForObject(
                        "select count(*) from shopping_purchase_lines where item_id=:i",
                        Map.of("i", item.id()),
                        Integer.class));
        assertTrue(
                after.items().stream()
                        .anyMatch(
                                i ->
                                        i.snapshot().productId().equals(b.getId())
                                                && i.remainingG().signum() > 0));
        assertEquals(
                0,
                pantry.view(user.getEmail())
                        .lots()
                        .getFirst()
                        .quantityOnHandG()
                        .compareTo(n("5000")));
        swaps.undo(
                user.getEmail(),
                UUID.randomUUID(),
                entry,
                action.operationId(),
                new PantryModels.Command(revision()));
        var undone = sessions.get(user.getEmail(), session.id());
        assertTrue(
                undone.items().stream()
                        .filter(i -> i.snapshot().productId().equals(b.getId()))
                        .allMatch(i -> i.remainingG().signum() == 0));
        assertEquals(price, undone.knownActualTotalRub());
    }

    @Test
    void aShoppingSessionOpenedAfterReplacementUsesTheCurrentSavedMeals() {
        swaps.apply(user.getEmail(), UUID.randomUUID(), entry, request());
        var session =
                sessions.open(
                        user.getEmail(),
                        UUID.randomUUID(),
                        new ShoppingPurchaseModels.Open(generation.id()));
        assertTrue(
                session.items().stream().anyMatch(i -> i.snapshot().productId().equals(b.getId())));
    }

    @Test
    void preparedAndSkippedMealsCannotBeReplacedOrUndone() {
        var action = swaps.apply(user.getEmail(), UUID.randomUUID(), entry, request());
        plans.confirmMenu(user.getEmail(), plan);
        em.flush();
        pantry.addLot(
                user.getEmail(),
                UUID.randomUUID(),
                new PantryModels.AddLot(
                        revision(), b.getId(), n("2000"), today, today.plusDays(10)));
        pantry.usage(
                user.getEmail(), UUID.randomUUID(), entry, new PantryModels.Command(revision()));
        assertFalse(swaps.status(user.getEmail(), entry).canReplace());
        assertThrows(
                PantryException.class,
                () ->
                        swaps.undo(
                                user.getEmail(),
                                UUID.randomUUID(),
                                entry,
                                action.operationId(),
                                new PantryModels.Command(revision())));
        assertThrows(PantryException.class, () -> swaps.preview(user.getEmail(), entry));
    }

    @Test
    void staleStockOrPriceAndOtherOwnersCannotUseAnOldOffer() {
        var r = request();
        pantry.addLot(
                user.getEmail(),
                UUID.randomUUID(),
                new PantryModels.AddLot(revision(), a.getId(), n("100"), today, today.plusDays(3)));
        assertThrows(
                PantryException.class,
                () -> swaps.apply(user.getEmail(), UUID.randomUUID(), entry, r));
        assertEquals(expensive.getName(), recipe());
        var stranger = new User("swap-other-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(stranger);
        em.flush();
        assertThrows(PantryException.class, () -> swaps.status(stranger.getEmail(), entry));
        assertThrows(PantryException.class, () -> swaps.preview(stranger.getEmail(), entry));
    }

    @Test
    void manualEditAfterReplacementIsNotOverwrittenByUndo() {
        var action = swaps.apply(user.getEmail(), UUID.randomUUID(), entry, request());
        jdbc.update(
                "update meal_plan_entries set portions=portions+0.25 where id=:e",
                Map.of("e", entry));
        assertNull(swaps.status(user.getEmail(), entry).undoId());
        assertThrows(
                PantryException.class,
                () ->
                        swaps.undo(
                                user.getEmail(),
                                UUID.randomUUID(),
                                entry,
                                action.operationId(),
                                new PantryModels.Command(revision())));
    }

    @Test
    void undoNeverRestoresARecipeThatIsNowExcluded() {
        var action = swaps.apply(user.getEmail(), UUID.randomUUID(), entry, request());
        jdbc.update(
                "insert into user_excluded_products(user_id,product_id) values(:u,:p)",
                Map.of("u", user.getId(), "p", a.getId()));
        var error =
                assertThrows(
                        PantryException.class,
                        () ->
                                swaps.undo(
                                        user.getEmail(),
                                        UUID.randomUUID(),
                                        entry,
                                        action.operationId(),
                                        new PantryModels.Command(revision())));
        assertEquals("UNDO_RECIPE_CHANGED", error.code());
        assertEquals(other.getName(), recipe());
    }

    @Test
    void savedPersonalCalorieTargetsRemainEvenIfCurrentProfileTargetsChange() {
        var member =
                household.create(
                        user.getEmail(),
                        new dev.jeefox.smartmeal.household.HouseholdModels.MemberRequest(
                                "Мама", 1500, List.of(), List.of()));
        var people =
                List.of(
                        new dev.jeefox.smartmeal.household.HouseholdModels.Participant(
                                user.getId(), "Вы", 2000, true),
                        new dev.jeefox.smartmeal.household.HouseholdModels.Participant(
                                member.id(), "Мама", 1500, false));
        jdbc.update(
                "update meal_plan_households set participants=cast(:p as jsonb) where meal_plan_id"
                    + " in (:ids)",
                Map.of("p", json.writeValueAsString(people), "ids", generation.savedPlanIds()));
        jdbc.update(
                "update meal_plan_entries set portions=portions*1.75 where meal_plan_id in (:ids)",
                Map.of("ids", generation.savedPlanIds()));
        jdbc.update(
                "update user_profiles set daily_calorie_target=2400 where user_id=:u",
                Map.of("u", user.getId()));
        jdbc.update(
                "update household_members set daily_calorie_target=1800 where id=:m",
                Map.of("m", member.id()));
        em.clear();
        var preview = swaps.preview(user.getEmail(), entry);
        var choice =
                preview.alternatives().stream()
                        .filter(c -> c.recipeId().equals(other.getId()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, choice.impact().personPortions().size());
        var result =
                swaps.apply(
                        user.getEmail(),
                        UUID.randomUUID(),
                        entry,
                        new SavedMealReplacementService.Apply(
                                preview.revision(),
                                choice.recipeId(),
                                choice.portions(),
                                choice.candidateKey(),
                                choice.comparisonKey()));
        var after = plans.findByDate(user.getEmail(), today);
        assertEquals(
                List.of(2000, 1500),
                after.participants().stream()
                        .map(
                                dev.jeefox.smartmeal.household.HouseholdModels.Participant
                                        ::dailyCalorieTarget)
                        .toList());
        assertEquals(other.getName(), recipe());
        assertNotNull(result.operationId());
    }

    @Test
    void changedPricesAndOtherDeviceMenuEditsRequireFreshPreview() {
        var r = request();
        var sku =
                em.createQuery(
                                "select s from StoreProduct s where s.product.id=:p",
                                StoreProduct.class)
                        .setParameter("p", b.getId())
                        .getSingleResult();
        em.persist(new PriceObservation(sku, n("999"), Instant.now(), PriceSource.MANUAL, false));
        em.flush();
        var error =
                assertThrows(
                        PantryException.class,
                        () -> swaps.apply(user.getEmail(), UUID.randomUUID(), entry, r));
        assertEquals("REPLACEMENT_COMPARISON_CHANGED", error.code());
        assertEquals(expensive.getName(), recipe());
    }

    @Test
    void replacementApiRequiresCsrfAndValidatesInput() throws Exception {
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                        .apply(
                                org.springframework.security.test.web.servlet.setup
                                        .SecurityMockMvcConfigurers.springSecurity())
                        .build();
        var path = "/api/me/meal-plan-entries/" + entry + "/replacements";
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                user.getEmail()))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isForbidden());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                user.getEmail()))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                user.getEmail())))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.alternatives")
                                .isArray());
    }

    @Test
    void alreadyCookedOtherMealsAreExcludedFromTheRemainingPurchaseComparison() {
        plans.confirmMenu(user.getEmail(), plan);
        em.flush();
        pantry.addLot(
                user.getEmail(),
                UUID.randomUUID(),
                new PantryModels.AddLot(
                        revision(), a.getId(), n("4000"), today, today.plusDays(10)));
        var lunch =
                jdbc.queryForObject(
                        "select id from meal_plan_entries where meal_plan_id=:p and"
                            + " meal_type='LUNCH'",
                        Map.of("p", plan),
                        UUID.class);
        pantry.usage(
                user.getEmail(), UUID.randomUUID(), lunch, new PantryModels.Command(revision()));
        var choice =
                swaps.preview(user.getEmail(), entry).alternatives().stream()
                        .filter(c -> c.recipeId().equals(other.getId()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(0, choice.impact().previousPurchaseCost().signum());
        assertEquals(0, n("200").compareTo(choice.deltaPurchaseCost()));
        assertEquals(
                0,
                pantry.view(user.getEmail())
                        .lots()
                        .getFirst()
                        .quantityOnHandG()
                        .compareTo(n("3200")));
    }
}
