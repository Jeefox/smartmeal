package dev.jeefox.smartmeal.store.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import java.util.UUID;

@SpringBootTest
@Transactional
class StoreProductCatalogueTest {
    @Autowired private EntityManager em;
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;
    private Store store;
    private Product rice;
    private Long first;
    private Long second;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ProductCategory category = new ProductCategory("CAT_" + UUID.randomUUID(), "Тест");
        em.persist(category);
        rice =
                new Product(
                        category,
                        "Рис",
                        null,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        Product pasta =
                new Product(
                        category,
                        "Макароны",
                        null,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        em.persist(rice);
        em.persist(pasta);
        RetailChain chain = new RetailChain("CHAIN_" + UUID.randomUUID(), "Тест");
        em.persist(chain);
        store = new Store(chain, "Тест", "Адрес А");
        Store other = new Store(chain, "Тест", "Адрес Б");
        em.persist(store);
        em.persist(other);
        first = pack(store, rice, "Рис 500 г").getId();
        second = pack(store, rice, "Рис 500 г").getId();
        pack(store, pasta, "Макароны 100%_качество");
        pack(other, rice, "Рис чужого магазина");
        StoreProduct inactive = pack(store, rice, "Рис неактивный");
        em.flush();
        em.createNativeQuery("update store_products set active=false where id=:id")
                .setParameter("id", inactive.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    void listsOnlyActiveProductsOfSelectedStoreWithPageMetadata() throws Exception {
        mvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Макароны 100%_качество"))
                .andExpect(jsonPath("$.content[1].id").value(first))
                .andExpect(jsonPath("$.content[2].id").value(second));
    }

    @Test
    void searchesTrimmedNameIgnoringCase() throws Exception {
        mvc.perform(request().param("name", "  рИС  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void filtersByBaseProductWithoutName() throws Exception {
        mvc.perform(request().param("productId", rice.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void combinesFiltersWithAnd() throws Exception {
        mvc.perform(request().param("productId", rice.getId().toString()).param("name", "Макароны"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(request().param("productId", rice.getId().toString()).param("name", "Рис"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void blankNameDoesNotRestrictResults() throws Exception {
        mvc.perform(request().param("name", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void treatsLikeWildcardsAsLiteralCharacters() throws Exception {
        mvc.perform(request().param("name", "%_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(request().param("name", "_%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void paginationIsStableForEqualNamesAndPreservesTotalBeyondLastPage() throws Exception {
        mvc.perform(request().param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(request().param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(second));
        mvc.perform(request().param("page", "3").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void unknownProductFilterReturnsEmptyPage() throws Exception {
        mvc.perform(request().param("productId", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsMissingAndInactiveStores() throws Exception {
        mvc.perform(
                        get("/api/store-products")
                                .param("storeId", Long.toString(Long.MAX_VALUE))
                                .with(user("test").roles("CATALOG_EDITOR")))
                .andExpect(status().isNotFound());
        em.createNativeQuery("update stores set active=false where id=:id")
                .setParameter("id", store.getId())
                .executeUpdate();
        em.clear();
        mvc.perform(request()).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @CsvSource({
        "storeId,0",
        "productId,-1",
        "page,-1",
        "size,0",
        "size,101",
        "page,abc",
        "storeId,abc"
    })
    void rejectsInvalidParameters(String parameter, String value) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/api/store-products").with(user("test").roles("CATALOG_EDITOR"));
        if (!parameter.equals("storeId")) request.param("storeId", store.getId().toString());
        mvc.perform(request.param(parameter, value)).andExpect(status().isBadRequest());
    }

    @Test
    void requiresStoreAndAuthentication() throws Exception {
        mvc.perform(get("/api/store-products").with(user("test").roles("CATALOG_EDITOR")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/store-products").param("storeId", store.getId().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatesAndRestoresSkuWithoutChangingItsIdentityOrWeight() throws Exception {
        mvc.perform(activity(first, "{\"active\":false}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.packageWeightG").value(500));
        mvc.perform(request().param("name", "Рис 500 г"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(request().param("name", "Рис 500 г").param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first));
        mvc.perform(activity(first, "{\"active\":true}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(request().param("name", "Рис 500 г"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void repeatedActivityChangeDoesNotChangeUpdatedAt() throws Exception {
        String firstResponse =
                mvc.perform(activity(first, "{\"active\":false}").with(csrf()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        em.clear();
        var firstUpdate = em.find(StoreProduct.class, first).getUpdatedAt();
        mvc.perform(activity(first, "{\"active\":false}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json(firstResponse));
        em.clear();
        org.junit.jupiter.api.Assertions.assertEquals(
                firstUpdate, em.find(StoreProduct.class, first).getUpdatedAt());
    }

    @Test
    void validatesActivityRequestAndProtectsMutation() throws Exception {
        mvc.perform(activity(first, "{}").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(activity(first, "{\"active\":null}").with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(activity(first, "{\"active\":false}")).andExpect(status().isForbidden());
        mvc.perform(
                        put("/api/store-products/{id}/activity", first)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(activity(Long.MAX_VALUE, "{\"active\":false}").with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(activity(0L, "{\"active\":false}").with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(request().param("active", "invalid")).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder activity(Long id, String body) {
        return put("/api/store-products/{id}/activity", id)
                .with(user("test").roles("CATALOG_EDITOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/store-products")
                .param("storeId", store.getId().toString())
                .with(user("test").roles("CATALOG_EDITOR"));
    }

    private StoreProduct pack(Store store, Product product, String name) {
        StoreProduct result = new StoreProduct(store, product, name, 500);
        em.persist(result);
        return result;
    }
}
