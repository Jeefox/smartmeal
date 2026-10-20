package dev.jeefox.smartmeal.exception;

public class MenuEntryNotExistsException extends RuntimeException {
    public MenuEntryNotExistsException() {
        super("Добавьте хотя бы одно блюдо перед подтверждением меню");
    }
}
