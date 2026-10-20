package dev.jeefox.smartmeal.price.entity;

import dev.jeefox.smartmeal.store.entity.StoreProduct;
import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Getter
@Table(name = "price_observations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_product_id", nullable = false)
    private StoreProduct storeProduct;

    @NotNull
    @Positive
    @Digits(integer = 10, fraction = 2)
    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @NotNull
    @PastOrPresent
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 16)
    private PriceSource priceSource;

    @Column(name = "promotional", nullable = false)
    private boolean promotional;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidation_reason", length = 300)
    private String invalidationReason;

    @Column(name = "invalidated_by", columnDefinition = "text")
    private String invalidatedBy;

    public boolean isInvalidated() {
        return invalidatedAt != null;
    }

    public void invalidate(String reason, String actor) {
        if (isInvalidated()) {
            return;
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 300
                || actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Укажите причину аннулирования и пользователя");
        }
        invalidationReason = reason.trim();
        invalidatedBy = actor;
        invalidatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public PriceObservation(
            StoreProduct storeProduct,
            BigDecimal price,
            Instant observedAt,
            PriceSource priceSource,
            boolean promotional){
        this.storeProduct = storeProduct;
        this.price = price;
        this.observedAt = observedAt;
        this.priceSource = priceSource;
        this.promotional = promotional;
    }

    @PrePersist
    protected void onCreate(){
        var now = Instant.now();
        this.createdAt = now;
    }
}
