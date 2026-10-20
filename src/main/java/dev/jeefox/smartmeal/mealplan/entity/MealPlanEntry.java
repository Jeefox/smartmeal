package dev.jeefox.smartmeal.mealplan.entity;

import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "meal_plan_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealPlanEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_plan_id", nullable = false)
    private MealPlan mealPlan;
    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 16)
    private MealType mealType;
    @NotNull
    @Positive
    @Digits(integer = 4, fraction = 2)
    @Column(name = "portions", nullable = false, precision = 6, scale = 2)
    private BigDecimal portions;
    @Positive
    @NotNull
    @Column(name = "position", nullable = false)
    private Integer position;
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipe_snapshot", nullable = false, columnDefinition = "jsonb")
    private RecipeSnapshot recipeSnapshot;

    public MealPlanEntry(MealPlan mealPlan, Recipe recipe, MealType mealType,
                         BigDecimal portions, Integer position,
                         RecipeSnapshot recipeSnapshot) {
        this.mealPlan = mealPlan;
        this.recipe = recipe;
        this.mealType = mealType;
        this.portions = portions;
        this.position = position;
        this.recipeSnapshot = recipeSnapshot;
    }

    public void updatePortions(BigDecimal portions) {
        if (portions == null || portions.signum() <= 0) {
            throw new IllegalArgumentException("Количество порций должно быть больше нуля");
        }
        this.portions = portions;
    }
}
