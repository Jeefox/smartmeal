package dev.jeefox.smartmeal.exception;

public class ProductNotFoundException extends RuntimeException{
    public ProductNotFoundException (Long id){
        super("Продукт с id " + id + " не найден");
    }
}
