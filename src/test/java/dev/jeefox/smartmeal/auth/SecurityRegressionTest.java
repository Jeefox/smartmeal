package dev.jeefox.smartmeal.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;

import dev.jeefox.smartmeal.auth.dto.RegisterRequest;
import dev.jeefox.smartmeal.exception.ApiExceptionHandler;
import dev.jeefox.smartmeal.household.HouseholdModels.MemberRequest;
import dev.jeefox.smartmeal.household.HouseholdService;
import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.user.entity.*;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;

import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@SpringBootTest
@Transactional
class SecurityRegressionTest {
    @Autowired WebApplicationContext web;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired HouseholdService household;
    @Autowired PasswordEncoder encoder;
    @Autowired Validator validator;
    MockMvc mvc;
    User a, b;
    Product product;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        a = new User("audit-a-" + UUID.randomUUID() + "@example.invalid", "SYNTHETIC_HASH_MARKER");
        b = new User("audit-b-" + UUID.randomUUID() + "@example.invalid", "SYNTHETIC_HASH_MARKER");
        em.persist(a);
        em.persist(b);
        em.persist(new UserProfile(2000, a));
        em.persist(new UserProfile(1800, b));
        var category = new ProductCategory("AUDIT_" + UUID.randomUUID(), "Audit");
        em.persist(category);
        product =
                new Product(
                        category,
                        "Audit product",
                        null,
                        100,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE);
        em.persist(product);
        em.flush();
    }

    @Test
    void anonymousCannotReadPrivateProfile() throws Exception {
        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfRequiredForAuthenticatedAndPublicWrites() throws Exception {
        mvc.perform(
                        put("/api/products/{id}/planning", product.getId())
                                .with(user(a.getEmail()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"allergenDataComplete\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").param("email", a.getEmail()).param("password", "test"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownProfileUsesSessionAndHasNoSecretFieldsOrCorsPermission() throws Exception {
        var result =
                mvc.perform(
                                get("/api/me/profile")
                                        .param("userId", b.getId().toString())
                                        .with(user(a.getEmail()))
                                        .header("Origin", "https://untrusted.example.invalid"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.userId").value(a.getId().toString()))
                        .andExpect(jsonPath("$.password").doesNotExist())
                        .andExpect(jsonPath("$.passwordHash").doesNotExist())
                        .andExpect(jsonPath("$.email").doesNotExist())
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andExpect(header().string("X-Frame-Options", "DENY"))
                        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                        .andReturn();
        assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
        assertFalse(result.getResponse().getContentAsString().contains("SYNTHETIC_HASH_MARKER"));
    }

    @Test
    void cannotReadOrConfirmOtherUsersPlan() throws Exception {
        var date = LocalDate.of(2050, 3, 7);
        var plan = new MealPlan(a, date);
        em.persist(plan);
        em.flush();
        mvc.perform(
                        get("/api/me/meal-plans")
                                .param("date", date.toString())
                                .with(user(b.getEmail())))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/me/meal-plans/{id}/confirm", plan.getId())
                                .with(user(b.getEmail()))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotReadChangeOrDeleteOtherUsersFamily() throws Exception {
        var member =
                household.create(
                        a.getEmail(),
                        new MemberRequest("Private audit member", 1800, List.of(), List.of()));
        mvc.perform(get("/api/me/household-members").with(user(b.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(
                        put("/api/me/household-members/{id}", member.id())
                                .with(user(b.getEmail()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"Changed\",\"dailyCalorieTarget\":1900,\"allergenIds\":[],\"excludedProductIds\":[]}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        delete("/api/me/household-members/{id}", member.id())
                                .with(user(b.getEmail()))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void normalUserCannotOverwriteSharedAllergenMetadata() throws Exception {
        var allergen =
                jdbc.queryForObject(
                        "insert into allergens(code,name) values(?,?) returning id",
                        Long.class,
                        "AUDIT_" + UUID.randomUUID(),
                        "Audit allergen");
        jdbc.update(
                "insert into product_allergens(product_id,allergen_id) values(?,?)",
                product.getId(),
                allergen);
        mvc.perform(
                        put("/api/products/{id}/planning", product.getId())
                                .with(user(b.getEmail()).roles("USER"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"allergenDataComplete\":true,\"verificationNote\":\"Untrusted"
                                            + " audit assertion\",\"allergenIds\":[]}"))
                .andExpect(status().isForbidden());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from product_allergens where product_id=?",
                        Integer.class,
                        product.getId()));
        mvc.perform(get("/api/products/{id}/planning", product.getId()).with(user(a.getEmail())))
                .andExpect(jsonPath("$.allergenIds[0]").value(allergen));
    }

    @Test
    void invalidPasswordIsNeverIncludedInDebugLogOrResponse() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        var before = logger.getLevel();
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        String marker = "AUDIT_PASSWORD_SENTINEL_" + "Z".repeat(130);
        try {
            var res =
                    mvc.perform(
                                    post("/api/auth/register")
                                            .with(csrf())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"email\":\"synthetic@example.invalid\",\"password\":\""
                                                            + marker
                                                            + "\"}"))
                            .andExpect(status().isBadRequest())
                            .andReturn();
            assertFalse(res.getResponse().getContentAsString().contains(marker));
            assertFalse(
                    appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(marker)),
                    "Rejected password must never appear in logs");
        } finally {
            logger.setLevel(before);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void validationRejectsPasswordsLongerThanBcryptByteLimit() {
        String password = "Z".repeat(73);
        assertFalse(
                validator
                        .validate(new RegisterRequest("synthetic@example.invalid", password))
                        .isEmpty());
        assertFalse(
                validator
                        .validate(new RegisterRequest("synthetic@example.invalid", "я".repeat(37)))
                        .isEmpty());
        assertTrue(
                validator
                        .validate(new RegisterRequest("synthetic@example.invalid", "я".repeat(36)))
                        .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(password));
    }

    @Test
    void catalogueEditorCanWriteButCannotBecomeAnotherUser() throws Exception {
        mvc.perform(
                        put("/api/products/{id}/planning", product.getId())
                                .with(user(b.getEmail()).roles("CATALOG_EDITOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"allergenDataComplete\":true,\"verificationNote\":\"Verified"
                                            + " by editor\",\"allergenIds\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/profile").with(user(b.getEmail()).roles("CATALOG_EDITOR")))
                .andExpect(jsonPath("$.canEditCatalogue").value(true));
        mvc.perform(get("/api/me/profile").with(user(a.getEmail())))
                .andExpect(jsonPath("$.canEditCatalogue").value(false));
    }

    @Test
    void longPasswordsReturn400InsteadOfAnEncoderFailure() throws Exception {
        mvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"audit@example.invalid\",\"password\":\""
                                                + "я".repeat(37)
                                                + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registrationDoesNotRevealWhetherEmailExists() throws Exception {
        for (String email :
                List.of(a.getEmail(), "audit-new-" + UUID.randomUUID() + "@example.invalid"))
            mvc.perform(
                            post("/api/auth/register")
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"email\":\""
                                                    + email
                                                    + "\",\"password\":\"synthetic-password\"}"))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
    }

    @Test
    void ordinaryUserCannotMutateOtherSharedResources() throws Exception {
        for (String url :
                List.of(
                        "/api/recipes/" + UUID.randomUUID() + "/planning",
                        "/api/products/" + product.getId() + "/fiber",
                        "/api/store-products/1/activity"))
            mvc.perform(
                            put(url).with(user(a.getEmail()))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isForbidden());
    }
}
