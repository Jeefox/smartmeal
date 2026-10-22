package dev.jeefox.smartmeal.exception;

import java.util.UUID;

public class RecipeImageNotFoundException extends RuntimeException {
    public RecipeImageNotFoundException(UUID id) {
        super("Изображение рецепта не найдено: " + id);
    }
}
