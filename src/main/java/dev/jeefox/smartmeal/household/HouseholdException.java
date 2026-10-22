package dev.jeefox.smartmeal.household;

import org.springframework.http.HttpStatus;

public class HouseholdException extends RuntimeException {
    private final HttpStatus status;

    public HouseholdException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
