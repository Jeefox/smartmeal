package dev.jeefox.smartmeal.exception;

import java.time.LocalDate;

public class MealPlanNotFoundException extends RuntimeException {
    public MealPlanNotFoundException(LocalDate planDate) {
        super("Меню на эту дату не найдено: "+ planDate);
    }
    public MealPlanNotFoundException(java.util.UUID id) {
        super("Меню не найдено: " + id);
    }
}