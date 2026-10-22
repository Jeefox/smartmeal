package dev.jeefox.smartmeal.testing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.*;

class TestDatabaseGuardTest {
    @Test
    void rejectsTheNormalApplicationDatabaseBeforeAnyConnectionIsCreated() {
        try (var c = new GenericApplicationContext()) {
            c.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test",
                                    Map.of(
                                            "spring.datasource.url",
                                            "jdbc:postgresql://localhost:5433/smartmeal")));
            new TestDatabaseGuard()
                    .createContextCustomizer(getClass(), List.of())
                    .customizeContext(c, null);
            assertThrows(IllegalStateException.class, c::refresh);
        }
    }

    @Test
    void acceptsAnExplicitTestDatabase() {
        try (var c = new GenericApplicationContext()) {
            c.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test",
                                    Map.of(
                                            "spring.datasource.url",
                                            "jdbc:postgresql://localhost:55439/smartmeal_test?ssl=false")));
            new TestDatabaseGuard()
                    .createContextCustomizer(getClass(), List.of())
                    .customizeContext(c, null);
            assertDoesNotThrow(c::refresh);
        }
    }
}
