package dev.jeefox.smartmeal.exception;

public class DailyCalorieTargetNotSetException extends RuntimeException {
    public DailyCalorieTargetNotSetException() {
        super("Задайте суточную цель перед подтверждением меню");
    }
}
