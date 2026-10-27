package dev.jeefox.smartmeal.mealplan.balanced;

import static dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.*;
import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.household.*;
import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.generation.period.*;
import dev.jeefox.smartmeal.mealplan.repository.*;
import dev.jeefox.smartmeal.mealplan.service.*;
import dev.jeefox.smartmeal.pantry.*;
import dev.jeefox.smartmeal.price.entity.*;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.recipe.entity.*;
import dev.jeefox.smartmeal.store.entity.*;
import dev.jeefox.smartmeal.user.entity.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;

@SpringBootTest
@Transactional
class BalancedPlanningIntegrationTest {
    @Autowired org.springframework.web.context.WebApplicationContext web;
    org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired BalancedDataService data;
    @Autowired PlanningContextLoader contexts;
    @Autowired PeriodMenuEvaluator evaluator;
    @Autowired PeriodPlanningService planner;
    @Autowired GenerationCommitService saves;
    @Autowired HouseholdService household;
    @Autowired MealPlanEntryRepository entries;
    @Autowired MealPlanService menus;
    @Autowired PantryService pantry;
    @Autowired NamedParameterJdbcTemplate jdbc;
    User owner;
    Long chain;
    UUID member;
    List<Product> products = new ArrayList<>();

    BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    @BeforeEach
    void setup() {
        mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                        .apply(
                                org.springframework.security.test.web.servlet.setup
                                        .SecurityMockMvcConfigurers.springSecurity())
                        .build();
        owner = new User("balanced-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(owner);
        em.persist(new UserProfile(2200, owner));
        var cat = new ProductCategory("BAL_" + UUID.randomUUID(), "Тест составных блюд");
        em.persist(cat);
        var chainEntity = new RetailChain("BAL_" + UUID.randomUUID(), "Тест сети");
        em.persist(chainEntity);
        chain = chainEntity.getId();
        var store = new Store(chainEntity, "Тула", "Тест");
        em.persist(store);
        int[] kcal = {200, 200, 150, 50};
        int[] protein = {15, 40, 5, 2};
        int[] fiber = {4, 0, 8, 5};
        for (int i = 0; i < 4; i++) {
            var p =
                    new Product(
                            cat,
                            "BAL " + i,
                            null,
                            kcal[i],
                            BigDecimal.valueOf(protein[i]),
                            n("2"),
                            n("10"));
            em.persist(p);
            products.add(p);
            em.flush();
            data.saveFiber(
                    p.getId(),
                    new Fiber(BigDecimal.valueOf(fiber[i]), "Измеренные тестовые данные", "LABEL"));
            var sku = new StoreProduct(store, p, "Тестовая упаковка", 1000);
            em.persist(sku);
            em.persist(
                    new PriceObservation(
                            sku,
                            n("100"),
                            Instant.now().minusSeconds(10),
                            PriceSource.MANUAL,
                            false));
        }
        addRecipe(
                "Завтрак",
                products.get(0),
                Set.of(MealType.BREAKFAST),
                Role.BREAKFAST,
                250,
                200,
                300,
                false,
                false);
        addRecipe(
                "Основное обед",
                products.get(1),
                Set.of(MealType.LUNCH),
                Role.PROTEIN_MAIN,
                250,
                100,
                180,
                false,
                false);
        addRecipe(
                "Основное ужин",
                products.get(1),
                Set.of(MealType.DINNER),
                Role.PROTEIN_MAIN,
                250,
                100,
                180,
                false,
                false);
        addRecipe(
                "Гарнир A",
                products.get(2),
                Set.of(MealType.LUNCH, MealType.DINNER),
                Role.SIDE,
                250,
                100,
                250,
                true,
                false);
        addRecipe(
                "Гарнир B",
                products.get(2),
                Set.of(MealType.LUNCH, MealType.DINNER),
                Role.SIDE,
                250,
                100,
                250,
                true,
                false);
        addRecipe(
                "Овощи",
                products.get(3),
                Set.of(MealType.LUNCH, MealType.DINNER),
                Role.VEGETABLE,
                250,
                100,
                200,
                false,
                true);
        em.flush();
        jdbc.update(
                "insert into user_excluded_products(user_id,product_id) select :u,id from products"
                        + " where id not in(:ids)",
                Map.of("u", owner.getId(), "ids", products.stream().map(Product::getId).toList()));
        member =
                household
                        .create(
                                owner.getEmail(),
                                new HouseholdModels.MemberRequest(
                                        "Мама", 2000, List.of(), List.of()))
                        .id();
        data.saveTargets(
                owner.getEmail(),
                owner.getId(),
                new Targets(0, true, n("80"), n("80"), n("25"), n("650"), "USER_SET"));
        data.saveTargets(
                owner.getEmail(),
                member,
                new Targets(0, true, n("70"), n("70"), n("25"), n("650"), "USER_SET"));
    }

    void addRecipe(
            String name,
            Product p,
            Set<MealType> meals,
            Role role,
            int yield,
            int min,
            int max,
            boolean starch,
            boolean veg) {
        var r = new Recipe(name, null, "Тест приготовления", 5, 1);
        r.changeMealTypes(meals);
        em.persist(r);
        em.persist(new RecipeIngredient(r, p, n("250"), 1));
        em.flush();
        data.saveProfile(
                r.getId(),
                new Profile(
                        role,
                        BigDecimal.valueOf(yield),
                        YieldQuality.MEASURED,
                        "Тестовый измеренный выход",
                        BigDecimal.valueOf(min),
                        BigDecimal.valueOf(max),
                        n("25"),
                        starch,
                        veg));
    }

    Options options() {
        return new Options(
                pantry.today(),
                pantry.today(),
                n("1000"),
                List.of(chain),
                "Тула",
                null,
                null,
                14,
                0,
                true,
                null,
                0L,
                true,
                List.of(member),
                true);
    }

    @Test
    void compositesRespectEveryPersonAndCountIngredientsOnce() {
        var v = planner.create(owner.getEmail(), options());
        assertEquals(3, v.result().entries().size());
        assertEquals("balanced-composite-v1", v.result().algorithmVersion());
        for (var e : v.result().entries()) {
            assertEquals(0, e.portions().compareTo(BigDecimal.ONE));
            assertFalse(e.recipe().components().isEmpty());
            for (var p : v.result().participants()) {
                var total =
                        e.recipe().components().stream()
                                .flatMap(c -> c.amounts().stream())
                                .filter(a -> a.participantId().equals(p.id()))
                                .map(Amount::cookedG)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertTrue(total.compareTo(n("650")) <= 0);
                for (var c : e.recipe().components()) {
                    var a =
                            c.amounts().stream()
                                    .filter(x -> x.participantId().equals(p.id()))
                                    .findFirst()
                                    .orElseThrow();
                    assertTrue(
                            a.cookedG().compareTo(c.profile().minG()) >= 0
                                    && a.cookedG().compareTo(c.profile().maxG()) <= 0);
                }
            }
            assertTrue(e.recipe().nutritionTotal().fiberComplete());
            assertEquals(
                    0,
                    dev.jeefox.smartmeal.nutrition.dto.NutritionValues.sum(
                                                    e.personPortions().stream()
                                                            .map(
                                                                    HouseholdModels.PersonPortion
                                                                            ::nutrition)
                                                            .toList())
                                            .calories()
                                            .subtract(e.recipe().nutritionTotal().calories())
                                            .abs()
                                            .compareTo(n(".05"))
                                    <= 0
                            ? 0
                            : 1);
        }
        var ingredients =
                v.result().entries().stream()
                        .flatMap(e -> e.recipe().ingredients().stream())
                        .map(i -> i.quantityG())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        var basket =
                v.result().shopping().items().stream()
                        .map(BasketItem::requiredG)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, ingredients.compareTo(basket));
        assertTrue(
                v.result().days().getFirst().people().stream()
                        .allMatch(p -> p.proteinTargetG() != null));
    }

