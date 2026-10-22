package dev.jeefox.smartmeal.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("smartmeal.storage")
public record ObjectStorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket
) {
}
