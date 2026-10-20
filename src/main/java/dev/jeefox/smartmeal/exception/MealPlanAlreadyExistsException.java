package dev.jeefox.smartmeal.exception;

import java.time.LocalDate;

public class MealPlanAlreadyExistsException extends RuntimeException {
    public MealPlanAlreadyExistsException(LocalDate planDate) {
        super("Меню на эту дату уже есть: "+ planDate);
    }
}
