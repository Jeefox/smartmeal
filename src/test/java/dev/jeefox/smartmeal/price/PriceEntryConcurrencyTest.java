package dev.jeefox.smartmeal.price;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.jeefox.smartmeal.price.dto.SavePriceEntriesRequest;
import dev.jeefox.smartmeal.price.dto.SavePriceEntriesResponse;
import dev.jeefox.smartmeal.price.service.PriceEntryService;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.store.entity.*;
import dev.jeefox.smartmeal.user.entity.User;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class PriceEntryConcurrencyTest {
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate tx;
    @Autowired private PriceEntryService service;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;
    private UUID userId;
    private String email;
    private Long storeId, chainId, productId, categoryId;
    private SavePriceEntriesRequest request;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tx.executeWithoutResult(
                status -> {
                    var user =
                            new User(
                                    "price-batch-" + UUID.randomUUID() + "@example.invalid",
                                    "unused-test-hash");
                    em.persist(user);
                    userId = user.getId();
                    email = user.getEmail();
                    var chain = new RetailChain("BATCH_" + UUID.randomUUID(), "Тест");
                    em.persist(chain);
                    chainId = chain.getId();
                    var store = new Store(chain, "Тула", "Тест");
                    em.persist(store);
                    storeId = store.getId();
                    var category = new ProductCategory("BATCH_" + UUID.randomUUID(), "Тест");
                    em.persist(category);
                    categoryId = category.getId();
                    var product =
                            new Product(
                                    category,
                                    "Рис",
                                    null,
                                    100,
                                    BigDecimal.ONE,
                                    BigDecimal.ONE,
                                    BigDecimal.ONE);
                    em.persist(product);
                    productId = product.getId();
                });
        request =
                new SavePriceEntriesRequest(
                        UUID.randomUUID(),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        List.of(
                                new SavePriceEntriesRequest.Entry(
                                        productId,
                                        null,
                                        "Рис",
                                        new BigDecimal("900"),
                                        new BigDecimal("79.90"),
                                        false)));
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(
                status -> {
                    if (userId != null)
                        em.createNativeQuery("delete from users where id=:id")
                                .setParameter("id", userId)
                                .executeUpdate();
                    if (storeId != null) {
                        em.createNativeQuery(
                                        "delete from price_observations where store_product_id in"
                                            + " (select id from store_products where store_id=:id)")
                                .setParameter("id", storeId)
                                .executeUpdate();
                        em.createNativeQuery("delete from store_products where store_id=:id")
                                .setParameter("id", storeId)
                                .executeUpdate();
                        em.createNativeQuery("delete from stores where id=:id")
                                .setParameter("id", storeId)
                                .executeUpdate();
                    }
                    if (chainId != null)
                        em.createNativeQuery("delete from retail_chains where id=:id")
                                .setParameter("id", chainId)
                                .executeUpdate();
                    if (productId != null)
                        em.createNativeQuery("delete from products where id=:id")
                                .setParameter("id", productId)
                                .executeUpdate();
                    if (categoryId != null)
                        em.createNativeQuery("delete from product_categories where id=:id")
                                .setParameter("id", categoryId)
                                .executeUpdate();
                });
    }

    private long count(String table) {
        return tx.execute(
                status ->
                        ((Number)
                                        em.createNativeQuery(
                                                        "select count(*) from "
                                                                + table
                                                                + " where "
                                                                + (table.equals("store_products")
                                                                        ? "store_id=:id"
                                                                        : "store_product_id in"
                                                                              + " (select id from"
                                                                              + " store_products"
                                                                              + " where"
                                                                              + " store_id=:id)"))
                                                .setParameter("id", storeId)
                                                .getSingleResult())
                                .longValue());
    }

    @Test
    void concurrentIdenticalRequestsCreateOnlyOneObservationAndPackage() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<SavePriceEntriesResponse> task =
                    () -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return service.save(email, storeId, request);
                    };
            var first = pool.submit(task);
            var second = pool.submit(task);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, count("store_products"));
        assertEquals(1, count("price_observations"));
    }

    @Test
    void invalidSecondRowRollsBackFirstPriceAndNewPackage() throws Exception {
        var invalid =
                new SavePriceEntriesRequest(
                        request.requestId(),
                        request.observedAt(),
                        List.of(
                                request.entries().getFirst(),
                                new SavePriceEntriesRequest.Entry(
                                        Long.MAX_VALUE,
                                        null,
                                        "Неизвестный",
                                        new BigDecimal("500"),
                                        BigDecimal.TEN,
                                        false)));
        mvc.perform(
                        post("/api/stores/" + storeId + "/price-entry")
                                .with(user(email).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(invalid)))
                .andExpect(status().isNotFound());
        assertEquals(0, count("store_products"));
        assertEquals(0, count("price_observations"));
        assertEquals(1, service.save(email, storeId, request).savedCount());
    }

    @Test
    void wrongStorePackageIsRejectedAndRepeatHttpDoesNotDuplicate() throws Exception {
        Long otherStore =
                tx.execute(
                        status -> {
                            var other =
                                    new Store(
                                            em.find(RetailChain.class, chainId),
                                            "Тула",
                                            "Другой адрес");
                            em.persist(other);
                            return other.getId();
                        });
        try {
            var saved = service.save(email, storeId, request);
            var wrong =
                    new SavePriceEntriesRequest(
                            UUID.randomUUID(),
                            request.observedAt(),
                            List.of(
                                    new SavePriceEntriesRequest.Entry(
                                            productId,
                                            saved.entries().getFirst().storeProductId(),
                                            null,
                                            null,
                                            BigDecimal.TEN,
                                            false)));
            mvc.perform(
                            post("/api/stores/" + otherStore + "/price-entry")
                                    .with(user(email).roles("CATALOG_EDITOR"))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(wrong)))
                    .andExpect(status().isConflict());
            mvc.perform(
                            post("/api/stores/" + storeId + "/price-entry")
                                    .with(user(email).roles("CATALOG_EDITOR"))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
            assertEquals(1, count("price_observations"));
        } finally {
            tx.executeWithoutResult(
                    status ->
                            em.createNativeQuery("delete from stores where id=:id")
                                    .setParameter("id", otherStore)
                                    .executeUpdate());
        }
    }
}
