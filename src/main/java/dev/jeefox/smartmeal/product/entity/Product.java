package dev.jeefox.smartmeal.product.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ProductCategory category;

    @Column(nullable = false)
    @NotNull
    private String name;

    @NotBlank
    @Column(name = "shopping_name", nullable = false, columnDefinition = "text")
    private String shoppingName;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @DecimalMax("1")
    @Digits(integer = 1, fraction = 6)
    @Column(name = "edible_yield", nullable = false, precision = 7, scale = 6)
    private BigDecimal edibleYield;


    @Column(length = 300)
    @Size(max = 300)
    private String description;

    @NotNull
    @PositiveOrZero
    @Column(name = "calories_per_100g",
            nullable = false)
    private Integer caloriesPer100g;

    @NotNull
    @PositiveOrZero
    @Column(name = "protein_per_100g",
            nullable = false,
            precision = 7, scale = 2)
    private BigDecimal proteinPer100g;

    @NotNull
    @PositiveOrZero
    @Column(name = "fat_per_100g",
            nullable = false,
            precision = 7, scale = 2)
    private BigDecimal fatPer100g;

    @NotNull
    @PositiveOrZero
    @Column(name = "carbs_per_100g",
            nullable = false,
            precision = 7, scale = 2)
    private BigDecimal carbsPer100g;

    @NotNull
    @Column(name = "created_at",
            nullable = false,
            updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at",
            nullable = false)
    private Instant updatedAt;

    public Product(ProductCategory category, String name,
                   String description, Integer caloriesPer100g,
                   BigDecimal proteinPer100g, BigDecimal fatPer100g,
                   BigDecimal carbsPer100g) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.caloriesPer100g = caloriesPer100g;
        this.proteinPer100g = proteinPer100g;
        this.fatPer100g = fatPer100g;
        this.carbsPer100g = carbsPer100g;
        this.shoppingName = name;
        this.edibleYield = BigDecimal.ONE;
    }

    public Product(ProductCategory category, String name, String description,
                   Integer caloriesPer100g, BigDecimal proteinPer100g,
                   BigDecimal fatPer100g, BigDecimal carbsPer100g,
                   String shoppingName, BigDecimal edibleYield) {
        this(category, name, description, caloriesPer100g, proteinPer100g, fatPer100g, carbsPer100g);
        updateShoppingDetails(shoppingName, edibleYield);
    }

    public void updateShoppingDetails(String shoppingName, BigDecimal edibleYield) {
        if (shoppingName != null) {
            if (shoppingName.isBlank()) {
                throw new IllegalArgumentException("Название для покупок не должно быть пустым");
            }
            this.shoppingName = shoppingName.trim();
        }
        if (edibleYield != null) {
            if (edibleYield.signum() <= 0 || edibleYield.compareTo(BigDecimal.ONE) > 0
                    || Math.max(0, edibleYield.stripTrailingZeros().scale()) > 6) {
                throw new IllegalArgumentException("Доля съедобной части должна быть больше 0 и не больше 1, до 6 знаков после запятой");
            }
            this.edibleYield = edibleYield;
        }
    }

    public void updateProduct(ProductCategory category, String name,
                                 String description, Integer caloriesPer100g,
                                 BigDecimal proteinPer100g, BigDecimal fatPer100g,
                                 BigDecimal carbsPer100g){
        this.category = category;
        this.name = name;
        this.description = description;
        this.caloriesPer100g = caloriesPer100g;
        this.proteinPer100g = proteinPer100g;
        this.fatPer100g = fatPer100g;
        this.carbsPer100g = carbsPer100g;
    }

    @PrePersist
    protected void onCreate(){
        var now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate(){
        this.updatedAt = Instant.now();
    }
}
