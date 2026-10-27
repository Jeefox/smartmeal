package dev.jeefox.smartmeal.shopping.purchase;

import dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.BasketItem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ShoppingPurchaseModels {
    private ShoppingPurchaseModels() {}

    public record Open(@NotNull UUID generationId) {}

    public record Change(
            @Min(0) long expectedRevision, @NotNull Boolean picked, @NotNull Boolean skipped) {}

    public record Line(
            @NotNull UUID itemId,
            @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal quantityG,
            @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal actualTotalRub,
            @Positive Long storeProductId,
            @NotNull LocalDate receivedOn,
            LocalDate expiresOn) {}

    public record Purchase(
            @Min(0) long expectedRevision,
            @Min(0) long expectedPantryRevision,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> items) {}

    public record Item(
            UUID id,
            BasketItem snapshot,
            BigDecimal requiredToBuyG,
            BigDecimal acquiredG,
            BigDecimal remainingG,
            BigDecimal suggestedPurchaseG,
            Integer packageCount,
            boolean picked,
            boolean skipped) {}

    public record View(
            UUID id,
            UUID generationId,
            LocalDate from,
            LocalDate to,
            long revision,
            long pantryRevision,
            LocalDate today,
            String status,
            BigDecimal budgetRub,
            int participants,
            List<Item> items,
            BigDecimal knownEstimateRub,
            int unknownEstimateProducts,
            BigDecimal knownActualTotalRub,
            int purchasesWithoutPrice,
            boolean overlappingOpenSessions,
            List<UUID> appliedPurchaseKeys) {}

    public record Summary(
            UUID id,
            LocalDate from,
            LocalDate to,
            String status,
            long revision,
            Instant createdAt) {}

    public record Page(List<Summary> items, int page, boolean hasNext) {}
}
