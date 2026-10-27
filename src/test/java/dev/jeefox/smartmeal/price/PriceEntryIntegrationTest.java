package dev.jeefox.smartmeal.price;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.exception.*;
import dev.jeefox.smartmeal.price.dto.*;
import dev.jeefox.smartmeal.price.entity.*;
import dev.jeefox.smartmeal.price.service.PriceEntryService;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.recipe.entity.*;
import dev.jeefox.smartmeal.store.entity.*;
import dev.jeefox.smartmeal.user.entity.User;

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

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Transactional
class PriceEntryIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private PriceEntryService service;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;
    private MockMvc mvc;
    private User user;
    private Store store;
    private Product rice;
    private Product oats;
    private Product unused;
    private String prefix;
    private final Instant observedAt = Instant.parse("2026-01-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        prefix = "Quick" + UUID.randomUUID();
        user = new User(prefix + "@example.invalid", "unused-test-hash");
        em.persist(user);
        var chain = new RetailChain(prefix, "Тест");
        em.persist(chain);
        store = new Store(chain, "Тула", "Тест");
        em.persist(store);
        var category = new ProductCategory(prefix, "Тест");
        em.persist(category);
        rice = product(category, "Рис");
        oats = product(category, "Хлопья");
        unused = product(category, "Не используется");
        var first = new Recipe(prefix, null, "Приготовить", 10, 1);
        em.persist(first);
        em.persist(new RecipeIngredient(first, rice, new BigDecimal("100"), 1));
        em.persist(new RecipeIngredient(first, oats, new BigDecimal("100"), 2));
        var second = new Recipe(prefix + "2", null, "Приготовить", 10, 1);
        em.persist(second);
        em.persist(new RecipeIngredient(second, rice, new BigDecimal("100"), 1));
        em.flush();
    }

    private Product product(ProductCategory category, String name) {
        var product =
                new Product(
                        category,
                        prefix + name,
                        null,
                        100,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE);
        em.persist(product);
        return product;
    }

    private StoreProduct sku(Product product, int grams) {
        var sku = new StoreProduct(store, product, product.getName(), grams);
        em.persist(sku);
        return sku;
    }

    private SavePriceEntriesRequest body(SavePriceEntriesRequest.Entry... entries) {
        return new SavePriceEntriesRequest(UUID.randomUUID(), observedAt, List.of(entries));
    }

    private SavePriceEntriesRequest.Entry entry(
            Product product, Long sku, String name, Integer grams) {
        return new SavePriceEntriesRequest.Entry(
                product.getId(),
                sku,
                name,
                grams == null ? null : BigDecimal.valueOf(grams),
                new BigDecimal("79.90"),
                false);
    }

    @Test
    void prioritizesProductsUsedInMoreRecipesAndDoesNotGuessOffersOrPrices() {
        var page = service.list(store.getId(), true, prefix, 0, 20);
        assertEquals(2, page.totalElements());
        assertEquals(rice.getId(), page.content().getFirst().productId());
        assertEquals(2, page.content().getFirst().recipeCount());
        assertTrue(page.content().stream().allMatch(p -> p.offers().isEmpty()));
        assertEquals(1, service.list(store.getId(), true, prefix, 1, 1).content().size());
    }

    @Test
    void promoInvalidatedAndInactiveOffersDoNotHideMissingPrices() {
        var sku = sku(rice, 500);
        var regular =
                new PriceObservation(sku, BigDecimal.TEN, observedAt, PriceSource.MANUAL, false);
        em.persist(regular);
        regular.invalidate("Ошибка", user.getEmail());
        em.persist(new PriceObservation(sku, BigDecimal.ONE, observedAt, PriceSource.MANUAL, true));
        em.flush();
        assertEquals(2, service.list(store.getId(), true, prefix, 0, 20).totalElements());
        var offer =
                service.list(store.getId(), true, prefix, 0, 20)
                        .content()
                        .getFirst()
                        .offers()
                        .getFirst();
        assertNull(offer.price());
        var valid =
                new PriceObservation(
                        sku, new BigDecimal("40"), observedAt, PriceSource.MANUAL, false);
        em.persist(valid);
        em.flush();
        assertEquals(1, service.list(store.getId(), true, prefix, 0, 20).totalElements());
        assertEquals(
                new BigDecimal("40"),
                service.list(store.getId(), false, prefix, 0, 20)
                        .content()
                        .getFirst()
                        .offers()
                        .getFirst()
                        .price());
        sku.changeActivity(false);
        em.flush();
        assertEquals(2, service.list(store.getId(), true, prefix, 0, 20).totalElements());
    }

    @Test
    void createsPackagesAndPricesInOneRequestAndIdenticalRetryReturnsSameIds() throws Exception {
        var existing = sku(rice, 500);
        em.flush();
        var request =
                body(
                        entry(rice, existing.getId(), null, null),
                        entry(oats, null, oats.getName(), 450));
        var first = service.save(user.getEmail(), store.getId(), request);
        var repeat = service.save(user.getEmail(), store.getId(), request);
        assertEquals(first, repeat);
        assertEquals(2, first.savedCount());
        assertFalse(first.entries().getFirst().createdStoreProduct());
        assertTrue(first.entries().getLast().createdStoreProduct());
        assertEquals(
                2,
                ((Number)
                                em.createNativeQuery(
                                                "select count(*) from price_observations po join"
                                                    + " store_products sp on"
                                                    + " sp.id=po.store_product_id where"
                                                    + " sp.store_id=:id")
                                        .setParameter("id", store.getId())
                                        .getSingleResult())
                        .intValue());
        assertEquals(
                observedAt,
                em.find(PriceObservation.class, first.entries().getFirst().priceObservationId())
                        .getObservedAt());
        assertEquals(
                PriceSource.MANUAL,
                em.find(PriceObservation.class, first.entries().getFirst().priceObservationId())
                        .getPriceSource());
        mvc.perform(
                        post("/api/stores/" + store.getId() + "/price-entry")
                                .with(user(user.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.savedCount").value(2));
    }

    @Test
    void exactExistingPackageIsReusedAndDifferentWeightCreatesAnother() {
        var sku = sku(rice, 500);
        em.flush();
        var first =
                service.save(
                        user.getEmail(),
                        store.getId(),
                        body(entry(rice, null, rice.getName(), 500)));
        assertEquals(sku.getId(), first.entries().getFirst().storeProductId());
        assertFalse(first.entries().getFirst().createdStoreProduct());
        var second =
                service.save(
                        user.getEmail(),
                        store.getId(),
                        body(entry(rice, null, rice.getName(), 450)));
        assertNotEquals(sku.getId(), second.entries().getFirst().storeProductId());
    }

    @Test
    void requestKeyCannotBeReusedWithChangedPayload() {
        var request = body(entry(rice, null, rice.getName(), 500));
        service.save(user.getEmail(), store.getId(), request);
        var changed =
                new SavePriceEntriesRequest(
                        request.requestId(),
                        observedAt,
                        List.of(entry(oats, null, oats.getName(), 500)));
        assertThrows(
                PriceEntryConflictException.class,
                () -> service.save(user.getEmail(), store.getId(), changed));
    }

    @Test
    void validatesDynamicFieldsAndRejectsWrongProductOrInactivePackage() {
        var sku = sku(rice, 500);
        em.flush();
        assertThrows(
                PriceEntryConflictException.class,
                () ->
                        service.save(
                                user.getEmail(),
                                store.getId(),
                                body(entry(oats, sku.getId(), null, null))));
        sku.changeActivity(false);
        em.flush();
        assertThrows(
                PriceEntryConflictException.class,
                () ->
                        service.save(
                                user.getEmail(),
                                store.getId(),
                                body(entry(rice, sku.getId(), null, null))));
        assertThrows(
                InvalidPriceEntryException.class,
                () ->
                        service.save(
                                user.getEmail(),
                                store.getId(),
                                body(entry(oats, null, null, null))));
    }

    @Test
    void validatesRequestAndRequiresAuthenticationAndCsrf() throws Exception {
        var path = "/api/stores/" + store.getId() + "/price-entry";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var request = body(entry(rice, null, rice.getName(), 500));
        mvc.perform(
                        post(path)
                                .with(user(user.getEmail()).roles("CATALOG_EDITOR"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(path)
                                .with(user(user.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"requestId\":\""
                                                + UUID.randomUUID()
                                                + "\",\"observedAt\":\"2026-01-01T00:00:00Z\",\"entries\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get(path)
                                .with(user(user.getEmail()).roles("CATALOG_EDITOR"))
                                .param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get(path)
                                .with(user(user.getEmail()).roles("CATALOG_EDITOR"))
                                .param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get(path)
                                .with(user(user.getEmail()).roles("CATALOG_EDITOR"))
                                .param("q", prefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void rejectsFractionalPackageWeightAndOverPreciseMoney() throws Exception {
        var path = "/api/stores/" + store.getId() + "/price-entry";
        String json = mapper.writeValueAsString(body(entry(rice, null, rice.getName(), 500)));
        assertTrue(json.contains("\"packageWeightG\":500"));
        for (String invalid :
                List.of(
                        json.replace("\"packageWeightG\":500", "\"packageWeightG\":1.5"),
                        json.replace("79.90", "79.901"),
                        json.replace("79.90", "0"))) {
            mvc.perform(
                            post(path)
                                    .with(user(user.getEmail()).roles("CATALOG_EDITOR"))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(invalid))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void literalSearchDoesNotTreatPercentAsWildcardAndUnknownStoreIsNotFound() {
        assertEquals(0, service.list(store.getId(), true, prefix + "%", 0, 20).totalElements());
        assertThrows(
                StoreNotFoundException.class, () -> service.list(Long.MAX_VALUE, true, "", 0, 20));
        em.createNativeQuery("update stores set active=false where id=:id")
                .setParameter("id", store.getId())
                .executeUpdate();
        em.clear();
        assertThrows(
                StoreInactiveException.class, () -> service.list(store.getId(), true, "", 0, 20));
    }
}
