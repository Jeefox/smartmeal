package dev.jeefox.smartmeal.price.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record CreatePriceObservationRequest(
        @NotNull
        @Positive
        @Digits(integer = 10, fraction = 2)
        BigDecimal price,
        @NotNull
        @PastOrPresent
        Instant observedAt,
        @NotNull
        Boolean promotional
) {
}
