package dev.jeefox.smartmeal.auth.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class RequestProtectionFilterTest {
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
    }

    MockHttpServletRequest request(String method, String path) {
        var r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        r.setRemoteAddr("127.0.0.20");
        return r;
    }

    @Test
    void loginLimitUsesSocketAddressNotSpoofableForwardedHeaders() throws Exception {
        var filter = new RequestProtectionFilter(2, 20);
        for (int i = 0; i < 3; i++) {
            var r = request("POST", "/api/auth/login");
            r.addHeader("X-Forwarded-For", "10.0.0." + i);
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter.doFilter(r, response, (q, s) -> called.set(true));
            assertEquals(i < 2, called.get());
            if (i == 2) {
                assertEquals(429, response.getStatus());
                assertEquals("60", response.getHeader("Retry-After"));
            }
        }
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request("GET", "/api/me/profile"), response, (q, s) -> called.set(true));
        assertTrue(called.get());
    }

    @Test
    void longLoginPasswordGetsGeneric401WithoutCallingEncoder() throws Exception {
        var filter = new RequestProtectionFilter(20, 20);
        var request = request("POST", "/api/auth/login");
        request.setParameter("password", "я".repeat(37));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (q, s) -> fail("Must reject before authentication"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void generationForSameUserCannotRunConcurrentlyAndPermitIsReleasedAfterFailure()
            throws Exception {
        var filter = new RequestProtectionFilter(20, 20);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        var auth = new UsernamePasswordAuthenticationToken("test-account", null, List.of());
        var first =
                worker.submit(
                        () -> {
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            try {
                                filter.doFilter(
                                        request("POST", "/api/me/meal-plan-generations"),
                                        new MockHttpServletResponse(),
                                        (q, s) -> {
                                            entered.countDown();
                                            try {
                                                if (!release.await(5, TimeUnit.SECONDS))
                                                    throw new AssertionError("Timeout");
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            throw new jakarta.servlet.ServletException(
                                                    "Synthetic error");
                                        });
                            } catch (Exception expected) {
                            } finally {
                                SecurityContextHolder.clearContext();
                            }
                        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SecurityContextHolder.getContext().setAuthentication(auth);
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    request("POST", "/api/me/meal-plan-generations"),
                    response,
                    (q, s) -> fail("Concurrent call admitted"));
            assertEquals(429, response.getStatus());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            var called = new AtomicBoolean();
            filter.doFilter(
                    request("POST", "/api/me/meal-plan-generations"),
                    new MockHttpServletResponse(),
                    (q, s) -> called.set(true));
            assertTrue(called.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void editorAllowlistOnlyAcceptsExistingAccountIdentifiers() {
        var id = java.util.UUID.randomUUID();
        var editors = new CatalogueEditors(id.toString());
        assertTrue(editors.contains(id));
        assertFalse(editors.contains(java.util.UUID.randomUUID()));
        assertFalse(new CatalogueEditors("").contains(id));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CatalogueEditors("editor@example.invalid"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogueEditors("*"));
    }

    @Test
    void productionRejectsDevelopmentSecretsWithoutPrintingTheirValues() {
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecrets("smartmeal-dev-secret-change-me", "x".repeat(24)));
        assertThrows(
                IllegalStateException.class, () -> new ProductionSecrets("s".repeat(40), "short"));
        assertDoesNotThrow(() -> new ProductionSecrets("s".repeat(40), "d".repeat(24)));
    }

    @Test
    void trustedAccountIdGrantsRoleButAnEmailAloneDoesNot() {
        var id = java.util.UUID.randomUUID();
        var repository =
                org.mockito.Mockito.mock(dev.jeefox.smartmeal.user.repository.UserRepository.class);
        var account = org.mockito.Mockito.mock(dev.jeefox.smartmeal.user.entity.User.class);
        org.mockito.Mockito.when(account.getId()).thenReturn(id);
        org.mockito.Mockito.when(account.getEmail()).thenReturn("owner@example.invalid");
        org.mockito.Mockito.when(account.getPasswordHash()).thenReturn("synthetic-hash");
        org.mockito.Mockito.when(repository.findByEmail("owner@example.invalid"))
                .thenReturn(java.util.Optional.of(account));
        var trusted =
                new dev.jeefox.smartmeal.auth.service.SmartmealUserDetailsService(
                        repository, new CatalogueEditors(id.toString()));
        assertTrue(
                trusted.loadUserByUsername("owner@example.invalid").getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_CATALOG_EDITOR")));
        var ordinary =
                new dev.jeefox.smartmeal.auth.service.SmartmealUserDetailsService(
                        repository, new CatalogueEditors(""));
        assertFalse(
                ordinary.loadUserByUsername("owner@example.invalid").getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_CATALOG_EDITOR")));
    }

    @Test
    void changingIpDoesNotBypassLoginAccountLimit() throws Exception {
        var filter = new RequestProtectionFilter(2, 20);
        for (int i = 0; i < 3; i++) {
            var r = request("POST", "/api/auth/login");
            r.setRemoteAddr("10.0.0." + i);
            r.setParameter("email", "same-account@example.invalid");
            r.setParameter("password", "synthetic");
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter.doFilter(r, response, (q, s) -> called.set(true));
            assertEquals(i < 2, called.get());
            if (i == 2) assertEquals(429, response.getStatus());
        }
    }
}
