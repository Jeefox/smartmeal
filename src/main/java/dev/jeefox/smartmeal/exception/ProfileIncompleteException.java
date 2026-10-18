package dev.jeefox.smartmeal.exception;

public class ProfileIncompleteException extends RuntimeException {
    public ProfileIncompleteException() {
        super("Заполните анкету для расчёта");
    }
}
