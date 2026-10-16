package dev.jeefox.smartmeal.recipe.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
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

    @NotNull
    @Column(nullable = false, columnDefinition = "text")
    private String instructions;

    @NotNull
    @PositiveOrZero
    @Column(name = "preparation_time_min",
            nullable = false)
    private Integer preparationTimeMin;

    @NotNull
    @Positive
    @Column(name = "servings",
            nullable = false)
    private Integer servings;

    @NotNull
    @Column(name = "created_at",
            nullable = false,
            updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at",
            nullable = false)
    private Instant updatedAt;

    public Recipe(String name, String description, String instructions,
                  Integer preparationTimeMin, Integer servings) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.preparationTimeMin = preparationTimeMin;
        this.servings = servings;
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
