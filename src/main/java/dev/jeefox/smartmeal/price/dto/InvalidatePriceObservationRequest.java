package dev.jeefox.smartmeal.price.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InvalidatePriceObservationRequest(
        @NotBlank @Size(max = 300) String reason
) {
}
