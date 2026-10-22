package dev.jeefox.smartmeal.pantry;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class PantryException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, ?> details;

    public PantryException(HttpStatus status, String code, String message, Map<String, ?> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, ?> details() {
        return details;
    }

    public static PantryException conflict(String code, String message) {
        return new PantryException(HttpStatus.CONFLICT, code, message, Map.of());
    }

    public static PantryException invalid(String message) {
        return new PantryException(
                HttpStatus.BAD_REQUEST, "INVALID_PANTRY_INPUT", message, Map.of());
    }

    public static PantryException missing() {
        return new PantryException(
                HttpStatus.NOT_FOUND, "PANTRY_NOT_FOUND", "Запись не найдена", Map.of());
    }
}
