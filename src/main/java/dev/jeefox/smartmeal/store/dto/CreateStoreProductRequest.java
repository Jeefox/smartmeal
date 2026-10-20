package dev.jeefox.smartmeal.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateStoreProductRequest(
        @NotNull
        @Positive
        Long storeId,
        @NotNull
        @Positive
        Long productId,
        @NotBlank
        @Size(max = 300)
        String name,
        @NotNull
        @Positive
        Integer packageWeightG
) {
}
