package dev.jeefox.smartmeal.product.controller;

import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class ProductShoppingDetailsTest {
    @Autowired private EntityManager em;
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;
    private ProductCategory category;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        category = new ProductCategory("YIELD_" + UUID.randomUUID(), "Тест");
        em.persist(category);
        em.flush();
    }

    @Test
    void createsShoppingDetailsAndDefaultsOldRequests() throws Exception {
        mvc.perform(post("/api/products").with(user("test")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"shoppingName\":\"Бананы\",\"edibleYield\":0.5")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shoppingName").value("Бананы"))
                .andExpect(jsonPath("$.edibleYield").value(0.5));
        mvc.perform(post("/api/products").with(user("test")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shoppingName").value("Банан без кожуры"))
                .andExpect(jsonPath("$.edibleYield").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.1", "1.1", "0.0000001"})
    void rejectsInvalidYield(String yield) throws Exception {
        mvc.perform(post("/api/products").with(user("test")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"edibleYield\":" + yield)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("edibleYield"));
    }

    @Test
    void rejectsBlankShoppingName() throws Exception {
        mvc.perform(post("/api/products").with(user("test")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"shoppingName\":\"   \"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesShoppingDetailsAndKeepsThemForLegacyUpdate() throws Exception {
        Product product = new Product(category, "Банан", null, 90, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        em.persist(product);
        em.flush();
        mvc.perform(put("/api/products/" + product.getId()).with(user("test")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"shoppingName\":\"Бананы\",\"edibleYield\":0.5")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.edibleYield").value(0.5));
        mvc.perform(put("/api/products/" + product.getId()).with(user("test")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shoppingName").value("Бананы"))
                .andExpect(jsonPath("$.edibleYield").value(0.5));
        em.flush();
        em.clear();
        Product saved = em.find(Product.class, product.getId());
        assertEquals("Бананы", saved.getShoppingName());
        assertEquals(0, new BigDecimal("0.5").compareTo(saved.getEdibleYield()));
    }

    private String body(String shoppingFields) {
        return "{\"categoryId\":" + category.getId() + ",\"name\":\"Банан без кожуры\","
                + "\"caloriesPer100g\":90,\"proteinPer100g\":1,\"fatPer100g\":1,\"carbsPer100g\":1"
                + shoppingFields + "}";
    }
}
