package dev.jeefox.smartmeal.mealplan.entity;

import dev.jeefox.smartmeal.exception.MealPlanNotEditableException;
import dev.jeefox.smartmeal.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MealPlanTest {
    private MealPlan draft() {
        return new MealPlan(new User("test@example.invalid", "unused-test-hash"), LocalDate.of(2032, 1, 1));
    }

    @Test
    void confirmsDraftAndCapturesGoal() {
        MealPlan plan = draft();
        plan.confirm(2200);
        assertEquals(MealPlanStatus.CONFIRMED, plan.getStatus());
        assertEquals(2200, plan.getDailyCalorieTargetSnapshot());
        assertNotNull(plan.getConfirmedAt());
    }

    @Test
    void rejectsNullGoalBeforeMutatingState() {
        MealPlan plan = draft();
        assertThrows(NullPointerException.class, () -> plan.confirm(null));
        assertDraft(plan);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveGoalBeforeMutatingState(int goal) {
        MealPlan plan = draft();
        assertThrows(IllegalArgumentException.class, () -> plan.confirm(goal));
        assertDraft(plan);
    }

    @Test
    void cannotConfirmAgainWithAnotherGoal() {
        MealPlan plan = draft();
        plan.confirm(2200);
        var confirmedAt = plan.getConfirmedAt();
        assertThrows(MealPlanNotEditableException.class, () -> plan.confirm(2500));
        assertEquals(2200, plan.getDailyCalorieTargetSnapshot());
        assertEquals(confirmedAt, plan.getConfirmedAt());
    }

    private void assertDraft(MealPlan plan) {
        assertEquals(MealPlanStatus.DRAFT, plan.getStatus());
        assertNull(plan.getDailyCalorieTargetSnapshot());
        assertNull(plan.getConfirmedAt());
    }
}