    @Test
    void saveReloadAndPreparationKeepCompositionAndFiber() {
        var v = planner.create(owner.getEmail(), options());
        var saved =
                saves.commit(
                        owner.getEmail(),
                        v.id(),
                        UUID.randomUUID(),
                        new Commit(v.revision(), true));
        em.flush();
        em.clear();
        var list =
                entries.findByMealPlan_IdOrderByMealTypeAscPositionAsc(
                        saved.savedPlanIds().getFirst());
        assertEquals(3, list.size());
        assertFalse(list.getFirst().getRecipeSnapshot().components().isEmpty());
        assertTrue(MealPlanNutritionCalculator.calculate(list).fiberComplete());
        for (var p : products)
            pantry.addLot(
                    owner.getEmail(),
                    UUID.randomUUID(),
                    new PantryModels.AddLot(
                            pantry.view(owner.getEmail()).revision(),
                            p.getId(),
                            n("10000"),
                            pantry.today(),
                            null));
        menus.confirmMenu(owner.getEmail(), saved.savedPlanIds().getFirst());
        var e = list.getFirst();
        var before = pantry.view(owner.getEmail());
        var request = new PantryModels.Command(before.revision());
        var key = UUID.randomUUID();
        var action = pantry.usage(owner.getEmail(), key, e.getId(), request);
        assertEquals(action, pantry.usage(owner.getEmail(), key, e.getId(), request));
        assertEquals(
                "DEDUCTED",
                pantry.states(owner.getEmail(), saved.savedPlanIds().getFirst()).entries().stream()
                        .filter(x -> x.entryId().equals(e.getId()))
                        .findFirst()
                        .orElseThrow()
                        .state()
                        .name());
    }

