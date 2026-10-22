package dev.jeefox.smartmeal.mealplan.generation.period;

import org.springframework.http.HttpStatus;

public class PlanningException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public PlanningException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static PlanningException conflict(String message) {
        return new PlanningException(HttpStatus.CONFLICT, "PREVIEW_CONFLICT", message);
    }
}
