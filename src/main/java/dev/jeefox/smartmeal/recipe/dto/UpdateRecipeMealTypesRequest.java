package dev.jeefox.smartmeal.recipe.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record UpdateRecipeMealTypesRequest(@NotNull Set<@NotNull MealType> mealTypes) {
}
