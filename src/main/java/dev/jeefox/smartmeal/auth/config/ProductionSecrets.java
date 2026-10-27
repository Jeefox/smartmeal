package dev.jeefox.smartmeal.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public final class ProductionSecrets {
    public ProductionSecrets(
            @Value("${smartmeal.storage.secret-key}") String storageSecret,
            @Value("${spring.datasource.password}") String databasePassword) {
        if (storageSecret == null
                || storageSecret.length() < 32
                || storageSecret.equals("smartmeal-dev-secret-change-me"))
            throw new IllegalStateException(
                    "Production requires a unique S3_SECRET_KEY of at least 32 characters");
        if (databasePassword == null || databasePassword.length() < 16)
            throw new IllegalStateException(
                    "Production requires a POSTGRES_PASSWORD of at least 16 characters");
    }
}
