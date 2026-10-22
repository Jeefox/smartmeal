package dev.jeefox.smartmeal.pantry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class PantryModels {
    private PantryModels() {}

    public record Lot(
            UUID id,
            Long productId,
            String productName,
            BigDecimal quantityOnHandG,
            BigDecimal reservedG,
            BigDecimal freeG,
            LocalDate receivedOn,
            LocalDate expiresOn,
            String source,
            boolean expired,
            List<ReservationTarget> reservedFor) {}

    public record ReservationTarget(
            UUID entryId,
            LocalDate date,
            String mealType,
            String recipeName,
            BigDecimal quantityG) {}

    public record StockLot(
            UUID id,
            Long productId,
            BigDecimal quantityG,
            LocalDate receivedOn,
            LocalDate expiresOn,
            Instant createdAt) {}

    public record Snapshot(long revision, LocalDate today, List<StockLot> lots) {
        public Snapshot {
            lots = List.copyOf(lots);
        }

        public static Snapshot empty() {
            return new Snapshot(0, LocalDate.of(1900, 1, 1), List.of());
        }
    }

    public record View(
            long revision, LocalDate today, List<Lot> lots, int unconfirmedPastEntries) {}

    public record PurchaseLine(
            @NotNull @Positive Long productId,
            @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal quantityG,
            @NotNull LocalDate receivedOn,
            LocalDate expiresOn,
            @Positive Long storeProductId,
            @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal actualPriceRub) {}

    public record Purchase(
            @Min(0) long expectedRevision,
            @NotEmpty @Size(max = 100) List<@Valid PurchaseLine> items) {}

    public record AddLot(
            @Min(0) long expectedRevision,
            @NotNull @Positive Long productId,
            @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal quantityG,
            @NotNull LocalDate receivedOn,
            LocalDate expiresOn) {}

    public record Adjustment(
            @Min(0) long expectedRevision,
            @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 4) BigDecimal quantityG,
            @NotBlank @Size(max = 300) String reason) {}

    public record Metadata(@Min(0) long expectedRevision, LocalDate expiresOn) {}

    public record Command(@Min(0) long expectedRevision) {}

    public record StateCommand(@Min(0) long expectedRevision, @NotNull State state) {}

    public enum State {
        OPEN,
        DEDUCTED,
        SKIPPED
    }

    public record Action(UUID operationId, long revision, List<UUID> lotIds) {}

    public record Movement(
            UUID lotId,
            Long productId,
            String productName,
            BigDecimal quantityDeltaG,
            String reason) {}

    public record Operation(UUID id, String kind, Instant createdAt, List<Movement> movements) {}

    public record History(int page, boolean hasNext, List<Operation> items) {}

    public record EntryState(UUID entryId, State state, Map<Long, BigDecimal> reservedG) {}

    public record EntryStates(long revision, LocalDate today, List<EntryState> entries) {}
}
