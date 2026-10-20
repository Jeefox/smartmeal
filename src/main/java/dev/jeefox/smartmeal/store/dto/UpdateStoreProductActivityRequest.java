package dev.jeefox.smartmeal.store.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateStoreProductActivityRequest(@NotNull Boolean active) {
}
