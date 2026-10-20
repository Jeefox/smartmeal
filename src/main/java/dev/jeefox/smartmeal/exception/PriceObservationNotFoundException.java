package dev.jeefox.smartmeal.exception;

public class PriceObservationNotFoundException extends RuntimeException {
    public PriceObservationNotFoundException(Long id) {
        super("Цена ещё не добавлена для товара магазина: " + id);
    }
}
