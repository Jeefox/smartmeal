package dev.jeefox.smartmeal.exception;

public record ValidationError(String field, String message) {
}
