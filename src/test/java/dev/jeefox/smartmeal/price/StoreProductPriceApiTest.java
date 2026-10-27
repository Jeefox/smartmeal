package dev.jeefox.smartmeal.price;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.price.dto.CreatePriceObservationRequest;
import dev.jeefox.smartmeal.price.dto.PriceObservationResponse;
import dev.jeefox.smartmeal.price.service.PriceObservationService;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.store.entity.RetailChain;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.entity.StoreProduct;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@SpringBootTest
@Transactional
class StoreProductPriceApiTest {
    private static final String USER = "price-test@example.invalid";
    @Autowired private EntityManager em;
    @Autowired private WebApplicationContext context;
    @Autowired private PriceObservationService priceService;
    private MockMvc mvc;
    private Store store;
    private Product product;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        RetailChain chain = new RetailChain("API_" + suffix, "Тестовая сеть");
        em.persist(chain);
        store = new Store(chain, "Тестовый город", "Тестовый адрес");
        em.persist(store);
        ProductCategory category = new ProductCategory("API_" + suffix, "Крупы");
        em.persist(category);
        product =
                new Product(
                        category,
                        "Крупа",
                        null,
                        350,
                        new BigDecimal("12"),
                        new BigDecimal("3"),
                        new BigDecimal("65"));
        em.persist(product);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createsPackageAndReadsItFromLocation() throws Exception {
        String location =
                mvc.perform(
                                post("/api/store-products")
                                        .with(user(USER).roles("CATALOG_EDITOR"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(productBody(store.getId(), product.getId(), 800)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.active").value(true))
                        .andExpect(jsonPath("$.packageWeightG").value(800))
                        .andExpect(jsonPath("$.createdAt").isNotEmpty())
                        .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");
        assertNotNull(location);
        em.flush();
        em.clear();
        mvc.perform(get(location).with(user(USER).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Учебная упаковка"))
                .andExpect(jsonPath("$.storeId").value(store.getId()))
                .andExpect(jsonPath("$.productId").value(product.getId()));
    }

    @Test
    void createsManualPriceAndGetsLatestDto() throws Exception {
        StoreProduct item = item();
        mvc.perform(
                        post(prices(item))
                                .with(user(USER).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"price\":89.90,\"observedAt\":\"2026-10-01T09:00:00Z\","
                                            + "\"promotional\":true,\"priceSource\":\"RECEIPT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(89.9))
                .andExpect(jsonPath("$.storeProductId").value(item.getId()))
                .andExpect(jsonPath("$.priceSource").value("MANUAL"))
                .andExpect(jsonPath("$.promotional").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        em.flush();
        em.clear();
        mvc.perform(get(prices(item) + "/latest").with(user(USER).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(89.9))
                .andExpect(jsonPath("$.priceSource").value("MANUAL"));
    }

    @Test
    void keepsHistoryAndSelectsByObservationDateThenId() throws Exception {
        StoreProduct item = item();
        Instant latestTime = Instant.parse("2026-10-02T09:00:00Z");
        PriceObservationResponse first =
                priceService.create(item.getId(), request("90.00", latestTime));
        priceService.create(item.getId(), request("80.00", latestTime.minusSeconds(86400)));
        assertEquals(first.id(), priceService.findLatest(item.getId()).id());
        PriceObservationResponse tieWinner =
                priceService.create(item.getId(), request("95.00", latestTime));
        em.flush();
        em.clear();
        mvc.perform(get(prices(item) + "/latest").with(user(USER).roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tieWinner.id()))
                .andExpect(jsonPath("$.price").value(95));
        assertEquals(
                3L,
                em.createQuery(
                                "select count(p) from PriceObservation p where p.storeProduct.id ="
                                    + " :id",
                                Long.class)
                        .setParameter("id", item.getId())
                        .getSingleResult());
    }

    @Test
    void latestPriceDoesNotLeakBetweenStoreProducts() {
        StoreProduct first = item();
        StoreProduct second = item();
        Instant observed = Instant.parse("2026-10-02T09:00:00Z");
        PriceObservationResponse firstPrice =
                priceService.create(first.getId(), request("90.00", observed));
        priceService.create(second.getId(), request("200.00", observed.plusSeconds(1)));
        assertEquals(firstPrice.id(), priceService.findLatest(first.getId()).id());
    }

    @Test
    void distinguishesMissingProductAndMissingPrice() throws Exception {
        StoreProduct item = item();
        mvc.perform(get(prices(item) + "/latest").with(user(USER).roles("CATALOG_EDITOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Цена ещё не добавлена"));
        mvc.perform(
                        get("/api/store-products/" + Long.MAX_VALUE)
                                .with(user(USER).roles("CATALOG_EDITOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Товар магазина не найден"));
        mvc.perform(
                        get("/api/store-products/" + Long.MAX_VALUE + "/prices/latest")
                                .with(user(USER).roles("CATALOG_EDITOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Товар магазина не найден"));
        mvc.perform(
                        post("/api/store-products/" + Long.MAX_VALUE + "/prices")
                                .with(user(USER).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(priceBody("90")))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingStoreOrProductDoesNotCreatePackage() throws Exception {
        mvc.perform(
                        post("/api/store-products")
                                .with(user(USER).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(productBody(Long.MAX_VALUE, product.getId(), 800)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Магазин не найден"));
        mvc.perform(
                        post("/api/store-products")
                                .with(user(USER).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(productBody(store.getId(), Long.MAX_VALUE, 800)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Продукт не найден"));
        assertEquals(
                0L,
                em.createQuery(
                                "select count(p) from StoreProduct p where p.store.id = :id",
                                Long.class)
                        .setParameter("id", store.getId())
                        .getSingleResult());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "1.001", "10000000000"})
    void rejectsInvalidPriceWithoutSavingObservation(String price) throws Exception {
        StoreProduct item = item();
        mvc.perform(
                        post(prices(item))
                                .with(user(USER).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(priceBody(price)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
        mvc.perform(get(prices(item) + "/latest").with(user(USER).roles("CATALOG_EDITOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsFutureDateAndMissingPriceFields() throws Exception {
        StoreProduct item = item();
        for (String body :
                new String[] {
                    "{}",
                    "{\"price\":90,\"promotional\":false}",
                    "{\"price\":90,\"observedAt\":\"2026-10-01T09:00:00Z\"}",
                    "{\"price\":90,\"promotional\":false,\"observedAt\":\"2099-01-01T00:00:00Z\"}"
                }) {
            mvc.perform(
                            post(prices(item))
                                    .with(user(USER).roles("CATALOG_EDITOR"))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(
                0L,
                em.createQuery(
                                "select count(p) from PriceObservation p where p.storeProduct.id ="
                                    + " :id",
                                Long.class)
                        .setParameter("id", item.getId())
                        .getSingleResult());
    }

    @Test
    void rejectsInvalidPackageFields() throws Exception {
        String valid = productBody(store.getId(), product.getId(), 800);
        for (String body :
                new String[] {
                    "{}",
                    valid.replace("Учебная упаковка", "   "),
                    valid.replace("Учебная упаковка", "x".repeat(301)),
                    productBody(store.getId(), product.getId(), 0),
                    productBody(store.getId(), product.getId(), -1)
                }) {
            mvc.perform(
                            post("/api/store-products")
                                    .with(user(USER).roles("CATALOG_EDITOR"))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        StoreProduct item = item();
        mvc.perform(get("/api/store-products/" + item.getId()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(prices(item) + "/latest")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post(prices(item))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(priceBody("90")))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(prices(item))
                                .with(user(USER).roles("CATALOG_EDITOR"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(priceBody("90")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/store-products")
                                .with(user(USER).roles("CATALOG_EDITOR"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(productBody(store.getId(), product.getId(), 800)))
                .andExpect(status().isForbidden());
    }

    private StoreProduct item() {
        StoreProduct item = new StoreProduct(store, product, "Учебная упаковка", 800);
        em.persist(item);
        return item;
    }

    private String prices(StoreProduct item) {
        return "/api/store-products/" + item.getId() + "/prices";
    }

    private String productBody(Long storeId, Long productId, int weight) {
        return "{\"storeId\":"
                + storeId
                + ",\"productId\":"
                + productId
                + ",\"name\":\"Учебная упаковка\",\"packageWeightG\":"
                + weight
                + "}";
    }

    private String priceBody(String price) {
        return "{\"price\":"
                + price
                + ",\"observedAt\":\"2026-10-01T09:00:00Z\",\"promotional\":false}";
    }

    private CreatePriceObservationRequest request(String price, Instant at) {
        return new CreatePriceObservationRequest(new BigDecimal(price), at, false);
    }
}
