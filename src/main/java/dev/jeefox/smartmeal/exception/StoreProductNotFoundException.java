package dev.jeefox.smartmeal.exception;

public class StoreProductNotFoundException extends RuntimeException {
    public StoreProductNotFoundException(Long id) {
        super("Товар магазина не найден: " + id);
    }
}
