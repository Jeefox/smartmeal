package dev.jeefox.smartmeal.mealplan.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PreviewMealPlanRequest(
        @NotNull LocalDate planDate,
        @Min(0) Integer breakfastMaxPreparationTimeMin,
        @Min(0) Integer lunchMaxPreparationTimeMin,
        @Min(0) Integer dinnerMaxPreparationTimeMin,
        @Positive Long storeId,
        @Positive @Digits(integer = 10, fraction = 2) BigDecimal budget
) {
    public PreviewMealPlanRequest(LocalDate planDate, Integer breakfast, Integer lunch, Integer dinner) {
        this(planDate, breakfast, lunch, dinner, null, null);
    }

    @AssertTrue(message = "Для подбора по бюджету укажите и магазин, и бюджет")
    public boolean isBudgetSelectionValid() {
        return (storeId == null) == (budget == null);
    }

    public PreviewMealPlanRequest {
        breakfastMaxPreparationTimeMin = breakfastMaxPreparationTimeMin == null ? 15 : breakfastMaxPreparationTimeMin;
        lunchMaxPreparationTimeMin = lunchMaxPreparationTimeMin == null ? 45 : lunchMaxPreparationTimeMin;
        dinnerMaxPreparationTimeMin = dinnerMaxPreparationTimeMin == null ? 45 : dinnerMaxPreparationTimeMin;
    }
}
