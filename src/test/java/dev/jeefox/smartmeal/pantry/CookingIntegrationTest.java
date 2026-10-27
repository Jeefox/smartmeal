package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.*;
import static dev.jeefox.smartmeal.pantry.CookingModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.balanced.CompositeMeals;
import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.recipe.entity.*;
import dev.jeefox.smartmeal.shopping.service.ShoppingListService;
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
class CookingIntegrationTest {
    @Autowired org.springframework.web.context.WebApplicationContext web;
    @Autowired EntityManager em;
    @Autowired CookingService cooking;
    @Autowired SavedMealReplacementService swaps;
    @Autowired CookingCorrections corrections;
    @Autowired PantryService pantry;
    @Autowired ShoppingListService shopping;
    @Autowired NamedParameterJdbcTemplate jdbc;
    User user;
    Product rice, chicken;
    Recipe riceRecipe, chickenRecipe;
    UUID todayEntry, tomorrowEntry;
    String sideId;
    LocalDate today;

    BigDecimal g(String s) {
        return new BigDecimal(s);
    }

    long revision() {
        return pantry.view(user.getEmail()).revision();
    }

    CookingModels.View view() {
        return cooking.view(user.getEmail(), today, today.plusDays(6));
    }

    BigDecimal balance(Product p) {
        return pantry.view(user.getEmail()).lots().stream()
                .filter(l -> l.productId().equals(p.getId()))
                .map(PantryModels.Lot::quantityOnHandG)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @BeforeEach
    void setup() {
        today = pantry.today();
        user = new User("cooking-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(user);
        em.persist(new UserProfile(2000, user));
        var category = new ProductCategory("COOK_" + UUID.randomUUID(), "Тест готовки");
        em.persist(category);
        rice = new Product(category, "Рис", null, 350, g("7"), g("2"), g("70"));
        chicken = new Product(category, "Курица", null, 150, g("20"), g("5"), g("0"));
        em.persist(rice);
        em.persist(chicken);
        riceRecipe = new Recipe("Рис отварной", null, "Сварить рис", 10, 1);
        chickenRecipe = new Recipe("Курица", null, "Приготовить курицу", 15, 1);
        em.persist(riceRecipe);
        em.persist(chickenRecipe);
        var ri = new RecipeIngredient(riceRecipe, rice, g("100"), 1);
        var ci = new RecipeIngredient(chickenRecipe, chicken, g("100"), 1);
        em.persist(ri);
        em.persist(ci);
        em.flush();
        var rs = RecipeSnapshotFactory.create(riceRecipe, List.of(ri));
        var cs = RecipeSnapshotFactory.create(chickenRecipe, List.of(ci));
        sideId = "SIDE:" + riceRecipe.getId();
        var riceComponent =
                new Component(
                        sideId,
                        Role.SIDE,
                        rs,
                        new Profile(
                                Role.SIDE,
                                g("200"),
                                YieldQuality.ESTIMATED,
                                "Тест",
                                g("100"),
                                g("300"),
                                g("25"),
                                true,
                                false),
                        List.of(new Amount(user.getId(), BigDecimal.ONE, g("200"))),
                        false);
        var meat =
                new Component(
                        "MAIN:" + chickenRecipe.getId(),
                        Role.PROTEIN_MAIN,
                        cs,
                        new Profile(
                                Role.PROTEIN_MAIN,
                                g("100"),
                                YieldQuality.ESTIMATED,
                                "Тест",
                                g("50"),
                                g("150"),
                                g("25"),
                                false,
                                false),
                        List.of(new Amount(user.getId(), BigDecimal.ONE, g("100"))),
                        false);
        var snapshot = CompositeMeals.aggregate(List.of(meat, riceComponent));
        var p1 = new MealPlan(user, today);
        var p2 = new MealPlan(user, today.plusDays(1));
        em.persist(p1);
        em.persist(p2);
        p1.confirm(2000);
        p2.confirm(2000);
        var e1 = new MealPlanEntry(p1, chickenRecipe, MealType.DINNER, BigDecimal.ONE, 1, snapshot);
        var e2 = new MealPlanEntry(p2, chickenRecipe, MealType.DINNER, BigDecimal.ONE, 1, snapshot);
        em.persist(e1);
        em.persist(e2);
        em.flush();
        todayEntry = e1.getId();
        tomorrowEntry = e2.getId();
        pantry.addLot(
                user.getEmail(),
                UUID.randomUUID(),
                new PantryModels.AddLot(
                        revision(), rice.getId(), g("1000"), today, today.plusDays(10)));
        pantry.addLot(
                user.getEmail(),
                UUID.randomUUID(),
                new PantryModels.AddLot(
                        revision(), chicken.getId(), g("1000"), today, today.plusDays(10)));
    }

    UUID create() {
        cooking.create(
                user.getEmail(),
                UUID.randomUUID(),
                new Create(
                        revision(),
                        today,
                        today.plusDays(1),
                        List.of(new Ref(todayEntry, sideId), new Ref(tomorrowEntry, sideId))));
        return view().batches().getFirst().id();
    }

    @Test
    void cookingOnceCoversDifferentDaysAndConsumptionDoesNotSpendRawAgain() {
        UUID batch = create();
        assertEquals(0, balance(rice).compareTo(g("1000")));
        UUID key = UUID.randomUUID();
        var r = new Prepare(revision(), g("420"));
        var first = cooking.prepare(user.getEmail(), key, batch, r);
        assertEquals(first, cooking.prepare(user.getEmail(), key, batch, r));
        assertEquals(0, balance(rice).compareTo(g("800")));
        assertEquals(0, balance(chicken).compareTo(g("1000")));
        var future = shopping.calculate(user.getEmail(), today.plusDays(1), today.plusDays(1));
        assertTrue(future.items().stream().noneMatch(i -> i.productId().equals(rice.getId())));
        pantry.usage(
                user.getEmail(),
                UUID.randomUUID(),
                todayEntry,
                new PantryModels.Command(revision()));
        assertEquals(0, balance(rice).compareTo(g("800")));
        assertEquals(0, balance(chicken).compareTo(g("900")));
        cooking.consume(
                user.getEmail(),
                UUID.randomUUID(),
                todayEntry,
                new PantryModels.Command(revision()));
        assertEquals(0, view().batches().getFirst().remainingG().compareTo(g("210")));
        assertThrows(
                PantryException.class,
                () ->
                        pantry.reverse(
                                user.getEmail(),
                                UUID.randomUUID(),
                                todayEntry,
                                new PantryModels.Command(revision())));
    }

    @Test
    void plannedBatchRequiresItsOwnPreparationAndReservesEarlier() {
        create();
        assertThrows(
                PantryException.class,
                () ->
                        pantry.usage(
                                user.getEmail(),
                                UUID.randomUUID(),
                                todayEntry,
                                new PantryModels.Command(revision())));
    }

    @Test
    void shortageLeavesWholeBatchUnpreparedAndBalancesUntouched() {
        var batch = create();
        jdbc.update(
                "update pantry_lots set quantity_on_hand_g=50 where user_id=:u and product_id=:p",
                Map.of("u", user.getId(), "p", rice.getId()));
        assertThrows(
                PantryException.class,
                () ->
                        cooking.prepare(
                                user.getEmail(),
                                UUID.randomUUID(),
                                batch,
                                new Prepare(revision(), g("400"))));
        assertEquals(0, balance(rice).compareTo(g("50")));
        assertEquals("PLANNED", view().batches().getFirst().status());
    }

    @Test
    void cancellingPreparedMealReleasesFoodForAnotherMatchingMeal() {
        var batch = create();
        cooking.prepare(
                user.getEmail(), UUID.randomUUID(), batch, new Prepare(revision(), g("400")));
        pantry.state(
                user.getEmail(),
                UUID.randomUUID(),
                tomorrowEntry,
                new PantryModels.StateCommand(revision(), PantryModels.State.SKIPPED));
        assertEquals(0, view().batches().getFirst().freeG().compareTo(g("200")));
        pantry.state(
                user.getEmail(),
                UUID.randomUUID(),
                tomorrowEntry,
                new PantryModels.StateCommand(revision(), PantryModels.State.OPEN));
        cooking.assign(
                user.getEmail(),
                UUID.randomUUID(),
                batch,
                new Assign(revision(), List.of(new Ref(tomorrowEntry, sideId))));
        assertEquals(0, view().batches().getFirst().freeG().signum());
        assertEquals(0, balance(rice).compareTo(g("800")));
    }

    @Test
    void discardDoesNotReturnRawIngredientsAndFutureDemandReappears() {
        var batch = create();
        cooking.prepare(
                user.getEmail(), UUID.randomUUID(), batch, new Prepare(revision(), g("400")));
        cooking.discard(
                user.getEmail(), UUID.randomUUID(), batch, new PantryModels.Command(revision()));
        assertEquals(0, balance(rice).compareTo(g("800")));
        assertTrue(
                shopping
                        .calculate(user.getEmail(), today.plusDays(1), today.plusDays(1))
                        .items()
                        .stream()
                        .anyMatch(i -> i.productId().equals(rice.getId())));
    }

    @Test
    void expiredFoodCannotBeConsumedOrAssigned() {
        var batch = create();
        cooking.prepare(
                user.getEmail(), UUID.randomUUID(), batch, new Prepare(revision(), g("400")));
        jdbc.update(
                "update cooking_batches set use_by=:y,cook_on=:y where id=:b",
                Map.of("y", today.minusDays(1), "b", batch));
        assertTrue(view().batches().getFirst().expired());
        assertThrows(
                PantryException.class,
                () ->
                        pantry.usage(
                                user.getEmail(),
                                UUID.randomUUID(),
                                todayEntry,
                                new PantryModels.Command(revision())));
    }

    @Test
    void noOwnershipBypassOrDuplicateAllocationOrMixedRecipes() {
        var other = new User("cooking-other-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(other);
        em.flush();
        var rev = pantry.view(other.getEmail()).revision();
        assertThrows(
                PantryException.class,
                () ->
                        cooking.create(
                                other.getEmail(),
                                UUID.randomUUID(),
                                new Create(
                                        rev,
                                        today,
                                        today.plusDays(1),
                                        List.of(new Ref(todayEntry, sideId)))));
        assertThrows(
                PantryException.class,
                () ->
                        cooking.create(
                                user.getEmail(),
                                UUID.randomUUID(),
                                new Create(
                                        revision(),
                                        today,
                                        today.plusDays(1),
                                        List.of(
                                                new Ref(todayEntry, sideId),
                                                new Ref(
                                                        todayEntry,
                                                        "MAIN:" + chickenRecipe.getId())))));
    }

    @Test
    void actualWeightDoesNotChangeSnapshotNutrition() {
        var before =
                jdbc.queryForObject(
                        "select recipe_snapshot::text from meal_plan_entries where id=:e",
                        Map.of("e", todayEntry),
                        String.class);
        var batch = create();
        cooking.prepare(
                user.getEmail(), UUID.randomUUID(), batch, new Prepare(revision(), g("300")));
        assertEquals(
                before,
                jdbc.queryForObject(
                        "select recipe_snapshot::text from meal_plan_entries where id=:e",
                        Map.of("e", todayEntry),
                        String.class));
        assertEquals(
                0,
                view().batches().getFirst().allocations().getFirst().cookedG().compareTo(g("150")));
    }

    @Test
    void batchReservesWholeComponentAtCookingDateNotAtFutureServingDate() {
        jdbc.update(
                "update pantry_lots set expires_on=:t where user_id=:u and product_id=:p",
                Map.of("t", today, "u", user.getId(), "p", rice.getId()));
        create();
        var lot =
                pantry.view(user.getEmail()).lots().stream()
                        .filter(l -> l.productId().equals(rice.getId()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(0, lot.reservedG().compareTo(g("200")));
        var daily = shopping.calculate(user.getEmail(), today, today);
        assertEquals(
                0,
                daily.items().stream()
                        .filter(i -> i.productId().equals(rice.getId()))
                        .findFirst()
                        .orElseThrow()
                        .requiredG()
                        .compareTo(g("200")));
        assertEquals(
                0,
                daily.items().stream()
                        .filter(i -> i.productId().equals(chicken.getId()))
                        .findFirst()
                        .orElseThrow()
                        .requiredG()
                        .compareTo(g("100")));
    }

    @Test
    void cookingDayShoppingIncludesBatchEvenWithoutMealThatDay() {
        jdbc.update(
                "update meal_plans set status='CANCELLED',cancelled_at=now() where id=(select"
                        + " meal_plan_id from meal_plan_entries where id=:e)",
                Map.of("e", todayEntry));
        em.clear();
        cooking.create(
                user.getEmail(),
                UUID.randomUUID(),
                new Create(
                        revision(),
                        today,
                        today.plusDays(1),
                        List.of(new Ref(tomorrowEntry, sideId))));
        var daily = shopping.calculate(user.getEmail(), today, today);
        assertEquals(1, daily.items().size());
        assertEquals(rice.getId(), daily.items().getFirst().productId());
        assertEquals(0, daily.items().getFirst().requiredG().compareTo(g("100")));
    }

    @Test
    void confirmWholePeriodIsExplicitAndIdempotent() {
        jdbc.update(
                "update meal_plans set"
                    + " status='DRAFT',confirmed_at=null,daily_calorie_target_snapshot=null where"
                    + " user_id=:u",
                Map.of("u", user.getId()));
        em.clear();
        assertEquals(2, view().drafts().size());
        assertTrue(view().candidates().isEmpty());
        var key = UUID.randomUUID();
        var request = new Confirm(revision(), today, today.plusDays(1));
        var result = cooking.confirm(user.getEmail(), key, request);
        assertEquals(result, cooking.confirm(user.getEmail(), key, request));
        assertTrue(view().drafts().isEmpty());
        assertEquals(4, view().candidates().size());
    }

    @Test
    void apiRequiresCsrfAndValidatesActualWeight() throws Exception {
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                        .apply(
                                org.springframework.security.test.web.servlet.setup
                                        .SecurityMockMvcConfigurers.springSecurity())
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/me/cooking-batches")
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
        var batch = create();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/me/cooking-batches/" + batch + "/prepare")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                user.getEmail()))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        "{\"expectedRevision\":"
                                                + revision()
                                                + ",\"actualYieldG\":0}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        assertEquals(0, balance(rice).compareTo(g("1000")));
    }

    UUID consumedBatch() {
        UUID b = create();
        cooking.prepare(user.getEmail(), UUID.randomUUID(), b, new Prepare(revision(), g("400")));
        pantry.usage(
                user.getEmail(),
                UUID.randomUUID(),
                todayEntry,
                new PantryModels.Command(revision()));
        cooking.consume(
                user.getEmail(),
                UUID.randomUUID(),
                todayEntry,
                new PantryModels.Command(revision()));
        return b;
    }

    @Test
    void correctedWeightIsAuditedAndIdempotentWithoutChangingRawOrNutrition() {
        var b = consumedBatch();
        var before =
                jdbc.queryForObject(
                        "select recipe_snapshot::text from meal_plan_entries where id=:e",
                        Map.of("e", todayEntry),
                        String.class);
        var key = UUID.randomUUID();
        var r = new CookingCorrections.Weight(revision(), g("600"), "Ошибка весов");
        var result = corrections.weight(user.getEmail(), key, b, r);
        assertEquals(result, corrections.weight(user.getEmail(), key, b, r));
        assertEquals(0, view().batches().getFirst().remainingG().compareTo(g("300")));
        assertEquals(0, balance(rice).compareTo(g("800")));
        assertEquals(0, balance(chicken).compareTo(g("900")));
        assertEquals(
                before,
                jdbc.queryForObject(
                        "select recipe_snapshot::text from meal_plan_entries where id=:e",
                        Map.of("e", todayEntry),
                        String.class));
        var history = corrections.history(user.getEmail(), b);
        assertEquals(1, history.size());
        assertEquals("Ошибка весов", history.getFirst().reason());
        assertEquals(
                0,
                new BigDecimal(history.getFirst().before().get("actualYieldG").toString())
                        .compareTo(g("400")));
        assertThrows(
                PantryException.class,
                () ->
                        corrections.weight(
                                user.getEmail(),
                                key,
                                b,
                                new CookingCorrections.Weight(
                                        revision(), g("700"), "Другая ошибка")));
    }

    @Test
    void undoEatenRestoresOnlyCookedFoodAndCanBeConsumedAgain() {
        var b = consumedBatch();
        var key = UUID.randomUUID();
        var r = new CookingCorrections.Reversal(revision(), "Не ел");
        var result = corrections.undoConsumption(user.getEmail(), key, todayEntry, r);
        assertEquals(result, corrections.undoConsumption(user.getEmail(), key, todayEntry, r));
        assertEquals(0, view().batches().getFirst().remainingG().compareTo(g("400")));
        assertEquals(0, balance(rice).compareTo(g("800")));
        assertEquals(0, balance(chicken).compareTo(g("900")));
        assertEquals(
                "CONSUMPTION_REVERSAL", corrections.history(user.getEmail(), b).getFirst().kind());
        cooking.consume(
                user.getEmail(),
                UUID.randomUUID(),
                todayEntry,
                new PantryModels.Command(revision()));
        assertEquals(0, view().batches().getFirst().remainingG().compareTo(g("200")));
    }

    @Test
    void cancelledDayReversalReleasesCookedFood() {
        consumedBatch();
        jdbc.update(
                "update meal_plans set status='CANCELLED',cancelled_at=now() where id=(select"
                    + " meal_plan_id from meal_plan_entries where id=:e)",
                Map.of("e", todayEntry));
        corrections.undoConsumption(
                user.getEmail(),
                UUID.randomUUID(),
                todayEntry,
                new CookingCorrections.Reversal(revision(), "Ошибка"));
        assertEquals(0, view().batches().getFirst().freeG().compareTo(g("200")));
    }

    @Test
    void discardedBatchCannotBeRestoredByCorrection() {
        var b = consumedBatch();
        cooking.discard(
                user.getEmail(), UUID.randomUUID(), b, new PantryModels.Command(revision()));
        assertThrows(
                PantryException.class,
                () ->
                        corrections.undoConsumption(
                                user.getEmail(),
                                UUID.randomUUID(),
                                todayEntry,
                                new CookingCorrections.Reversal(revision(), "Ошибка")));
        assertThrows(
                PantryException.class,
                () ->
                        corrections.weight(
                                user.getEmail(),
                                UUID.randomUUID(),
                                b,
                                new CookingCorrections.Weight(revision(), g("600"), "Ошибка")));
        assertTrue(corrections.history(user.getEmail(), b).isEmpty());
    }

    @Test
    void expiredReversalNeverExtendsExpiryOrAllowsEating() {
        var b = consumedBatch();
        jdbc.update(
                "update cooking_batches set cook_on=:d,use_by=:d where id=:b",
                Map.of("d", today.minusDays(1), "b", b));
        corrections.undoConsumption(
                user.getEmail(),
                UUID.randomUUID(),
                todayEntry,
                new CookingCorrections.Reversal(revision(), "Ошибка"));
        assertTrue(view().batches().getFirst().expired());
        assertEquals(today.minusDays(1), view().batches().getFirst().useBy());
        assertThrows(
                PantryException.class,
                () ->
                        cooking.consume(
                                user.getEmail(),
                                UUID.randomUUID(),
                                todayEntry,
                                new PantryModels.Command(revision())));
    }

    @Test
    void correctionsEnforceOwnerAndRevision() {
        var b = consumedBatch();
        var other = new User("correction-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(other);
        em.flush();
        assertThrows(PantryException.class, () -> corrections.history(other.getEmail(), b));
        assertThrows(
                PantryException.class,
                () ->
                        corrections.weight(
                                other.getEmail(),
                                UUID.randomUUID(),
                                b,
                                new CookingCorrections.Weight(
                                        pantry.view(other.getEmail()).revision(),
                                        g("600"),
                                        "Ошибка")));
        assertThrows(
                PantryException.class,
                () ->
                        corrections.weight(
                                user.getEmail(),
                                UUID.randomUUID(),
                                b,
                                new CookingCorrections.Weight(revision() - 1, g("600"), "Ошибка")));
        assertTrue(corrections.history(user.getEmail(), b).isEmpty());
    }

    @Test
    void correctionApiRequiresCsrfAndValidatesWeight() throws Exception {
        var b = consumedBatch();
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                        .apply(
                                org.springframework.security.test.web.servlet.setup
                                        .SecurityMockMvcConfigurers.springSecurity())
                        .build();
        String path = "/api/me/cooking-batches/" + b + "/weight-corrections";
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
                                .content(
                                        "{\"expectedRevision\":"
                                                + revision()
                                                + ",\"actualYieldG\":0,\"reason\":\"test\"}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
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
                                .content(
                                        "{\"expectedRevision\":"
                                                + revision()
                                                + ",\"actualYieldG\":450,\"reason\":\"test\"}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/me/cooking-batches/" + b + "/corrections")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                user.getEmail())))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$[0].after.actualYieldG")
                                .value(450));
    }

    @Test
    void plannedAndPreparedSharedComponentsProtectSavedMealReplacement() {
        var batch = create();
        assertFalse(swaps.status(user.getEmail(), todayEntry).canReplace());
        assertThrows(PantryException.class, () -> swaps.preview(user.getEmail(), todayEntry));
        cooking.prepare(
                user.getEmail(), UUID.randomUUID(), batch, new Prepare(revision(), g("400")));
        assertFalse(swaps.status(user.getEmail(), todayEntry).canReplace());
        assertThrows(PantryException.class, () -> swaps.preview(user.getEmail(), todayEntry));
        assertEquals(0, balance(rice).compareTo(g("800")));
    }
}
