package dev.jeefox.smartmeal.exception;

import java.util.UUID;

public class MealPlanEntryNotFoundException extends RuntimeException {
    public MealPlanEntryNotFoundException(UUID id) {
        super("Блюдо меню не найдено: " + id);
    }
}
