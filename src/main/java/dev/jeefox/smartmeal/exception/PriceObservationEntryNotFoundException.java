package dev.jeefox.smartmeal.exception;

public class PriceObservationEntryNotFoundException extends RuntimeException {
    public PriceObservationEntryNotFoundException(Long id) {
        super("Запись цены не найдена для указанного товара: " + id);
    }
}
