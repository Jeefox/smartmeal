package dev.jeefox.smartmeal.household;

import static dev.jeefox.smartmeal.household.HouseholdModels.*;
import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.generation.period.*;
import dev.jeefox.smartmeal.mealplan.service.MealPlanService;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.recipe.entity.*;
import dev.jeefox.smartmeal.user.entity.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;

@SpringBootTest
@Transactional
class HouseholdIntegrationTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired HouseholdService households;
    @Autowired PlanningContextLoader contexts;
    @Autowired PeriodPlanningService planning;
    @Autowired GenerationCommitService commits;
    @Autowired MealPlanService plans;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    User owner, other;
    Product product;
    long chain;
    final LocalDate date = LocalDate.of(2044, 6, 1);

    @BeforeEach
    void setup() {
        owner = new User("household-" + UUID.randomUUID() + "@example.invalid", "test");
        other = new User("other-" + UUID.randomUUID() + "@example.invalid", "test");
        em.persist(owner);
        em.persist(other);
        em.persist(new UserProfile(2000, owner));
        em.persist(new UserProfile(2000, other));
        var cat = new ProductCategory("HOUSEHOLD_" + UUID.randomUUID(), "Тест семьи");
        em.persist(cat);
        product =
                new Product(
                        cat, "Крупа", null, 100, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        em.persist(product);
        em.flush();
        jdbc.update("update products set allergen_data_complete=true where id=?", product.getId());
        for (var meal : List.of(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)) {
            var r = new Recipe("Семейное блюдо " + meal, null, "Сварить", 10, 1);
            r.changeMealTypes(Set.of(meal));
            r.changePlanningDetails(
                    new BigDecimal("0.5"), new BigDecimal("3"), new BigDecimal("0.25"), null);
            em.persist(r);
            em.persist(
                    new RecipeIngredient(
                            r,
                            product,
                            new BigDecimal(
                                    meal == MealType.BREAKFAST
                                            ? 500
                                            : meal == MealType.LUNCH ? 800 : 700),
                            1));
        }
        em.flush();
        jdbc.update(
                "insert into user_excluded_products(user_id,product_id) select ?,id from products"
                    + " where id<>?",
                owner.getId(),
                product.getId());
        chain =
                jdbc.queryForObject(
                        "insert into retail_chains(name,code) values(?,?) returning id",
                        Long.class,
                        "Тест семьи",
                        "HH_" + UUID.randomUUID());
    }

    Member create(int target) {
        return households.create(
                owner.getEmail(), new MemberRequest("Анна", target, List.of(), List.of()));
    }

    Options options(List<UUID> ids) {
        return new Options(
                date,
                date,
                null,
                List.of(chain),
                "Тула",
                null,
                null,
                2,
                0,
                true,
                null,
                0L,
                false,
                ids);
    }

    @Test
    void managesOnlyOwnPeopleAndArchivesWithoutDestroyingSnapshots() {
        var member = create(1500);
        assertEquals(1, households.list(owner.getEmail()).size());
        assertThrows(
                HouseholdException.class,
                () ->
                        households.update(
                                other.getEmail(),
                                member.id(),
                                new MemberRequest("Чужой", 2000, List.of(), List.of())));
        var updated =
                households.update(
                        owner.getEmail(),
                        member.id(),
                        new MemberRequest("Анна", 1800, List.of(), List.of(product.getId())));
        assertEquals(product.getId(), updated.excludedProducts().getFirst().id());
        households.remove(owner.getEmail(), member.id());
        assertTrue(households.list(owner.getEmail()).isEmpty());
        assertThrows(
                HouseholdException.class,
                () -> contexts.load(owner.getEmail(), options(List.of(member.id()))));
    }

    @Test
    void unionOfRestrictionsAppliesToEveryoneAndUnknownAllergensAreExcluded() {
        var member = create(1500);
        var allergen =
                jdbc.queryForObject(
                        "insert into allergens(code,name) values(?,?) returning id",
                        Long.class,
                        "HH_" + UUID.randomUUID(),
                        "Тестовый аллерген");
        jdbc.update("insert into product_allergens values(?,?)", product.getId(), allergen);
        households.update(
                owner.getEmail(),
                member.id(),
                new MemberRequest("Анна", 1500, List.of(allergen), List.of()));
        assertTrue(
                contexts.load(owner.getEmail(), options(List.of(member.id())))
                        .allCandidates()
                        .isEmpty());
        households.update(
                owner.getEmail(),
                member.id(),
                new MemberRequest("Анна", 1500, List.of(), List.of(product.getId())));
        assertTrue(
                contexts.load(owner.getEmail(), options(List.of(member.id())))
                        .allCandidates()
                        .isEmpty());
    }

    @Test
    void differentTargetsScalePurchasesAndKeepIndividualPortionsAfterSavingAndConfirmation() {
        var member = create(1000);
        var single = planning.create(owner.getEmail(), options(List.of()));
        var generated = planning.create(owner.getEmail(), options(List.of(member.id())));
        assertEquals(3000, generated.result().dailyCalorieTarget());
        assertEquals(2, generated.result().participants().size());
        assertEquals(3, generated.result().entries().size());
        assertTrue(
                generated.result().entries().stream()
                        .allMatch(e -> e.personPortions().size() == 2));
        assertTrue(
                generated
                                .result()
                                .shopping()
                                .items()
                                .getFirst()
                                .requiredG()
                                .compareTo(
                                        single.result().shopping().items().getFirst().requiredG())
                        > 0);
        var saved =
                commits.commit(
                        owner.getEmail(),
                        generated.id(),
                        UUID.randomUUID(),
                        new Commit(generated.revision(), true));
        em.flush();
        em.clear();
        var plan = plans.findByDate(owner.getEmail(), date);
        assertEquals(3000, plan.dailyCalorieTarget());
        assertEquals(2, plan.people().size());
        for (var entry : plan.entries())
            assertEquals(
                    0,
                    entry.personPortions().stream()
                            .map(PersonPortion::portions)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .compareTo(entry.portions()));
        households.update(
                owner.getEmail(),
                member.id(),
                new MemberRequest("Другое имя", 2200, List.of(), List.of()));
        assertEquals("Анна", plans.findByDate(owner.getEmail(), date).participants().get(1).name());
        assertEquals(
                3000, plans.confirmMenu(owner.getEmail(), plan.id()).dailyCalorieTargetSnapshot());
        assertEquals(1, saved.savedPlanIds().size());
    }

    @Test
    void profileChangesInvalidateAnOldPreviewRatherThanSaveStaleRestrictions() {
        var member = create(1500);
        var v = planning.create(owner.getEmail(), options(List.of(member.id())));
        households.update(
                owner.getEmail(),
                member.id(),
                new MemberRequest("Анна", 1600, List.of(), List.of()));
        assertThrows(
                PlanningException.class,
                () ->
                        commits.commit(
                                owner.getEmail(),
                                v.id(),
                                UUID.randomUUID(),
                                new Commit(v.revision(), true)));
    }

    @Test
    void foreignAndDuplicateMembersCannotBeSelected() {
        var member = create(1500);
        assertThrows(
                HouseholdException.class,
                () -> contexts.load(other.getEmail(), options(List.of(member.id()))));
        assertFalse(options(List.of(member.id(), member.id())).isSettingsValid());
    }

    @Test
    void householdApiValidatesTargetsAndRequiresCsrf() throws Exception {
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                        .apply(
                                org.springframework.security.test.web.servlet.setup
                                        .SecurityMockMvcConfigurers.springSecurity())
                        .build();
        var post =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/me/household-members")
                        .with(
                                org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.user(
                                        owner.getEmail()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        mvc.perform(
                        post.content(
                                "{\"name\":\"Анна\",\"dailyCalorieTarget\":1500,\"allergenIds\":[],\"excludedProductIds\":[]}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isForbidden());
        mvc.perform(
                        post.with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .content(
                                        "{\"name\":\"Анна\",\"dailyCalorieTarget\":0,\"allergenIds\":[],\"excludedProductIds\":[]}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
    }
}
