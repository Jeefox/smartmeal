package dev.jeefox.smartmeal.testing;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.*;

import java.util.*;

/**
 * Runs before DataSource/Flyway initialization, even when an integration test is started in an IDE.
 */
public final class TestDatabaseGuard implements ContextCustomizerFactory {
    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> attributes) {
        return new Guard();
    }

    private record Guard() implements ContextCustomizer {
        @Override
        public void customizeContext(
                ConfigurableApplicationContext context, MergedContextConfiguration config) {
            context.addBeanFactoryPostProcessor(
                    factory -> {
                        String url =
                                context.getEnvironment().getProperty("spring.datasource.url", "");
                        String database =
                                url.substring(url.lastIndexOf('/') + 1)
                                        .split(java.util.regex.Pattern.quote("?"), 2)[0];
                        if (!database.matches("(?i)(?:test(?:_.*)?|.*_test(?:_.*)?)"))
                            throw new IllegalStateException(
                                    "Интеграционные тесты запрещены для рабочей базы. Укажите"
                                        + " отдельную БД с суффиксом _test через"
                                        + " -Dspring.datasource.url. Получено имя БД: "
                                            + database);
                    });
        }
    }
}
