package dev.jeefox.smartmeal.recipe.entity;

import dev.jeefox.smartmeal.mealplan.entity.MealType;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recipes")
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    @NotNull
    private String name;

    private String description;

    @Column(name = "catalogue_visible", nullable = false)
    private boolean catalogueVisible = true;

    @NotNull
    @Column(nullable = false, columnDefinition = "text")
    private String instructions;

    @NotNull
    @PositiveOrZero
    @Column(name = "preparation_time_min", nullable = false)
    private Integer preparationTimeMin;

    @NotNull
    @Positive
    @Column(name = "servings", nullable = false)
    private Integer servings;

    @Column(name = "portion_step", nullable = false, precision = 4, scale = 2)
    private java.math.BigDecimal portionStep = java.math.BigDecimal.ONE;

    @Column(name = "min_portions", nullable = false, precision = 4, scale = 2)
    private java.math.BigDecimal minPortions = java.math.BigDecimal.ONE;

    @Column(name = "max_portions", nullable = false, precision = 4, scale = 2)
    private java.math.BigDecimal maxPortions = new java.math.BigDecimal("2");

    @Column(name = "dish_family", length = 100)
    private String dishFamily;

    public void changePlanningDetails(
            java.math.BigDecimal min,
            java.math.BigDecimal max,
            java.math.BigDecimal step,
            String family) {
        this.minPortions = min;
        this.maxPortions = max;
        this.portionStep = step;
        this.dishFamily = family;
    }

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "recipe_meal_types", joinColumns = @JoinColumn(name = "recipe_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 16)
    @BatchSize(size = 50)
    private Set<MealType> mealTypes = new HashSet<>();

    public Set<MealType> getMealTypes() {
        return Set.copyOf(mealTypes);
    }

    public void changeMealTypes(Set<MealType> types) {
        Set<MealType> replacement = Set.copyOf(Objects.requireNonNull(types));
        if (!mealTypes.equals(replacement)) {
            mealTypes.clear();
            mealTypes.addAll(replacement);
            updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    public Recipe(
            String name,
            String description,
            String instructions,
            Integer preparationTimeMin,
            Integer servings) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.preparationTimeMin = preparationTimeMin;
        this.servings = servings;
    }

    @PrePersist
    protected void onCreate() {
        var now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
