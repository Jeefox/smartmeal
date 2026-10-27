package dev.jeefox.smartmeal.kitchen;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.recipe.entity.*;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import dev.jeefox.smartmeal.user.entity.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.*;

@SpringBootTest
@Transactional
class KitchenIntegrationTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext web;
    @Autowired RecipeRepository recipes;
    MockMvc mvc;
    User a, b;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        a = new User("kitchen-a-" + UUID.randomUUID() + "@example.invalid", "test");
        b = new User("kitchen-b-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(a);
        em.persist(b);
        em.persist(new UserProfile(2000, a));
        em.persist(new UserProfile(2000, b));
        em.flush();
    }

    long id(String code) {
        return jdbc.queryForObject("select id from equipment where code=?", Long.class, code);
    }

    void choose(User u, List<Long> ids) throws Exception {
        mvc.perform(
                        put("/api/me/kitchen")
                                .with(user(u.getEmail()))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"equipmentIds\":" + ids + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void firstChoiceIsPersistedEvenIfEmptyAndReportedInProfile() throws Exception {
        mvc.perform(get("/api/me/kitchen").with(user(a.getEmail())))
                .andExpect(jsonPath("$.configured").value(false));
        choose(a, List.of());
        mvc.perform(get("/api/me/kitchen").with(user(a.getEmail())))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.equipmentIds").isEmpty());
        mvc.perform(get("/api/me/profile").with(user(a.getEmail())))
                .andExpect(jsonPath("$.equipmentConfigured").value(true));
        mvc.perform(get("/api/me/profile").with(user(b.getEmail())))
                .andExpect(jsonPath("$.equipmentConfigured").value(false));
    }

    @Test
    void changingKitchenPreservesAllergiesAndOtherAccounts() throws Exception {
        long allergen =
                jdbc.queryForObject(
                        "insert into allergens(code,name) values(?,?) returning id",
                        Long.class,
                        "K_" + UUID.randomUUID(),
                        "Тест");
        jdbc.update(
                "insert into user_allergies(user_id,allergen_id) values(?,?)", a.getId(), allergen);
        choose(a, List.of(id("MICROWAVE")));
        choose(b, List.of(id("OVEN")));
        choose(a, List.of(id("AIR_FRYER")));
        assertEquals(
                List.of(id("OVEN")),
                jdbc.queryForList(
                        "select equipment_id from user_equipment where user_id=?",
                        Long.class,
                        b.getId()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from user_allergies where user_id=?",
                        Integer.class,
                        a.getId()));
    }

    @Test
    void invalidAndDuplicateEquipmentDoNotChangeSavedChoice() throws Exception {
        choose(a, List.of(id("OVEN")));
        for (String values :
                List.of("[9223372036854775807]", "[" + id("OVEN") + "," + id("OVEN") + "]", "null"))
            mvc.perform(
                            put("/api/me/kitchen")
                                    .with(user(a.getEmail()))
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content("{\"equipmentIds\":" + values + "}"))
                    .andExpect(status().isBadRequest());
        assertEquals(
                List.of(id("OVEN")),
                jdbc.queryForList(
                        "select equipment_id from user_equipment where user_id=?",
                        Long.class,
                        a.getId()));
    }

    @Test
    void kitchenRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/me/kitchen")).andExpect(status().isUnauthorized());
        mvc.perform(
                        put("/api/me/kitchen")
                                .with(user(a.getEmail()))
                                .contentType("application/json")
                                .content("{\"equipmentIds\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allergySettingsCannotAccidentallyOverwriteKitchen() throws Exception {
        choose(a, List.of(id("MICROWAVE")));
        mvc.perform(
                        put("/api/me/planning-preferences")
                                .with(user(a.getEmail()))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"allergenIds\":[],\"excludedProductIds\":[],\"equipmentIds\":null}"))
                .andExpect(status().isOk());
        assertEquals(
                List.of(id("MICROWAVE")),
                jdbc.queryForList(
                        "select equipment_id from user_equipment where user_id=?",
                        Long.class,
                        a.getId()));
    }

    @Test
    void plannerNeverUsesAnUnavailableAppliance() throws Exception {
        var category = new ProductCategory("K_" + UUID.randomUUID(), "Тест");
        em.persist(category);
        var product =
                new Product(
                        category,
                        "Кухня",
                        null,
                        100,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE);
        em.persist(product);
        Map<String, UUID> recipeIds = new HashMap<>();
        for (String code : List.of("OVEN", "MICROWAVE", "AIR_FRYER")) {
            var recipe = new Recipe("Тест кухни " + code, null, "Приготовить", 10, 1);
            recipe.changeMealTypes(Set.of(MealType.LUNCH));
            em.persist(recipe);
            em.persist(new RecipeIngredient(recipe, product, new BigDecimal("100"), 1));
            em.flush();
            jdbc.update(
                    "insert into recipe_equipment(recipe_id,equipment_id) values(?,?)",
                    recipe.getId(),
                    id(code));
            recipeIds.put(code, recipe.getId());
        }
        for (String code : recipeIds.keySet()) {
            choose(a, List.of(id(code)));
            var eligible =
                    recipes
                            .findPreviewCandidates(a.getId(), "LUNCH", 60, PageRequest.of(0, 500))
                            .stream()
                            .map(Recipe::getId)
                            .toList();
            assertTrue(eligible.contains(recipeIds.get(code)));
            for (var other : recipeIds.entrySet())
                if (!other.getKey().equals(code)) assertFalse(eligible.contains(other.getValue()));
        }
    }
}
