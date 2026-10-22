package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.pantry.PantryModels.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.service.MealPlanService;
import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.shopping.service.ShoppingListService;
import dev.jeefox.smartmeal.user.entity.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real transactions (not a test-wide rollback) verify failed commands leave no partial movements.
 */
@SpringBootTest
class PantryIntegrationTest {
    @Autowired PantryService pantry;
    @Autowired MealPlanService menus;
    @Autowired ShoppingListService shopping;
    @Autowired EntityManager em;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext web;
    UUID owner, recipeId;
    Long productId, categoryId;
    String email;
    TransactionTemplate tx;
    MockMvc mvc;

    BigDecimal g(String v) {
        return new BigDecimal(v);
    }

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(transactionManager);
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        tx.executeWithoutResult(
                s -> {
                    var u = new User("pantry-" + UUID.randomUUID() + "@example.invalid", "unused");
                    em.persist(u);
                    owner = u.getId();
                    email = u.getEmail();
                    em.persist(new UserProfile(2000, u));
                    var c = new ProductCategory("PANTRY_" + UUID.randomUUID(), "Тест запасов");
                    em.persist(c);
                    categoryId = c.getId();
                    var p = new Product(c, "Тест гречки", null, 350, g("12"), g("3"), g("60"));
                    em.persist(p);
                    productId = p.getId();
                    var r = new Recipe("Тест блюда запасов", null, "Сварить", 10, 1);
                    em.persist(r);
                    recipeId = r.getId();
                });
    }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(
                s -> {
                    var p = Map.of("u", owner, "r", recipeId, "p", productId, "c", categoryId);
                    jdbc.update("delete from pantry_movements where user_id=:u", p);
                    jdbc.update("delete from pantry_reservations where user_id=:u", p);
                    jdbc.update(
                            "delete from meal_entry_inventory_states where meal_plan_entry_id in"
                                    + " (select e.id from meal_plan_entries e join meal_plans m on"
                                    + " m.id=e.meal_plan_id where m.user_id=:u)",
                            p);
                    jdbc.update("delete from pantry_operations where user_id=:u", p);
                    jdbc.update("delete from pantry_lots where user_id=:u", p);
                    jdbc.update("delete from pantry_accounts where user_id=:u", p);
                    jdbc.update("delete from meal_plans where user_id=:u", p);
                    jdbc.update("delete from user_profiles where user_id=:u", p);
                    jdbc.update("delete from users where id=:u", p);
                    jdbc.update("delete from recipes where id=:r", p);
                    jdbc.update("delete from products where id=:p", p);
                    jdbc.update("delete from product_categories where id=:c", p);
                });
    }

    Action add(String amount, LocalDate expiry) {
        return pantry.addLot(
                email,
                UUID.randomUUID(),
                new AddLot(
                        pantry.view(email).revision(),
                        productId,
                        g(amount),
                        pantry.today(),
                        expiry));
    }

    long rev() {
        return pantry.view(email).revision();
    }

    record Plan(UUID id, UUID entry, LocalDate date) {}

    Plan plan(int day, String amount) {
        return tx.execute(
                s -> {
                    var p =
                            new MealPlan(
                                    em.getReference(User.class, owner),
                                    pantry.today().plusDays(day));
                    em.persist(p);
                    var n = new NutritionValues(g("350"), g("12"), g("3"), g("60"));
                    var snap =
                            new RecipeSnapshot(
                                    recipeId,
                                    "Блюдо",
                                    null,
                                    "Готовить",
                                    10,
                                    1,
                                    List.of(
                                            new RecipeIngredientSnapshot(
                                                    productId, "Гречка", g(amount))),
                                    n,
                                    n);
                    var e =
                            new MealPlanEntry(
                                    p,
                                    em.getReference(Recipe.class, recipeId),
                                    MealType.LUNCH,
                                    BigDecimal.ONE,
                                    1,
                                    snap);
                    em.persist(e);
                    return new Plan(p.getId(), e.getId(), p.getPlanDate());
                });
    }

    @Test
    void initialReceiptIsIdempotentAndJournalMatchesBalance() {
        UUID key = UUID.randomUUID();
        var request = new AddLot(0, productId, g("300"), pantry.today(), null);
        var first = pantry.addLot(email, key, request);
        assertEquals(first, pantry.addLot(email, key, request));
        assertEquals(0, g("300").compareTo(pantry.view(email).lots().getFirst().quantityOnHandG()));
        assertEquals(1, pantry.history(email, 0).items().size());
        assertThrows(
                PantryException.class,
                () ->
                        pantry.addLot(
                                email,
                                key,
                                new AddLot(0, productId, g("301"), pantry.today(), null)));
    }

    @Test
    void staleCorrectionAndExcessDiscardLeaveNoPartialOperation() {
        var id = add("300", null).lotIds().getFirst();
        assertThrows(
                PantryException.class,
                () ->
                        pantry.adjust(
                                email,
                                UUID.randomUUID(),
                                id,
                                new Adjustment(0, g("50"), "Сверка"),
                                false));
        assertThrows(
                PantryException.class,
                () ->
                        pantry.adjust(
                                email,
                                UUID.randomUUID(),
                                id,
                                new Adjustment(rev(), g("500"), "Порча"),
                                true));
        assertEquals(1, pantry.history(email, 0).items().size());
        assertEquals(0, g("300").compareTo(pantry.view(email).lots().getFirst().quantityOnHandG()));
    }

    @Test
    void draftDoesNotReserveButConfirmDoesAndOwnShoppingDoesNotSubtractTwice() {
        add("300", null);
        var p = plan(0, "200");
        assertEquals(0, pantry.view(email).lots().getFirst().reservedG().signum());
        menus.confirmMenu(email, p.id());
        var lot = pantry.view(email).lots().getFirst();
        assertEquals(0, g("200").compareTo(lot.reservedG()));
        assertEquals(0, g("100").compareTo(lot.freeG()));
        var needs = shopping.calculate(email, p.date(), p.date()).items().getFirst();
        assertEquals(0, needs.quantityG().signum());
        assertEquals(0, g("200").compareTo(needs.fromPantryG()));
    }

    @Test
    void twoConfirmedDaysCannotOverReserveOneLotAndCancelReleasesIt() {
        add("300", null);
        var first = plan(0, "200");
        var second = plan(1, "200");
        menus.confirmMenu(email, second.id());
        menus.confirmMenu(email, first.id());
        assertEquals(0, g("300").compareTo(pantry.view(email).lots().getFirst().reservedG()));
        var later = pantry.states(email, second.id()).entries().getFirst();
        assertEquals(0, g("100").compareTo(later.reservedG().get(productId)));
        pantry.plan(email, UUID.randomUUID(), first.id(), new Command(rev()), false);
        assertEquals(0, g("200").compareTo(pantry.view(email).lots().getFirst().reservedG()));
        assertEquals(0, g("100").compareTo(pantry.view(email).lots().getFirst().freeG()));
    }

    @Test
    void deductionAndReversalAreExactlyOnce() {
        add("300", null);
        var p = plan(0, "200");
        menus.confirmMenu(email, p.id());
        UUID key = UUID.randomUUID();
        var command = new Command(rev());
        var action = pantry.usage(email, key, p.entry(), command);
        assertEquals(action, pantry.usage(email, key, p.entry(), command));
        assertEquals(0, g("100").compareTo(pantry.view(email).lots().getFirst().quantityOnHandG()));
        assertThrows(
                PantryException.class,
                () -> pantry.usage(email, UUID.randomUUID(), p.entry(), new Command(rev())));
        pantry.reverse(email, UUID.randomUUID(), p.entry(), new Command(rev()));
        assertEquals(0, g("300").compareTo(pantry.view(email).lots().getFirst().quantityOnHandG()));
        assertThrows(
                PantryException.class,
                () -> pantry.reverse(email, UUID.randomUUID(), p.entry(), new Command(rev())));
        assertEquals(3, pantry.history(email, 0).items().size());
    }

    @Test
    void missingIngredientRollsBackAllDeductionsAndOperation() {
        add("100", null);
        var p = plan(0, "200");
        menus.confirmMenu(email, p.id());
        var error =
                assertThrows(
                        PantryException.class,
                        () ->
                                pantry.usage(
                                        email, UUID.randomUUID(), p.entry(), new Command(rev())));
        assertEquals("INSUFFICIENT_STOCK", error.code());
        assertTrue(error.details().containsKey("deficits"));
        assertEquals(1, pantry.history(email, 0).items().size());
        assertEquals(0, g("100").compareTo(pantry.view(email).lots().getFirst().quantityOnHandG()));
        assertEquals(State.OPEN, pantry.states(email, p.id()).entries().getFirst().state());
    }

    @Test
    void unknownAndExpiredLotsHaveDifferentAvailability() {
        // A historical receipt can be expired already; no automatic disposal.
        pantry.addLot(
                email,
                UUID.randomUUID(),
                new AddLot(
                        0,
                        productId,
                        g("300"),
                        pantry.today().minusDays(3),
                        pantry.today().minusDays(1)));
        var lot = pantry.view(email).lots().getFirst();
        assertTrue(lot.expired());
        assertEquals(0, lot.freeG().signum());
        var p = plan(0, "200");
        menus.confirmMenu(email, p.id());
        assertEquals(0, pantry.view(email).lots().getFirst().reservedG().signum());
    }

    @Test
    void purchaseReallocatesMissingStockAndSkipReleasesIt() {
        var p = plan(0, "200");
        menus.confirmMenu(email, p.id());
        pantry.purchase(
                email,
                UUID.randomUUID(),
                new Purchase(
                        rev(),
                        List.of(
                                new PurchaseLine(
                                        productId,
                                        g("900"),
                                        pantry.today(),
                                        null,
                                        null,
                                        g("90")))));
        assertEquals(0, g("200").compareTo(pantry.view(email).lots().getFirst().reservedG()));
        pantry.state(email, UUID.randomUUID(), p.entry(), new StateCommand(rev(), State.SKIPPED));
        assertEquals(0, pantry.view(email).lots().getFirst().reservedG().signum());
        assertTrue(shopping.calculate(email, p.date(), p.date()).items().isEmpty());
    }

    @Test
    void metadataCorrectionAndPhysicalCountRebuildReservations() {
        var id = add("300", pantry.today().plusDays(3)).lotIds().getFirst();
        var p = plan(1, "250");
        menus.confirmMenu(email, p.id());
        pantry.adjust(
                email, UUID.randomUUID(), id, new Adjustment(rev(), g("100"), "Пересчитал"), false);
        assertEquals(0, g("100").compareTo(pantry.view(email).lots().getFirst().reservedG()));
        pantry.metadata(email, UUID.randomUUID(), id, new Metadata(rev(), pantry.today()));
        assertEquals(0, pantry.view(email).lots().getFirst().reservedG().signum());
    }

    @Test
    void cannotDeductFutureMenuOrBypassStateEndpoint() {
        add("300", null);
        var p = plan(1, "200");
        menus.confirmMenu(email, p.id());
        assertThrows(
                PantryException.class,
                () -> pantry.usage(email, UUID.randomUUID(), p.entry(), new Command(rev())));
        assertThrows(
                PantryException.class,
                () ->
                        pantry.state(
                                email,
                                UUID.randomUUID(),
                                p.entry(),
                                new StateCommand(rev(), State.DEDUCTED)));
    }

    @Test
    void cancelledDayCanReopenWithoutLeavingForeignKeyStateRows() {
        var p = plan(0, "200");
        menus.confirmMenu(email, p.id());
        pantry.plan(email, UUID.randomUUID(), p.id(), new Command(rev()), false);
        assertTrue(shopping.calculate(email, p.date(), p.date()).items().isEmpty());
        jdbc.update(
                "update user_profiles set daily_calorie_target=2500 where user_id=:u",
                Map.of("u", owner));
        assertEquals(2000, menus.findByDate(email, p.date()).dailyCalorieTarget());
        pantry.plan(email, UUID.randomUUID(), p.id(), new Command(rev()), true);
        assertEquals(MealPlanStatus.DRAFT, menus.findByDate(email, p.date()).status());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from meal_entry_inventory_states where"
                                + " meal_plan_entry_id=:e",
                        Map.of("e", p.entry()),
                        Integer.class));
    }

    @Test
    void apiEnforcesOwnerCsrfAndValidation() throws Exception {
        var id = add("300", null).lotIds().getFirst();
        mvc.perform(get("/api/me/pantry")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/me/pantry/lots")
                                .with(user(email))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/me/pantry/lots")
                                .with(user(email))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        "{\"expectedRevision\":0,\"productId\":"
                                                + productId
                                                + ",\"quantityG\":-1,\"receivedOn\":\""
                                                + pantry.today()
                                                + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/me/pantry/lots/" + id + "/adjustments")
                                .with(user("missing@example.invalid"))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        "{\"expectedRevision\":0,\"quantityG\":1,\"reason\":\"Тест\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentDoubleTapCreatesOnlyOneOperation() throws Exception {
        var key = UUID.randomUUID();
        var command = new AddLot(0, productId, g("300"), pantry.today(), null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Action> job =
                    () -> {
                        gate.await();
                        return pantry.addLot(email, key, command);
                    };
            var a = pool.submit(job);
            var b = pool.submit(job);
            gate.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, pantry.history(email, 0).items().size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidSecondPurchaseLineRollsBackTheWholeBatch() {
        assertThrows(
                PantryException.class,
                () ->
                        pantry.purchase(
                                email,
                                UUID.randomUUID(),
                                new Purchase(
                                        0,
                                        List.of(
                                                new PurchaseLine(
                                                        productId,
                                                        g("900"),
                                                        pantry.today(),
                                                        null,
                                                        null,
                                                        null),
                                                new PurchaseLine(
                                                        productId,
                                                        g("100"),
                                                        pantry.today().plusDays(1),
                                                        null,
                                                        null,
                                                        null)))));
        assertTrue(pantry.view(email).lots().isEmpty());
        assertTrue(pantry.history(email, 0).items().isEmpty());
    }

    @Test
    void pastOpenPlanReleasesReservationWithoutAssumingConsumption() {
        add("300", null);
        var p = plan(-1, "200");
        menus.confirmMenu(email, p.id());
        var view = pantry.view(email);
        assertEquals(0, view.lots().getFirst().reservedG().signum());
        assertEquals(1, view.unconfirmedPastEntries());
        assertEquals(0, g("300").compareTo(view.lots().getFirst().quantityOnHandG()));
    }

    @Test
    void concurrentPreparationsDoNotOverdrawOrConsumeAnotherReservation() throws Exception {
        add("300", null);
        var a = plan(0, "200");
        var b = plan(-1, "200");
        menus.confirmMenu(email, a.id());
        menus.confirmMenu(email, b.id());
        long revision = rev();
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            java.util.function.Function<UUID, Callable<Boolean>> job =
                    id ->
                            () -> {
                                gate.await();
                                try {
                                    pantry.usage(
                                            email, UUID.randomUUID(), id, new Command(revision));
                                    return true;
                                } catch (PantryException ex) {
                                    return false;
                                }
                            };
            var first = pool.submit(job.apply(a.entry()));
            var second = pool.submit(job.apply(b.entry()));
            gate.countDown();
            assertEquals(
                    1,
                    (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                            + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(
                    0, g("100").compareTo(pantry.view(email).lots().getFirst().quantityOnHandG()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fractionalPortionsCanBeReservedAndCookedWithoutLosingAPackage() {
        add("2", null);
        var p = plan(0, "2");
        tx.executeWithoutResult(
                s -> {
                    var plan = em.find(MealPlan.class, p.id());
                    var first = em.find(MealPlanEntry.class, p.entry());
                    var r = first.getRecipeSnapshot();
                    var snap =
                            new RecipeSnapshot(
                                    r.recipeId(),
                                    r.name(),
                                    null,
                                    r.instructions(),
                                    r.preparationTimeMin(),
                                    3,
                                    r.ingredients(),
                                    r.nutritionTotal(),
                                    r.nutritionPerServing());
                    jdbc.update(
                            "update meal_plan_entries set"
                                    + " recipe_snapshot=jsonb_set(recipe_snapshot,'{servings}','3')"
                                    + " where id=:e",
                            Map.of("e", p.entry()));
                    for (int i = 2; i <= 3; i++)
                        em.persist(
                                new MealPlanEntry(
                                        plan,
                                        em.getReference(Recipe.class, recipeId),
                                        MealType.LUNCH,
                                        BigDecimal.ONE,
                                        i,
                                        snap));
                });
        menus.confirmMenu(email, p.id());
        var states = pantry.states(email, p.id());
        assertTrue(
                shopping.calculate(email, p.date(), p.date())
                                .items()
                                .getFirst()
                                .quantityG()
                                .signum()
                        == 0);
        // Reading twice must not spuriously bump the revision because of stored decimal scales.
        assertEquals(rev(), rev());
        for (var entry : states.entries())
            pantry.usage(email, UUID.randomUUID(), entry.entryId(), new Command(rev()));
        var balance =
                pantry.view(email).lots().stream()
                        .map(Lot::quantityOnHandG)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(balance.compareTo(new BigDecimal("1E-24")) <= 0);
        assertEquals(
                3,
                pantry.states(email, p.id()).entries().stream()
                        .filter(e -> e.state() == State.DEDUCTED)
                        .count());
    }
}
