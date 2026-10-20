package dev.jeefox.smartmeal.exception;

public class InvalidShoppingListPeriodException extends RuntimeException {
    public InvalidShoppingListPeriodException() {
        super("Начало периода не должно быть позже окончания; период не должен превышать 31 день включительно");
    }
}
