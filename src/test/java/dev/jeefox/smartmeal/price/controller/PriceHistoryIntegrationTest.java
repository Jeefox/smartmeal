package dev.jeefox.smartmeal.price.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.price.entity.PriceObservation;
import dev.jeefox.smartmeal.price.entity.PriceSource;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.store.entity.RetailChain;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.entity.StoreProduct;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@SpringBootTest
@Transactional
class PriceHistoryIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;
    private StoreProduct sku;
    private StoreProduct empty;
    private Long oldId;
    private Long regularId;
    private Long promotionalId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ProductCategory category = new ProductCategory("HISTORY_" + UUID.randomUUID(), "Тест");
        em.persist(category);
        Product product =
                new Product(
                        category,
                        "Рис",
                        null,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        em.persist(product);
        RetailChain chain = new RetailChain("HISTORY_" + UUID.randomUUID(), "Тест");
        em.persist(chain);
        Store store = new Store(chain, "Тест", "Тестовый адрес");
        em.persist(store);
        sku = new StoreProduct(store, product, "Рис 500 г", 500);
        empty = new StoreProduct(store, product, "Рис 800 г", 800);
        StoreProduct other = new StoreProduct(store, product, "Другой SKU", 1000);
        em.persist(sku);
        em.persist(empty);
        em.persist(other);
        // Created last, but observed earlier: observedAt must take precedence over ID.
        Instant recent = Instant.parse("2026-01-02T09:00:00Z");
        regularId = price(sku, recent, false, "90.00");
        promotionalId = price(sku, recent, true, "70.00");
        oldId = price(sku, recent.minusSeconds(86400), false, "80.00");
        price(other, recent, false, "999.00");
        em.flush();
        em.clear();
    }

    @Test
    void returnsOnlySelectedSkuInObservedTimeThenIdDescendingOrder() throws Exception {
        mvc.perform(request(sku.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").value(promotionalId))
                .andExpect(jsonPath("$.content[0].promotional").value(true))
                .andExpect(jsonPath("$.content[0].price").value(70))
                .andExpect(jsonPath("$.content[0].priceSource").value("MANUAL"))
                .andExpect(jsonPath("$.content[1].id").value(regularId))
                .andExpect(jsonPath("$.content[1].promotional").value(false))
                .andExpect(jsonPath("$.content[2].id").value(oldId));
    }

    @Test
    void paginatesAndPreservesTotalOnPageBeyondEnd() throws Exception {
        mvc.perform(request(sku.getId()).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(regularId))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(request(sku.getId()).param("page", "3").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void returnsEmptyHistoryForExistingSkuWithoutObservations() throws Exception {
        mvc.perform(request(empty.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void keepsHistoryReadableAfterSkuAndStoreAreDeactivated() throws Exception {
        em.createNativeQuery("update store_products set active=false where id=:id")
                .setParameter("id", sku.getId())
                .executeUpdate();
        em.createNativeQuery("update stores set active=false where id=:id")
                .setParameter("id", sku.getStore().getId())
                .executeUpdate();
        em.clear();
        mvc.perform(request(sku.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void rejectsMissingSkuAndAnonymousRequest() throws Exception {
        mvc.perform(request(Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/store-products/{id}/prices", sku.getId()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,abc", "size,0", "size,101", "size,abc"})
    void rejectsInvalidPagination(String key, String value) throws Exception {
        mvc.perform(request(sku.getId()).param(key, value)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonPositiveSkuId() throws Exception {
        mvc.perform(request(0L)).andExpect(status().isBadRequest());
    }

    @Test
    void invalidationPreservesHistoryAndFallsBackToPreviousPrice() throws Exception {
        String firstResponse =
                mvc.perform(invalidate(sku.getId(), promotionalId, "  Ошибка ввода  ").with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.invalidated").value(true))
                        .andExpect(jsonPath("$.invalidatedAt").isNotEmpty())
                        .andExpect(jsonPath("$.invalidationReason").value("Ошибка ввода"))
                        .andExpect(jsonPath("$.price").value(70))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        em.flush();
        em.clear();
        PriceObservation saved = em.find(PriceObservation.class, promotionalId);
        assertEquals("history-test", saved.getInvalidatedBy());
        Instant firstInvalidation = saved.getInvalidatedAt();
        mvc.perform(invalidate(sku.getId(), promotionalId, "Другая причина").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invalidationReason").value("Ошибка ввода"))
                .andExpect(content().json(firstResponse));
        assertEquals(firstInvalidation, saved.getInvalidatedAt());
        mvc.perform(
                        get("/api/store-products/{id}/prices/latest", sku.getId())
                                .with(user("history-test").roles("CATALOG_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(regularId));
        mvc.perform(request(sku.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].invalidated").value(true));
        mvc.perform(invalidate(sku.getId(), regularId, "Ошибка").with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(invalidate(sku.getId(), oldId, "Ошибка").with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/store-products/{id}/prices/latest", sku.getId())
                                .with(user("history-test").roles("CATALOG_EDITOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidationRequiresCsrfAndAuthentication() throws Exception {
        mvc.perform(invalidate(sku.getId(), promotionalId, "Ошибка"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(
                                        "/api/store-products/{sku}/prices/{price}/invalidate",
                                        sku.getId(),
                                        promotionalId)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Ошибка\"}"))
                .andExpect(status().isUnauthorized());
        assertNull(em.find(PriceObservation.class, promotionalId).getInvalidatedAt());
    }

    @Test
    void invalidationRejectsWrongSkuAndMissingPrice() throws Exception {
        mvc.perform(invalidate(empty.getId(), promotionalId, "Ошибка").with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(invalidate(sku.getId(), Long.MAX_VALUE, "Ошибка").with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(invalidate(Long.MAX_VALUE, promotionalId, "Ошибка").with(csrf()))
                .andExpect(status().isNotFound());
        assertNull(em.find(PriceObservation.class, promotionalId).getInvalidatedAt());
    }

    @Test
    void invalidationRejectsBlankAndLongReasonAndInvalidId() throws Exception {
        mvc.perform(invalidate(sku.getId(), promotionalId, "   ").with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(invalidate(sku.getId(), promotionalId, "x".repeat(301)).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(invalidate(sku.getId(), 0L, "Ошибка").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filtersHistoryByInvalidationAndPromotionTogether() throws Exception {
        mvc.perform(invalidate(sku.getId(), regularId, "Ошибка").with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(request(sku.getId()).param("invalidated", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(regularId));
        mvc.perform(
                        request(sku.getId())
                                .param("invalidated", "false")
                                .param("promotional", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(oldId));
        mvc.perform(request(sku.getId()).param("invalidated", "false").param("promotional", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(promotionalId));
        mvc.perform(request(sku.getId()).param("invalidated", "true").param("promotional", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(
                        request(sku.getId())
                                .param("promotional", "false")
                                .param("size", "1")
                                .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(oldId));
        mvc.perform(request(sku.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void historySurvivesActivityChangesViaApi() throws Exception {
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/store-products/{id}/activity", sku.getId())
                                .with(user("history-test").roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(request(sku.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void rejectsInvalidHistoryFilterValues() throws Exception {
        mvc.perform(request(sku.getId()).param("invalidated", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(sku.getId()).param("promotional", "invalid"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder invalidate(Long skuId, Long priceId, String reason) {
        return post("/api/store-products/{sku}/prices/{price}/invalidate", skuId, priceId)
                .with(user("history-test").roles("CATALOG_EDITOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}");
    }

    private MockHttpServletRequestBuilder request(Long id) {
        return get("/api/store-products/{id}/prices", id)
                .with(user("history-test").roles("CATALOG_EDITOR"));
    }

    private Long price(StoreProduct sku, Instant time, boolean promotional, String amount) {
        PriceObservation value =
                new PriceObservation(
                        sku, new BigDecimal(amount), time, PriceSource.MANUAL, promotional);
        em.persist(value);
        return value.getId();
    }
}
