package dev.jeefox.smartmeal.exception;

public class StoreNotFoundException extends RuntimeException {
    public StoreNotFoundException(Long id) {
        super("Магазин не найден: " + id);
    }
}
