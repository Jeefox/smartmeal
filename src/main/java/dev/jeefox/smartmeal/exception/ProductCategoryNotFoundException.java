package dev.jeefox.smartmeal.exception;

import java.text.MessageFormat;

public class ProductCategoryNotFoundException extends RuntimeException{
    public ProductCategoryNotFoundException(Long id) {
        super("Категория с id " + id + " не найдена");
    }
}