    @Test
    void changedNutrientTargetsInvalidatePreview() {
        var v = planner.create(owner.getEmail(), options());
        var t =
                data.targets(owner.getEmail()).stream()
                        .filter(x -> x.participantId().equals(owner.getId()))
                        .findFirst()
                        .orElseThrow();
        data.saveTargets(
                owner.getEmail(),
                owner.getId(),
                new Targets(t.revision(), true, n("80"), n("90"), n("25"), n("650"), "USER_SET"));
        assertEquals(
                "PLANNING_CONTEXT_CHANGED",
                assertThrows(
                                PlanningException.class,
                                () ->
                                        saves.commit(
                                                owner.getEmail(),
                                                v.id(),
                                                UUID.randomUUID(),
                                                new Commit(v.revision(), true)))
                        .code());
    }

    @Test
    void unknownFiberIsNotZeroOrSuccessfulTarget() {
        data.saveFiber(products.get(2).getId(), new Fiber(null, null, "UNKNOWN"));
        var v = planner.create(owner.getEmail(), options());
        assertTrue(
                v.result().issues().stream()
                        .anyMatch(i -> i.code().equals("FIBER_DATA_INCOMPLETE")));
        assertFalse(v.result().days().getFirst().nutrition().fiberComplete());
        assertTrue(v.result().days().getFirst().nutrition().knownFiber().signum() > 0);
    }

