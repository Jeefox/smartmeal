package dev.jeefox.smartmeal.store.entity;

import dev.jeefox.smartmeal.product.entity.Product;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Getter
@Table(name = "store_products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotBlank
    @Size(max = 300)
    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @NotNull
    @Positive
    @Column(name = "package_weight_g", nullable = false)
    private Integer packageWeightG;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StoreProduct(
            Store store,
            Product product,
            String name,
            Integer packageWeightG){
        this.store = store;
        this.product = product;
        this.name = name;
        this.packageWeightG = packageWeightG;
        this.active = true;
    }

    public void changeActivity(boolean active) {
        this.active = active;
    }

    @PrePersist
    protected void onCreate(){
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate(){
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
