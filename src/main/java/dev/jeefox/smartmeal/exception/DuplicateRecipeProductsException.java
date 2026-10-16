package dev.jeefox.smartmeal.exception;

import java.util.List;

public class DuplicateRecipeProductsException extends RuntimeException {
    public DuplicateRecipeProductsException(List<Long> productsId) {
        super("Продукты с id: "+productsId.toString() + " повторяются");
    }
}
