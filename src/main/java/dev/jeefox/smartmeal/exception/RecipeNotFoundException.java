package dev.jeefox.smartmeal.exception;

import java.util.UUID;

public class RecipeNotFoundException extends RuntimeException {
    public RecipeNotFoundException(UUID uuid) {
        super("Рецепт с id " + uuid + " не найден");
    }
}
