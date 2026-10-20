package dev.jeefox.smartmeal.exception;

public class StoreInactiveException extends RuntimeException {
    public StoreInactiveException(Long storeId) {
        super("Магазин недоступен для расчёта: " + storeId);
    }
}
