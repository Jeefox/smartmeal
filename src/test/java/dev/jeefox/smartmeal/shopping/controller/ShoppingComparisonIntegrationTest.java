package dev.jeefox.smartmeal.shopping.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeIngredientSnapshot;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.price.entity.PriceObservation;
import dev.jeefox.smartmeal.price.entity.PriceSource;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.store.entity.RetailChain;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.entity.StoreProduct;
import dev.jeefox.smartmeal.user.entity.User;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Transactional
class ShoppingComparisonIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;
    private User owner;
    private Product rice;
    private Product extra;
    private Store a;
    private Store b;
    private final LocalDate date = LocalDate.of(2033, 1, 1);
    private final Instant observedAt = Instant.parse("2026-01-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner =
                new User(
                        "comparison-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
        em.persist(owner);
        ProductCategory category =
                new ProductCategory("TEST_" + UUID.randomUUID(), "Тестовая категория");
        em.persist(category);
        rice = product(category, "Рис");
        extra = product(category, "Соль");
        RetailChain chain = new RetailChain("TEST_" + UUID.randomUUID(), "Тестовая сеть");
        em.persist(chain);
        a = new Store(chain, "Тестовый город", "Тестовый адрес А");
        b = new Store(chain, "Тестовый город", "Тестовый адрес Б");
        em.persist(a);
        em.persist(b);
        StoreProduct large = pack(a, rice, 800, "Большая упаковка");
        price(large, "80.00", observedAt.minusSeconds(1), false);
        price(large, "85.00", observedAt, false);
        price(large, "90.00", observedAt, false);
        price(large, "1.00", observedAt.plusSeconds(1), true);
        price(pack(b, rice, 500, "Маленькая упаковка"), "70.00", observedAt, false);
        StoreProduct inactive = pack(b, rice, 1000, "Неактивная упаковка");
        price(inactive, "1.00", observedAt, false);
        em.flush();
        em.createNativeQuery("update store_products set active = false where id = :id")
                .setParameter("id", inactive.getId())
                .executeUpdate();
    }

    @Test
    void comparesRealSnapshotsPackagesAndRegularPricesThroughApi() throws Exception {
        menu(false);
        clear();
        mvc.perform(
                        get("/api/me/shopping-comparison")
                                .param("from", date.toString())
                                .param("to", date.toString())
                                .param("storeIds", a.getId() + "," + b.getId() + "," + b.getId())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containsDrafts").value(true))
                .andExpect(jsonPath("$.shoppingEstimateResponseList.length()").value(2))
                .andExpect(jsonPath("$.shoppingEstimateResponseList[0].storeId").value(b.getId()))
                .andExpect(jsonPath("$.shoppingEstimateResponseList[0].complete").value(true))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[0].knownPurchaseCost").value(140))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[0].items[0].requiredWeightG")
                                .value(1000))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[0].items[0].packageCount")
                                .value(2))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[0].items[0].remainderG").value(0))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[1].knownPurchaseCost").value(180))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[1].items[0].packagePrice")
                                .value(90))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[1].items[0].remainderG")
                                .value(600));
        mvc.perform(
                        get("/api/me/shopping-estimate")
                                .param("from", date.toString())
                                .param("to", date.toString())
                                .param("storeId", a.getId().toString())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knownPurchaseCost").value(180))
                .andExpect(jsonPath("$.items[0].priceSource").value("MANUAL"));
    }

    @Test
    void completeStoreComesBeforeCheaperIncompleteEstimate() throws Exception {
        menu(true);
        price(pack(a, extra, 500, "Соль"), "20.00", observedAt, false);
        clear();
        mvc.perform(
                        get("/api/me/shopping-comparison")
                                .param("from", date.toString())
                                .param("to", date.toString())
                                .param("storeIds", b.getId() + "," + a.getId())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shoppingEstimateResponseList[0].storeId").value(a.getId()))
                .andExpect(jsonPath("$.shoppingEstimateResponseList[0].complete").value(true))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[0].knownPurchaseCost").value(200))
                .andExpect(jsonPath("$.shoppingEstimateResponseList[1].complete").value(false))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[1].knownPurchaseCost").value(140))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[1].items[1].productId")
                                .value(extra.getId()))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[1].items[1].purchaseCost")
                                .doesNotExist());
    }

    @Test
    void doesNotReadAnotherUsersMenu() throws Exception {
        menu(false);
        User other =
                new User("other-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
        em.persist(other);
        clear();
        mvc.perform(
                        get("/api/me/shopping-comparison")
                                .param("from", date.toString())
                                .param("to", date.toString())
                                .param("storeIds", a.getId().toString())
                                .with(user(other.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containsDrafts").value(false))
                .andExpect(jsonPath("$.shoppingEstimateResponseList[0].complete").value(true))
                .andExpect(jsonPath("$.shoppingEstimateResponseList[0].knownPurchaseCost").value(0))
                .andExpect(jsonPath("$.shoppingEstimateResponseList[0].items").isEmpty());
    }

    @Test
    void estimatesIgnoreInvalidatedRegularPricesAndDoNotUsePromotionalFallback() throws Exception {
        menu(false);
        var observations =
                em.createQuery(
                                "select p from PriceObservation p where p.storeProduct.store.id ="
                                    + " :id",
                                PriceObservation.class)
                        .setParameter("id", a.getId())
                        .getResultList();
        observations.stream()
                .filter(p -> p.getPrice().compareTo(new BigDecimal("90")) == 0)
                .forEach(p -> p.invalidate("Ошибка цены", owner.getEmail()));
        clear();
        mvc.perform(
                        get("/api/me/shopping-estimate")
                                .param("from", date.toString())
                                .param("to", date.toString())
                                .param("storeId", a.getId().toString())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knownPurchaseCost").value(170));
        em
                .createQuery(
                        "select p from PriceObservation p where p.storeProduct.store.id = :id",
                        PriceObservation.class)
                .setParameter("id", a.getId())
                .getResultList()
                .stream()
                .filter(p -> !p.isPromotional())
                .forEach(p -> p.invalidate("Ошибка цены", owner.getEmail()));
        clear();
        mvc.perform(
                        get("/api/me/shopping-comparison")
                                .param("from", date.toString())
                                .param("to", date.toString())
                                .param("storeIds", a.getId() + "," + b.getId())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shoppingEstimateResponseList[0].storeId").value(b.getId()))
                .andExpect(jsonPath("$.shoppingEstimateResponseList[1].complete").value(false))
                .andExpect(
                        jsonPath("$.shoppingEstimateResponseList[1].items[0].purchaseCost")
                                .doesNotExist());
    }

    @Test
    void activityChangeRemovesAndRestoresOfferInShoppingEstimate() throws Exception {
        menu(false);
        Long skuId =
                em.createQuery("select p.id from StoreProduct p where p.store.id = :id", Long.class)
                        .setParameter("id", a.getId())
                        .getSingleResult();
        clear();
        var put =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/store-products/{id}/activity", skuId);
        mvc.perform(
                        put.with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/me/shopping-estimate")
                                .param("from", date.toString())
                                .param("to", date.toString())
                                .param("storeId", a.getId().toString())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.items[0].purchaseCost").doesNotExist());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/store-products/{id}/activity", skuId)
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR"))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/me/shopping-estimate")
                                .param("from", date.toString())
                                .param("to", date.toString())
                                .param("storeId", a.getId().toString())
                                .with(user(owner.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.knownPurchaseCost").value(180));
    }

    private Product product(ProductCategory category, String name) {
        Product result =
                new Product(
                        category, name, null, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        em.persist(result);
        return result;
    }

    private StoreProduct pack(Store store, Product product, int weight, String name) {
        StoreProduct result = new StoreProduct(store, product, name, weight);
        em.persist(result);
        return result;
    }

    private void price(StoreProduct product, String amount, Instant time, boolean promotional) {
        em.persist(
                new PriceObservation(
                        product, new BigDecimal(amount), time, PriceSource.MANUAL, promotional));
    }

    private void menu(boolean withExtra) {
        MealPlan plan = new MealPlan(owner, date);
        em.persist(plan);
        Recipe recipe = new Recipe("Тестовый рецепт", null, "Приготовить", 10, 3);
        em.persist(recipe);
        var ingredients = new java.util.ArrayList<RecipeIngredientSnapshot>();
        ingredients.add(new RecipeIngredientSnapshot(rice.getId(), "Рис", new BigDecimal("1500")));
        if (withExtra)
            ingredients.add(
                    new RecipeIngredientSnapshot(extra.getId(), "Соль", new BigDecimal("150")));
        var zero =
                new NutritionValues(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        var snapshot =
                new RecipeSnapshot(
                        recipe.getId(),
                        recipe.getName(),
                        null,
                        recipe.getInstructions(),
                        10,
                        3,
                        List.copyOf(ingredients),
                        zero,
                        zero);
        em.persist(
                new MealPlanEntry(plan, recipe, MealType.LUNCH, new BigDecimal("2"), 1, snapshot));
    }

    private void clear() {
        em.flush();
        em.clear();
    }
}