    @Test
    void componentReplacementAndLockAreVersioned() {
        var v = planner.create(owner.getEmail(), options());
        var meal =
                v.result().entries().stream()
                        .filter(e -> e.mealType() == MealType.LUNCH)
                        .findFirst()
                        .orElseThrow();
        var side =
                meal.recipe().components().stream()
                        .filter(c -> c.role() == Role.SIDE)
                        .findFirst()
                        .orElseThrow();
        var main = meal.recipe().components().getFirst();
        var locked =
                planner.lockComponent(
                        owner.getEmail(),
                        v.id(),
                        meal.id(),
                        main.id(),
                        new Lock(v.revision(), true));
        var alternatives = planner.alternatives(owner.getEmail(), v.id(), meal.id(), side.id());
        assertFalse(alternatives.isEmpty());
        var a = alternatives.getFirst();
        var changed =
                planner.replace(
                        owner.getEmail(),
                        v.id(),
                        meal.id(),
                        new Replacement(
                                locked.revision(),
                                a.recipeId(),
                                a.portions(),
                                a.candidateKey(),
                                side.id()));
        var after =
                changed.result().entries().stream()
                        .filter(e -> e.id().equals(meal.id()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                main.recipe().recipeId(),
                after.recipe().components().getFirst().recipe().recipeId());
        assertTrue(after.recipe().components().getFirst().locked());
        assertThrows(
                PlanningException.class,
                () ->
                        planner.lock(
                                owner.getEmail(), v.id(), meal.id(), new Lock(v.revision(), true)));
    }

    @Test
    void referenceTargetsRequireExplicitConfirmationAndWeight() {
        assertThrows(
                PlanningException.class,
                () -> data.suggest(new Targets(0, false, n("80"), null, null, n("650"), "UNSET")));
        var before = data.targets(owner.getEmail());
        var proposal = data.suggest(new Targets(1, true, n("80"), null, null, n("650"), "UNSET"));
        assertEquals(n("66.40"), proposal.proteinTargetG());
        assertEquals(before, data.targets(owner.getEmail()));
        assertThrows(
                PlanningException.class,
                () -> data.saveTargets(owner.getEmail(), UUID.randomUUID(), proposal));
    }

    @Test
    void feedbackIsOwnedVersionedAndDoesNotChangeTargets() throws Exception {
        var v = planner.create(owner.getEmail(), options());
        var saved =
                saves.commit(
                        owner.getEmail(),
                        v.id(),
                        UUID.randomUUID(),
                        new Commit(v.revision(), true));
        var entry =
                entries.findByMealPlan_IdOrderByMealTypeAscPositionAsc(
                                saved.savedPlanIds().getFirst())
                        .getFirst();
        String path =
                "/api/me/meal-plan-entries/" + entry.getId() + "/portion-feedback/" + owner.getId();
        var initial = data.targets(owner.getEmail());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        owner.getEmail())
                                                .roles("CATALOG_EDITOR"))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType("application/json")
                                .content("{\"expectedRevision\":0,\"rating\":\"ENOUGH\"}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isConflict());
        for (var p : products)
            pantry.addLot(
                    owner.getEmail(),
                    UUID.randomUUID(),
                    new PantryModels.AddLot(
                            pantry.view(owner.getEmail()).revision(),
                            p.getId(),
                            n("10000"),
                            pantry.today(),
                            null));
        menus.confirmMenu(owner.getEmail(), saved.savedPlanIds().getFirst());
        pantry.usage(
                owner.getEmail(),
                UUID.randomUUID(),
                entry.getId(),
                new PantryModels.Command(pantry.view(owner.getEmail()).revision()));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        owner.getEmail())
                                                .roles("CATALOG_EDITOR"))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType("application/json")
                                .content("{\"expectedRevision\":0,\"rating\":\"ENOUGH\"}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.revision")
                                .value(1));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        owner.getEmail())
                                                .roles("CATALOG_EDITOR"))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType("application/json")
                                .content("{\"expectedRevision\":0,\"rating\":\"TOO_MUCH\"}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isConflict());
        assertEquals(initial, data.targets(owner.getEmail()));
        var other = new User("bal-other-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(other);
        em.flush();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/me/meal-plan-entries/"
                                                + entry.getId()
                                                + "/portion-feedback")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                other.getEmail())))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
    }

    @Test
    void recipeAndNutrientDataValidateInputAndCsrf() throws Exception {
        String path = "/api/products/" + products.getFirst().getId() + "/fiber";
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                owner.getEmail()))
                                .contentType("application/json")
                                .content(
                                        "{\"fiberPer100g\":5,\"source\":\"test\",\"quality\":\"LABEL\"}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isForbidden());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        owner.getEmail())
                                                .roles("CATALOG_EDITOR"))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"fiberPer100g\":-5,\"source\":\"test\",\"quality\":\"LABEL\"}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        var proposal = data.suggest(new Targets(1, true, n("80"), null, null, n("650"), "UNSET"));
        var saved = data.saveTargets(owner.getEmail(), owner.getId(), proposal);
        assertEquals(n("66.40"), saved.settings().proteinTargetG());
        assertThrows(
                PlanningException.class,
                () -> data.saveTargets(owner.getEmail(), owner.getId(), proposal));
    }

    @Test
    void weekIsFilledWithoutDroppingLaterDaysAtSearchLimit() {
        var o = options();
        var week =
                new Options(
                        o.from(),
                        o.from().plusDays(6),
                        o.budgetRub(),
                        o.allowedChainIds(),
                        o.city(),
                        o.purchaseMode(),
                        o.maxPreparationTimeByMeal(),
                        14,
                        0,
                        o.allowEstimatedPrices(),
                        o.minSplitSavingRub(),
                        o.seed(),
                        o.usePantry(),
                        o.householdMemberIds(),
                        true);
        var view =
                assertTimeout(Duration.ofSeconds(25), () -> planner.create(owner.getEmail(), week));
        assertEquals(21, view.result().entries().size());
        assertTrue(view.result().days().stream().allMatch(d -> d.entries().size() == 3));
    }

    @Test
    void lowBudgetDoesNotReduceCalorieTargetsOrBreakPortionBounds() {
        var o = options();
        var cheap =
                new Options(
                        o.from(),
                        o.to(),
                        BigDecimal.ONE,
                        o.allowedChainIds(),
                        o.city(),
                        o.purchaseMode(),
                        o.maxPreparationTimeByMeal(),
                        o.maxRecipeOccurrences(),
                        o.minRepeatGapDays(),
                        o.allowEstimatedPrices(),
                        o.minSplitSavingRub(),
                        o.seed(),
                        o.usePantry(),
                        o.householdMemberIds(),
                        true);
        var v = planner.create(owner.getEmail(), cheap);
        assertEquals(4200, v.result().dailyCalorieTarget());
        assertEquals(3, v.result().entries().size());
        assertTrue(v.result().issues().stream().anyMatch(i -> i.code().equals("BUDGET_EXCEEDED")));
        assertTrue(v.result().days().getFirst().withinTargetTolerance());
    }

    Options compact(int cap, int days) {
        var o = options();
        return new Options(
                o.from(),
                o.from().plusDays(days - 1),
                o.budgetRub(),
                o.allowedChainIds(),
                o.city(),
                o.purchaseMode(),
                o.maxPreparationTimeByMeal(),
                14,
                0,
                o.allowEstimatedPrices(),
                o.minSplitSavingRub(),
                o.seed(),
                o.usePantry(),
                o.householdMemberIds(),
                true,
                cap);
    }

    @Test
    void weekReusesProductsAndBuysWholeSharedPackages() {
        var v = planner.create(owner.getEmail(), compact(4, 7));
        assertEquals(21, v.result().entries().size());
        assertTrue(GroceryPolicy.count(v.result().shopping()) <= 4);
        assertTrue(
                v.result().shopping().items().stream()
                        .anyMatch(i -> i.requiredG().compareTo(n("1000")) > 0));
        for (var i : v.result().shopping().items()) {
            assertEquals(0, i.purchasedG().remainder(n("1000")).signum());
            assertTrue(i.purchasedG().compareTo(i.requiredG()) >= 0);
        }
        var id = v.result().entries().getFirst().id();
        for (var a : planner.alternatives(owner.getEmail(), v.id(), id))
            assertNotNull(a.candidateKey());
        assertEquals(4, v.result().options().withSeed(99).maxShoppingProducts());
    }

    @Test
    void impossibleCompactRequestDoesNotSilentlyExceedCapOrShrinkTargets() {
        var v = planner.create(owner.getEmail(), compact(1, 2));
        assertTrue(GroceryPolicy.count(v.result().shopping()) <= 1);
        assertEquals(4200, v.result().dailyCalorieTarget());
        assertEquals("NO_FEASIBLE_FOUND", v.result().status());
        assertTrue(
                v.result().issues().stream()
                        .anyMatch(i -> i.code().equals("COMPACT_BASKET_SEARCH")));
        assertThrows(
                PlanningException.class,
                () ->
                        saves.commit(
                                owner.getEmail(),
                                v.id(),
                                UUID.randomUUID(),
                                new Commit(v.revision(), true)));
    }

    @Test
    void evaluatorBlocksExcessProductsEvenWhenWarningWouldBeAccepted() {
        var full = planner.create(owner.getEmail(), options()).result();
        var o = compact(1, 1);
        var checked =
                evaluator.evaluate(contexts.load(owner.getEmail(), o), o, full.entries(), "FOUND");
        assertEquals("NO_FEASIBLE_FOUND", checked.status());
        assertTrue(
                checked.issues().stream().anyMatch(i -> i.code().equals("SHOPPING_PRODUCT_LIMIT")));
    }

    @Test
    void historyIncludesSideRecipesAndChangesContextFingerprint() {
        var o = options();
        var before = contexts.load(owner.getEmail(), o);
        var selection =
                planner.create(owner.getEmail(), o).result().entries().stream()
                        .filter(e -> e.mealType() == MealType.LUNCH)
                        .findFirst()
                        .orElseThrow();
        var previous = new MealPlan(owner, o.from().minusDays(1));
        em.persist(previous);
        em.persist(
                new MealPlanEntry(
                        previous,
                        em.getReference(Recipe.class, selection.recipe().recipeId()),
                        MealType.LUNCH,
                        selection.portions(),
                        1,
                        selection.recipe()));
        previous.confirm(4200);
        em.flush();
        var after = contexts.load(owner.getEmail(), o);
        assertNotEquals(before.fingerprint(), after.fingerprint());
        for (var component : selection.recipe().components())
            assertEquals(
                    o.from().minusDays(1),
                    after.candidate(component.recipe().recipeId()).lastPlannedDate());
        assertTrue(BalancedMealPlanner.historyPenalty(after, selection) > 0);
        assertEquals(
                3,
                planner.create(owner.getEmail(), o).result().entries().size(),
                "History must not ban the only safe main recipe");
    }

    @Test
    void missingYieldIsDiagnosedRatherThanCallingItMissingRecipes() {
        jdbc.update(
                "delete from recipe_portion_profiles where recipe_id in(select recipe_id from"
                        + " recipe_ingredients where product_id=:p)",
                Map.of("p", products.get(0).getId()));
        var v = planner.create(owner.getEmail(), options());
        assertTrue(
                v.result().issues().stream()
                        .anyMatch(i -> i.code().equals("MISSING_YIELD_BREAKFAST")));
    }
}
