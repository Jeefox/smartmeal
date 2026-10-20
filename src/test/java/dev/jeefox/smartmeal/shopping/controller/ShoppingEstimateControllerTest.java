package dev.jeefox.smartmeal.shopping.controller;

import dev.jeefox.smartmeal.exception.StoreInactiveException;
import dev.jeefox.smartmeal.exception.StoreNotFoundException;
import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateItemResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateResponse;
import dev.jeefox.smartmeal.shopping.service.ShoppingEstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class ShoppingEstimateControllerTest {
    @Autowired private WebApplicationContext context;
    @MockitoBean private ShoppingEstimateService service;
    private MockMvc mvc;
    private final LocalDate date = LocalDate.of(2033, 1, 1);
    private final String email = "estimate-api@example.invalid";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void returnsPartialEstimateForAuthenticatedUserAndPreservesUnknownCost() throws Exception {
        var item = new ShoppingEstimateItemResponse(1L, "Рис", new BigDecimal("100.00"),
                null, null, null, null, null, null, null, null, null);
        when(service.estimate(email, date, date, 1L)).thenReturn(new ShoppingEstimateResponse(
                date, date, 1L, true, false, new BigDecimal("0.00"), List.of(item)));
        mvc.perform(request().with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(1))
                .andExpect(jsonPath("$.containsDrafts").value(true))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.knownPurchaseCost").value(0))
                .andExpect(jsonPath("$.items[0].productName").value("Рис"))
                .andExpect(jsonPath("$.items[0].purchaseCost").doesNotExist());
        verify(service).estimate(email, date, date, 1L);
    }

    @Test
    void rejectsAnonymousRequest() throws Exception {
        mvc.perform(request()).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void rejectsInvalidStoreId(String id) throws Exception {
        mvc.perform(get("/api/me/shopping-estimate").param("from", date.toString())
                        .param("to", date.toString()).param("storeId", id).with(user(email)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void requiresStoreId() throws Exception {
        mvc.perform(get("/api/me/shopping-estimate").param("from", date.toString())
                        .param("to", date.toString()).with(user(email)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidDate() throws Exception {
        mvc.perform(get("/api/me/shopping-estimate").param("from", "invalid")
                        .param("to", date.toString()).param("storeId", "1").with(user(email)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void returnsNotFoundForMissingStore() throws Exception {
        when(service.estimate(email, date, date, 1L)).thenThrow(new StoreNotFoundException(1L));
        mvc.perform(request().with(user(email))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void returnsConflictForInactiveStore() throws Exception {
        when(service.estimate(email, date, date, 1L)).thenThrow(new StoreInactiveException(1L));
        mvc.perform(request().with(user(email))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/me/shopping-estimate").param("from", date.toString())
                .param("to", date.toString()).param("storeId", "1");
    }
}
