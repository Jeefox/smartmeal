package dev.jeefox.smartmeal.exception;

public class InvalidPriceEntryException extends RuntimeException {
    public InvalidPriceEntryException(String message) {
        super(message);
    }
}
