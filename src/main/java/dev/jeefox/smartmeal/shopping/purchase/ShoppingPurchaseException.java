package dev.jeefox.smartmeal.shopping.purchase;

import org.springframework.http.HttpStatus;

public class ShoppingPurchaseException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ShoppingPurchaseException(HttpStatus status, String code, String message) {
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

    static ShoppingPurchaseException conflict(String code, String message) {
        return new ShoppingPurchaseException(HttpStatus.CONFLICT, code, message);
    }

    static ShoppingPurchaseException invalid(String message) {
        return new ShoppingPurchaseException(
                HttpStatus.BAD_REQUEST, "INVALID_SHOPPING_INPUT", message);
    }

    static ShoppingPurchaseException missing() {
        return new ShoppingPurchaseException(
                HttpStatus.NOT_FOUND, "SHOPPING_NOT_FOUND", "Список или позиция не найдены");
    }
}
