package dev.jeefox.smartmeal.exception;

public class MealPlanNotEditableException extends RuntimeException {
    public MealPlanNotEditableException() {
        super("Подтверждённое меню нельзя редактировать");
    }
}
