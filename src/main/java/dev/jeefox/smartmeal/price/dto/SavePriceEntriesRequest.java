package dev.jeefox.smartmeal.price.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SavePriceEntriesRequest(
        @NotNull UUID requestId,
        @NotNull @PastOrPresent Instant observedAt,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Entry> entries
) {
    public record Entry(@NotNull @Positive Long productId,
                        @Positive Long storeProductId,
                        @Size(max = 300) String name,
                        @Positive @Digits(integer = 10, fraction = 0) @DecimalMax("2147483647") BigDecimal packageWeightG,
                        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal price,
                        @NotNull Boolean promotional) {
    }
}
