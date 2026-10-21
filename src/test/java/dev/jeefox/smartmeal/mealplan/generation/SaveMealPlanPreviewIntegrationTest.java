package dev.jeefox.smartmeal.mealplan.generation;

import dev.jeefox.smartmeal.exception.MealPlanAlreadyExistsException;
import dev.jeefox.smartmeal.mealplan.dto.*;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanEntryRepository;
import dev.jeefox.smartmeal.mealplan.service.MealPlanPreviewService;
import dev.jeefox.smartmeal.mealplan.service.MealPlanService;
import dev.jeefox.smartmeal.mealplan.service.SaveMealPlanPreviewService;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class SaveMealPlanPreviewIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate tx;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;
    @Autowired private MealPlanPreviewService previews;
    @Autowired private SaveMealPlanPreviewService saves;
    @Autowired private MealPlanService plans;
    @MockitoSpyBean private MealPlanEntryRepository entries;
    private MockMvc mvc;
    private UUID userId;
    private String email;
    private Long productId;
    private Long categoryId;
    private final List<UUID> recipeIds = new ArrayList<>();
    private final LocalDate date = LocalDate.of(2034, 3, 1);
    private SaveMealPlanPreviewRequest request;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tx.executeWithoutResult(status -> {
            User user = new User("save-preview-" + UUID.randomUUID() + "@example.invalid", "unused-test-hash");
            em.persist(user);
            userId = user.getId(); email = user.getEmail();
            em.persist(new UserProfile(2000, user));
            ProductCategory category = new ProductCategory("SAVE_" + UUID.randomUUID(), "Тест");
            em.persist(category); categoryId = category.getId();
            Product product = new Product(category, "Тестовый продукт", null, 100, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
            product.updateShoppingDetails("Название покупки", new BigDecimal("0.5"));
            em.persist(product); productId = product.getId();
            recipe(product, MealType.BREAKFAST, 10, 500);
            recipe(product, MealType.LUNCH, 30, 800);
            recipe(product, MealType.DINNER, 40, 700);
            em.flush();
            em.createNativeQuery("insert into user_excluded_products(user_id,product_id) select :u,id from products where id<>:p")
                    .setParameter("u", userId).setParameter("p", productId).executeUpdate();
        });
        PreviewMealPlanRequest options = new PreviewMealPlanRequest(date, null, null, null);
        MealPlanPreviewResponse preview = previews.preview(email, options);
        request = new SaveMealPlanPreviewRequest(options, preview.dailyCalorieTarget(), preview.entries().stream()
                .map(e -> new SaveMealPlanPreviewRequest.Entry(e.mealType(), e.recipeId(), e.portions(), e.recipeFingerprint())).toList());
    }

    @AfterEach
    void cleanUp() {
        reset(entries);
        tx.executeWithoutResult(status -> {
            if (userId != null) em.createQuery("delete from User u where u.id=:id").setParameter("id", userId).executeUpdate();
            if (!recipeIds.isEmpty()) em.createQuery("delete from Recipe r where r.id in :ids").setParameter("ids", recipeIds).executeUpdate();
            if (productId != null) em.createQuery("delete from Product p where p.id=:id").setParameter("id", productId).executeUpdate();
            if (categoryId != null) em.createQuery("delete from ProductCategory c where c.id=:id").setParameter("id", categoryId).executeUpdate();
        });
    }

    @Test
    void savesSelectedDayAsDraftWithFrozenSnapshotsAndMatchingNutrition() throws Exception {
        mvc.perform(save(request)).andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/me/meal-plans?date=" + date))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.nutritionTotal.calories").value(2000))
                .andExpect(jsonPath("$.dailyCalorieTargetSnapshot").doesNotExist());
        MealPlanResponse saved = plans.findByDate(email, date);
        assertEquals(request.entries().stream().map(SaveMealPlanPreviewRequest.Entry::recipeId).toList(),
                saved.entries().stream().map(e -> e.recipeSnapshot().recipeId()).toList());
        assertEquals(new BigDecimal("0.500000"), saved.entries().getFirst().recipeSnapshot().ingredients().getFirst().edibleYield());
        tx.executeWithoutResult(status -> em.find(Product.class, productId).updateShoppingDetails("Другая покупка", BigDecimal.ONE));
        assertEquals("Название покупки", plans.findByDate(email, date).entries().getFirst().recipeSnapshot().ingredients().getFirst().shoppingName());
    }

    @Test
    void doesNotRegenerateOrReplaceExistingDraftOrConfirmedMenu() throws Exception {
        MealPlanResponse first = saves.save(email, request);
        mvc.perform(save(request)).andExpect(status().isConflict());
        assertEquals(first.id(), plans.findByDate(email, date).id());
        plans.confirmMenu(email, first.id());
        mvc.perform(save(request)).andExpect(status().isConflict());
        assertEquals(3, plans.findByDate(email, date).entries().size());
    }

    @Test
    void detectsChangedProductNutritionAndWritesNothing() throws Exception {
        tx.executeWithoutResult(status -> em.createQuery("update Product p set p.caloriesPer100g=101 where p.id=:id")
                .setParameter("id", productId).executeUpdate());
        mvc.perform(save(request)).andExpect(status().isConflict());
        assertEquals(0, menuCount());
    }

    @Test
    void detectsChangedShoppingYieldAndWritesNothing() throws Exception {
        tx.executeWithoutResult(status -> em.find(Product.class, productId).updateShoppingDetails(null, BigDecimal.ONE));
        mvc.perform(save(request)).andExpect(status().isConflict());
        assertEquals(0, menuCount());
    }

    @Test
    void detectsChangedRecipe() throws Exception {
        tx.executeWithoutResult(status -> em.createQuery("update Recipe r set r.instructions='Изменённые шаги' where r.id=:id")
                .setParameter("id", recipeIds.getFirst()).executeUpdate());
        mvc.perform(save(request)).andExpect(status().isConflict());
        assertEquals(0, menuCount());
    }

    @Test
    void detectsChangedGoal() throws Exception {
        tx.executeWithoutResult(status -> em.find(UserProfile.class, userId).updateDailyCalorieTarget(2100));
        mvc.perform(save(request)).andExpect(status().isConflict());
        assertEquals(0, menuCount());
    }

    @Test
    void rejectsTamperedFingerprintAndUnknownRecipe() throws Exception {
        var first = request.entries().getFirst();
        var selected = new ArrayList<>(request.entries());
        selected.set(0, new SaveMealPlanPreviewRequest.Entry(first.mealType(), first.recipeId(), first.portions(), "0".repeat(64)));
        mvc.perform(save(withEntries(selected))).andExpect(status().isConflict());
        selected.set(0, new SaveMealPlanPreviewRequest.Entry(first.mealType(), UUID.randomUUID(), first.portions(), first.recipeFingerprint()));
        mvc.perform(save(withEntries(selected))).andExpect(status().isNotFound());
        assertEquals(0, menuCount());
    }

    @Test
    void rechecksClassificationAndPersonalRestrictions() throws Exception {
        tx.executeWithoutResult(status -> em.find(Recipe.class, recipeIds.getFirst()).changeMealTypes(Set.of(MealType.LUNCH)));
        mvc.perform(save(request)).andExpect(status().isConflict());
        tx.executeWithoutResult(status -> {
            em.find(Recipe.class, recipeIds.getFirst()).changeMealTypes(Set.of(MealType.BREAKFAST));
            em.createNativeQuery("insert into user_excluded_products(user_id,product_id) values(:u,:p)")
                    .setParameter("u", userId).setParameter("p", productId).executeUpdate();
        });
        mvc.perform(save(request)).andExpect(status().isConflict());
        assertEquals(0, menuCount());
    }

    @Test
    void rejectsWrongTypesDuplicateRecipesAndInvalidPortionStep() throws Exception {
        var original = request.entries().getFirst();
        var invalid = new ArrayList<>(request.entries());
        invalid.set(0, new SaveMealPlanPreviewRequest.Entry(MealType.SNACK, original.recipeId(), original.portions(), original.recipeFingerprint()));
        mvc.perform(save(withEntries(invalid))).andExpect(status().isBadRequest());
        invalid = new ArrayList<>(request.entries());
        invalid.set(1, new SaveMealPlanPreviewRequest.Entry(MealType.LUNCH, original.recipeId(), original.portions(), original.recipeFingerprint()));
        mvc.perform(save(withEntries(invalid))).andExpect(status().isBadRequest());
        invalid = new ArrayList<>(request.entries());
        invalid.set(0, new SaveMealPlanPreviewRequest.Entry(original.mealType(), original.recipeId(), new BigDecimal("0.60"), original.recipeFingerprint()));
        mvc.perform(save(withEntries(invalid))).andExpect(status().isBadRequest());
        assertEquals(0, menuCount());
    }

    @Test
    void validatesShapeAndRequiresSessionAndCsrf() throws Exception {
        mvc.perform(post("/api/me/meal-plans/preview/save").with(user(email)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/meal-plans/preview/save").with(user(email))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/me/meal-plans/preview/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        assertEquals(0, menuCount());
    }

    @Test
    void rollsBackPlanWhenWritingEntriesFails() {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Simulated persistence failure")).when(entries).saveAll(anyList());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> saves.save(email, request));
        assertEquals(0, menuCount());
    }

    @Test
    void concurrentSavesCreateExactlyOneMenu() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                try {
                    saves.save(email, request);
                    return 201;
                } catch (MealPlanAlreadyExistsException expected) {
                    return 409;
                }
            }));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var result : results) statuses.add(result.get(20, TimeUnit.SECONDS));
            assertEquals(List.of(201, 409), statuses.stream().sorted().toList());
            assertEquals(1, menuCount());
            assertEquals(3, plans.findByDate(email, date).entries().size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private SaveMealPlanPreviewRequest withEntries(List<SaveMealPlanPreviewRequest.Entry> selected) {
        return new SaveMealPlanPreviewRequest(request.options(), request.dailyCalorieTarget(), selected);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder save(SaveMealPlanPreviewRequest body) {
        return post("/api/me/meal-plans/preview/save").with(user(email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private long menuCount() {
        return tx.execute(status -> em.createQuery("select count(p) from MealPlan p where p.user.id=:id", Long.class)
                .setParameter("id", userId).getSingleResult());
    }

    private void recipe(Product product, MealType type, int minutes, int grams) {
        Recipe recipe = new Recipe("Save test " + UUID.randomUUID(), null, "Приготовить", minutes, 1);
        recipe.changeMealTypes(Set.of(type));
        em.persist(recipe);
        recipeIds.add(recipe.getId());
        em.persist(new RecipeIngredient(recipe, product, BigDecimal.valueOf(grams), 1));
    }
}
