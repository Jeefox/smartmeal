package dev.jeefox.smartmeal.shopping.purchase;

import static dev.jeefox.smartmeal.shopping.purchase.ShoppingPurchaseModels.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.mealplan.generation.period.*;
import dev.jeefox.smartmeal.pantry.*;
import dev.jeefox.smartmeal.product.entity.*;
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

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest
class ShoppingSessionIntegrationTest {
    @Autowired ShoppingSessionService sessions;
    @Autowired ShoppingPurchaseService purchases;
    @Autowired PantryService pantry;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext web;
    UUID reserveRecipe;
    TransactionTemplate tx;
    String email;
    UUID owner, generation, other;
    Long product, category;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(manager);
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        tx.executeWithoutResult(
                t -> {
                    var u =
                            new User(
                                    "shopping-" + UUID.randomUUID() + "@example.invalid", "unused");
                    em.persist(u);
                    owner = u.getId();
                    email = u.getEmail();
                    var c = new ProductCategory("SHOP_" + UUID.randomUUID(), "Тест покупок");
                    em.persist(c);
                    category = c.getId();
                    var p =
                            new Product(
                                    c,
                                    "Гречка",
                                    null,
                                    350,
                                    BigDecimal.ONE,
                                    BigDecimal.ONE,
                                    BigDecimal.ONE);
                    em.persist(p);
                    product = p.getId();
                    var o =
                            new User(
                                    "shopping-other-" + UUID.randomUUID() + "@example.invalid",
                                    "unused");
                    em.persist(o);
                    other = o.getId();
                    String body =
                            """
                            {"dailyCalorieTarget":2000,"options":{"from":"2038-03-01","to":"2038-03-03","budgetRub":4000},"entries":[],"days":[],"shopping":{"items":[{"productId":%d,"productName":"Гречка","requiredG":600,"needToBuyG":600,"purchasedG":900,"packageWeightG":900,"packagePrice":69,"packageCount":1,"purchaseCost":69,"estimated":true}],"knownPurchaseCost":69,"pricedProducts":1,"estimatedProducts":1,"unknownProducts":0},"issues":[],"participants":[]}
                            """
                                    .formatted(product);
                    var doc = json.readValue(body, PlanningModels.Document.class);
                    var g = new MenuGeneration(u, doc, Instant.now());
                    g.committed(UUID.randomUUID(), List.of(UUID.randomUUID()));
                    em.persist(g);
                    generation = g.getId();
                    em.flush();
                });
    }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(
                t -> {
                    var p = Map.of("u", owner, "o", other, "c", category, "p", product);
                    jdbc.update("delete from shopping_sessions where user_id=:u", p);
                    jdbc.update("delete from meal_plan_generations where user_id=:u", p);
                    jdbc.update("delete from pantry_movements where user_id=:u", p);
                    jdbc.update("delete from pantry_reservations where user_id=:u", p);
                    jdbc.update("delete from pantry_operations where user_id=:u", p);
                    jdbc.update("delete from pantry_lots where user_id=:u", p);
                    jdbc.update("delete from pantry_accounts where user_id in(:u,:o)", p);
                    jdbc.update("delete from meal_plans where user_id=:u", p);
                    if (reserveRecipe != null)
                        jdbc.update("delete from recipes where id=:r", Map.of("r", reserveRecipe));
                    jdbc.update("delete from users where id in(:u,:o)", p);
                    jdbc.update("delete from products where category_id=:c", p);
                    jdbc.update("delete from product_categories where id=:c", p);
                });
    }

    View open() {
        return sessions.open(email, UUID.randomUUID(), new Open(generation));
    }

    @Test
    void repeatedOpeningAndPaginationPreserveSnapshot() {
        var a = open();
        var b = open();
        assertEquals(a.id(), b.id());
        assertEquals(1, a.items().size());
        assertEquals(0, a.items().getFirst().requiredToBuyG().compareTo(new BigDecimal("600")));
        assertEquals(1, sessions.list(email, "OPEN", 0).items().size());
        assertThrows(ShoppingPurchaseException.class, () -> sessions.list(email, "bad", 0));
    }

    @Test
    void markingIsIdempotentAndStaleEditsFail() {
        var v = open();
        var item = v.items().getFirst();
        var key = UUID.randomUUID();
        var r = new Change(v.revision(), true, false);
        var a = sessions.change(email, v.id(), item.id(), key, r);
        assertTrue(a.items().getFirst().picked());
        assertEquals(a, sessions.change(email, v.id(), item.id(), key, r));
        assertEquals(
                "IDEMPOTENCY_CONFLICT",
                assertThrows(
                                ShoppingPurchaseException.class,
                                () ->
                                        sessions.change(
                                                email,
                                                v.id(),
                                                item.id(),
                                                key,
                                                new Change(a.revision(), false, false)))
                        .code());
        assertEquals(
                "SHOPPING_SESSION_STALE",
                assertThrows(
                                ShoppingPurchaseException.class,
                                () ->
                                        sessions.change(
                                                email, v.id(), item.id(), UUID.randomUUID(), r))
                        .code());
        assertEquals(0, pantry.view(email).lots().size());
    }

    @Test
    void skipsAreReversibleAndNeverAddStock() {
        var v = open();
        var i = v.items().getFirst().id();
        var a =
                sessions.change(
                        email, v.id(), i, UUID.randomUUID(), new Change(v.revision(), false, true));
        assertEquals("COMPLETED", a.status());
        assertEquals(
                "OPEN",
                sessions.change(
                                email,
                                v.id(),
                                i,
                                UUID.randomUUID(),
                                new Change(a.revision(), false, false))
                        .status());
        assertTrue(pantry.view(email).lots().isEmpty());
    }

    @Test
    void ownershipAndSecurityAreEnforced() throws Exception {
        var v = open();
        String stranger =
                jdbc.queryForObject(
                        "select email from users where id=:id", Map.of("id", other), String.class);
        assertEquals(
                404,
                assertThrows(ShoppingPurchaseException.class, () -> sessions.get(stranger, v.id()))
                        .status()
                        .value());
        mvc.perform(get("/api/me/shopping-sessions/" + v.id()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/shopping-sessions/" + v.id()).with(user(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/me/shopping-sessions")
                                .with(user(email))
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/me/shopping-sessions")
                                .with(user(email))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsavedSourceCannotBecomeShoppingList() {
        jdbc.update(
                "update meal_plan_generations set commit_key=null,saved_plan_ids='[]' where id=:id",
                Map.of("id", generation));
        assertEquals(
                "SHOPPING_SOURCE_NOT_SAVED",
                assertThrows(ShoppingPurchaseException.class, this::open).code());
    }

    @Test
    void concurrentOpenCreatesOneSession() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<UUID> task =
                    () -> {
                        start.await();
                        return open().id();
                    };
            var a = pool.submit(task);
            var b = pool.submit(task);
            start.countDown();
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, sessions.list(email, "ALL", 0).items().size());
        }
    }

    Purchase buy(View v, String amount, BigDecimal total) {
        return new Purchase(
                v.revision(),
                v.pantryRevision(),
                List.of(
                        new Line(
                                v.items().getFirst().id(),
                                new BigDecimal(amount),
                                total,
                                null,
                                pantry.today(),
                                null)));
    }

    @Test
    void partialPurchaseAndPackageRemainderAreSeparate() {
        var v = open();
        var r = purchases.purchase(email, v.id(), UUID.randomUUID(), buy(v, "400", null));
        assertEquals(0, r.items().getFirst().remainingG().compareTo(new BigDecimal("200")));
        assertEquals(0, r.items().getFirst().suggestedPurchaseG().compareTo(new BigDecimal("900")));
        assertEquals(1, r.purchasesWithoutPrice());
        var next =
                purchases.purchase(
                        email, v.id(), UUID.randomUUID(), buy(r, "900", new BigDecimal("69")));
        assertEquals("COMPLETED", next.status());
        assertEquals(
                0,
                pantry.view(email).lots().stream()
                        .map(PantryModels.Lot::quantityOnHandG)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(new BigDecimal("1300")));
        assertEquals(0, next.knownActualTotalRub().compareTo(new BigDecimal("69")));
        assertEquals(1, sessions.list(email, "COMPLETED", 0).items().size());
    }

    @Test
    void lostResponseAndNewKeyCannotDoubleCreditCompletedLine() {
        var v = open();
        var key = UUID.randomUUID();
        var request = buy(v, "900", BigDecimal.ZERO);
        var a = purchases.purchase(email, v.id(), key, request);
        assertEquals(a, purchases.purchase(email, v.id(), key, request));
        assertTrue(a.appliedPurchaseKeys().contains(key));
        assertEquals(1, pantry.view(email).lots().size());
        assertEquals(0, a.purchasesWithoutPrice());
        assertEquals(
                "IDEMPOTENCY_CONFLICT",
                assertThrows(
                                ShoppingPurchaseException.class,
                                () -> purchases.purchase(email, v.id(), key, buy(a, "901", null)))
                        .code());
        assertThrows(
                ShoppingPurchaseException.class,
                () -> purchases.purchase(email, v.id(), UUID.randomUUID(), buy(a, "900", null)));
        assertEquals(1, pantry.view(email).lots().size());
    }

    @Test
    void pantryRevisionConflictKeepsShoppingUnchanged() {
        var v = open();
        pantry.addLot(
                email,
                UUID.randomUUID(),
                new PantryModels.AddLot(
                        v.pantryRevision(), product, BigDecimal.ONE, pantry.today(), null));
        assertEquals(
                "PANTRY_STALE",
                assertThrows(
                                PantryException.class,
                                () ->
                                        purchases.purchase(
                                                email,
                                                v.id(),
                                                UUID.randomUUID(),
                                                buy(v, "400", null)))
                        .code());
        assertEquals(v.revision(), sessions.get(email, v.id()).revision());
        assertTrue(sessions.get(email, v.id()).appliedPurchaseKeys().isEmpty());
    }

    @Test
    void errorAfterPantryWriteRollsBackLotsMovementsAndShopping() {
        var v = open();
        var key = UUID.randomUUID();
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> purchases.purchase(email, v.id(), key, buy(v, "400", new BigDecimal("-1"))));
        assertTrue(pantry.view(email).lots().isEmpty());
        assertEquals(v.revision(), sessions.get(email, v.id()).revision());
        assertEquals(v.pantryRevision(), pantry.view(email).revision());
        assertTrue(sessions.get(email, v.id()).appliedPurchaseKeys().isEmpty());
        assertEquals(0, sessions.get(email, v.id()).items().getFirst().acquiredG().signum());
        assertEquals(
                1,
                purchases
                        .purchase(email, v.id(), key, buy(v, "400", null))
                        .appliedPurchaseKeys()
                        .size());
    }

    @Test
    void duplicateSkippedAndForeignItemsCannotBePurchased() {
        var v = open();
        var line = buy(v, "400", null).items().getFirst();
        assertThrows(
                ShoppingPurchaseException.class,
                () ->
                        purchases.purchase(
                                email,
                                v.id(),
                                UUID.randomUUID(),
                                new Purchase(
                                        v.revision(), v.pantryRevision(), List.of(line, line))));
        var foreign = new Line(UUID.randomUUID(), BigDecimal.ONE, null, null, pantry.today(), null);
        assertThrows(
                ShoppingPurchaseException.class,
                () ->
                        purchases.purchase(
                                email,
                                v.id(),
                                UUID.randomUUID(),
                                new Purchase(v.revision(), v.pantryRevision(), List.of(foreign))));
        var skipped =
                sessions.change(
                        email,
                        v.id(),
                        line.itemId(),
                        UUID.randomUUID(),
                        new Change(v.revision(), false, true));
        assertThrows(
                ShoppingPurchaseException.class,
                () ->
                        purchases.purchase(
                                email, v.id(), UUID.randomUUID(), buy(skipped, "400", null)));
        assertTrue(pantry.view(email).lots().isEmpty());
    }

    @Test
    void purchaseEndpointValidatesDecimalDatesAndCsrf() throws Exception {
        var v = open();
        String path = "/api/me/shopping-sessions/" + v.id() + "/purchases";
        var request = buy(v, "400", new BigDecimal("-1"));
        mvc.perform(
                        post(path)
                                .with(user(email))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post(path)
                                .with(user(email))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(json.writeValueAsString(buy(v, "400", null))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(path)
                                .with(user(email))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(json.writeValueAsString(buy(v, "400", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].remainingG").value(200));
    }

    @Test
    void simultaneousPurchasesWithSameRevisionCannotBothSucceed() throws Exception {
        var v = open();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> task =
                    () -> {
                        gate.await();
                        try {
                            purchases.purchase(
                                    email, v.id(), UUID.randomUUID(), buy(v, "400", null));
                            return true;
                        } catch (ShoppingPurchaseException e) {
                            assertEquals("SHOPPING_SESSION_STALE", e.code());
                            return false;
                        }
                    };
            var a = pool.submit(task);
            var b = pool.submit(task);
            gate.countDown();
            assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, pantry.view(email).lots().size());
        }
    }

    @Test
    void multipleItemsAreCreditedToTheCorrectProductsInOneBatch() {
        var v = open();
        Long second =
                tx.execute(
                        t -> {
                            var p =
                                    new Product(
                                            em.getReference(ProductCategory.class, category),
                                            "Макароны",
                                            null,
                                            350,
                                            BigDecimal.ONE,
                                            BigDecimal.ONE,
                                            BigDecimal.ONE);
                            em.persist(p);
                            em.flush();
                            return p.getId();
                        });
        UUID secondItem = UUID.randomUUID();
        String snap =
                json.writeValueAsString(v.items().getFirst().snapshot())
                        .replace("\"productId\":" + product, "\"productId\":" + second);
        jdbc.update(
                "insert into"
                    + " shopping_session_items(id,session_id,product_id,position,snapshot,required_to_buy_g)"
                    + " values(:id,:s,:p,1,cast(:snapshot as jsonb),200)",
                Map.of("id", secondItem, "s", v.id(), "p", second, "snapshot", snap));
        var a =
                new Line(
                        secondItem,
                        new BigDecimal("200"),
                        new BigDecimal("20"),
                        null,
                        pantry.today(),
                        null);
        var b = buy(v, "400", new BigDecimal("30")).items().getFirst();
        var result =
                purchases.purchase(
                        email,
                        v.id(),
                        UUID.randomUUID(),
                        new Purchase(v.revision(), v.pantryRevision(), List.of(a, b)));
        assertEquals(0, result.knownActualTotalRub().compareTo(new BigDecimal("50")));
        var lots = pantry.view(email).lots();
        assertEquals(2, lots.size());
        assertEquals(
                0,
                lots.stream()
                        .filter(l -> l.productId().equals(product))
                        .findFirst()
                        .orElseThrow()
                        .quantityOnHandG()
                        .compareTo(new BigDecimal("400")));
        assertEquals(
                0,
                lots.stream()
                        .filter(l -> l.productId().equals(second))
                        .findFirst()
                        .orElseThrow()
                        .quantityOnHandG()
                        .compareTo(new BigDecimal("200")));
    }

    @Test
    void purchasingRebuildsReservationsForFutureConfirmedMenu() {
        tx.executeWithoutResult(
                t -> {
                    var r =
                            new dev.jeefox.smartmeal.recipe.entity.Recipe(
                                    "Тест резерва покупки", null, "Сварить", 10, 1);
                    em.persist(r);
                    reserveRecipe = r.getId();
                    var plan =
                            new dev.jeefox.smartmeal.mealplan.entity.MealPlan(
                                    em.getReference(User.class, owner), pantry.today().plusDays(1));
                    plan.confirm(2000);
                    em.persist(plan);
                    var n =
                            new dev.jeefox.smartmeal.nutrition.dto.NutritionValues(
                                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
                    var snap =
                            new dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot(
                                    r.getId(),
                                    "Блюдо",
                                    null,
                                    "Сварить",
                                    10,
                                    1,
                                    List.of(
                                            new dev.jeefox.smartmeal.mealplan.snapshot
                                                    .RecipeIngredientSnapshot(
                                                    product, "Гречка", new BigDecimal("600"))),
                                    n,
                                    n);
                    em.persist(
                            new dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry(
                                    plan,
                                    r,
                                    dev.jeefox.smartmeal.mealplan.entity.MealType.LUNCH,
                                    BigDecimal.ONE,
                                    1,
                                    snap));
                });
        var v = open();
        purchases.purchase(email, v.id(), UUID.randomUUID(), buy(v, "900", null));
        var lot = pantry.view(email).lots().getFirst();
        assertEquals(0, lot.reservedG().compareTo(new BigDecimal("600")));
        assertEquals(0, lot.freeG().compareTo(new BigDecimal("300")));
        assertEquals(1, lot.reservedFor().size());
    }
}
