package dev.jeefox.smartmeal.recipe.controller;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class RecipeCandidatesIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;
    private Product product;
    private Recipe quick;
    private Recipe boundary;
    private Recipe soup;
    private Recipe unclassified;
    private String tag;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tag = "CAND_" + UUID.randomUUID();
        ProductCategory category = new ProductCategory(tag, "Тест");
        em.persist(category);
        product = new Product(category, "Продукт", null, 100, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        em.persist(product);
        quick = recipe("A завтрак", 10, true, MealType.BREAKFAST);
        boundary = recipe("B завтрак-перекус", 15, true, MealType.BREAKFAST, MealType.SNACK);
        recipe("C долгий завтрак", 60, true, MealType.BREAKFAST);
        soup = recipe("D борщ", 10, true, MealType.LUNCH, MealType.DINNER);
        unclassified = recipe("E без разметки", 5, true);
        recipe("F без ингредиентов", 5, false, MealType.BREAKFAST);
        recipe("G без готовки", 0, true, MealType.SNACK);
        em.flush();
        em.clear();
    }

    @Test
    void excludesSlowWrongMealUnclassifiedAndEmptyRecipes() throws Exception {
        mvc.perform(candidates("BREAKFAST", "15")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(quick.getId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(boundary.getId().toString()))
                .andExpect(jsonPath("$.content[1].mealTypes[0]").value("BREAKFAST"))
                .andExpect(jsonPath("$.content[1].mealTypes[1]").value("SNACK"));
    }

    @Test
    void supportsMultipleMealTypesAndZeroTime() throws Exception {
        mvc.perform(candidates("DINNER", "10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(soup.getId().toString()));
        mvc.perform(candidates("SNACK", "0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].preparationTimeMin").value(0));
    }

    @Test
    void paginatesWithoutDuplicatesForMultiTypeRecipe() throws Exception {
        mvc.perform(candidates("BREAKFAST", "15").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(boundary.getId().toString()));
        mvc.perform(candidates("BREAKFAST", "15").param("page", "2").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void searchesLiteralSubstringIgnoringCase() throws Exception {
        recipe("спец %_!", 10, true, MealType.BREAKFAST);
        em.flush();
        em.clear();
        mvc.perform(get("/api/recipes/candidates").with(user("test"))
                        .param("mealType", "BREAKFAST").param("maxPreparationTimeMin", "15")
                        .param("q", "  " + tag.toLowerCase() + " спец %_!  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void emptyResultIsSuccessfulAndGeneralCatalogueKeepsUnclassifiedRecipes() throws Exception {
        mvc.perform(candidates("LUNCH", "0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/recipes").param("q", tag + " E").with(user("test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].mealTypes").isEmpty());
    }

    @Test
    void classifiesExistingRecipeAndCanClearClassification() throws Exception {
        mvc.perform(classify(unclassified.getId(), "{\"mealTypes\":[\"BREAKFAST\",\"SNACK\",\"BREAKFAST\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mealTypes.length()").value(2));
        em.flush();
        em.clear();
        mvc.perform(candidates("BREAKFAST", "15")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(classify(unclassified.getId(), "{\"mealTypes\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mealTypes").isEmpty());
        em.flush();
        em.clear();
        mvc.perform(candidates("BREAKFAST", "15")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void createsClassifiedRecipeAndKeepsLegacyCreationCompatible() throws Exception {
        mvc.perform(post("/api/recipes").with(user("test")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(",\"mealTypes\":[\"BREAKFAST\"]")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mealTypes[0]").value("BREAKFAST"));
        mvc.perform(post("/api/recipes").with(user("test")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mealTypes").isEmpty());
    }

    @Test
    void detailsExposeMealTypesWithoutChangingNutrition() throws Exception {
        mvc.perform(get("/api/recipes/{id}", quick.getId()).with(user("test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mealTypes[0]").value("BREAKFAST"))
                .andExpect(jsonPath("$.nutritionTotal.calories").value(100));
    }

    @Test
    void validatesClassificationAndProtectsMutations() throws Exception {
        for (String body : new String[]{"{}", "{\"mealTypes\":null}", "{\"mealTypes\":[null]}", "{\"mealTypes\":[\"SOUP\"]}"}) {
            mvc.perform(classify(quick.getId(), body)).andExpect(status().isBadRequest());
        }
        mvc.perform(classify(UUID.randomUUID(), "{\"mealTypes\":[]}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/recipes/{id}/meal-types", quick.getId()).with(user("test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mealTypes\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/recipes/candidates").param("mealType", "BREAKFAST")
                        .param("maxPreparationTimeMin", "15"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"mealType,SOUP", "maxPreparationTimeMin,-1", "maxPreparationTimeMin,abc", "page,-1", "size,0", "size,101"})
    void rejectsInvalidCandidateParameters(String key, String value) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/recipes/candidates").with(user("test")).param("q", tag);
        if (!key.equals("mealType")) request.param("mealType", "BREAKFAST");
        if (!key.equals("maxPreparationTimeMin")) request.param("maxPreparationTimeMin", "15");
        mvc.perform(request.param(key, value)).andExpect(status().isBadRequest());
    }

    @Test
    void requiresMealTypeAndTimeLimit() throws Exception {
        mvc.perform(get("/api/recipes/candidates").with(user("test")).param("mealType", "BREAKFAST"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/recipes/candidates").with(user("test")).param("maxPreparationTimeMin", "15"))
                .andExpect(status().isBadRequest());
    }

    private Recipe recipe(String name, int minutes, boolean withIngredients, MealType... types) {
        Recipe value = new Recipe(tag + " " + name, null, "Приготовить", minutes, 1);
        value.changeMealTypes(Set.of(types));
        em.persist(value);
        if (withIngredients) em.persist(new RecipeIngredient(value, product, new BigDecimal("100"), 1));
        return value;
    }

    private MockHttpServletRequestBuilder candidates(String type, String minutes) {
        return get("/api/recipes/candidates").with(user("test")).param("q", tag)
                .param("mealType", type).param("maxPreparationTimeMin", minutes);
    }

    private MockHttpServletRequestBuilder classify(UUID id, String body) {
        return put("/api/recipes/{id}/meal-types", id).with(user("test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String createBody(String mealTypes) {
        return "{\"name\":\"" + tag + " новый\",\"instructions\":\"Приготовить\","
                + "\"preparationTimeMin\":10,\"servings\":1,\"ingredients\":[{\"productId\":"
                + product.getId() + ",\"quantityG\":100}]" + mealTypes + "}";
    }
}
