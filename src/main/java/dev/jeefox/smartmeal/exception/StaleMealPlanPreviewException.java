package dev.jeefox.smartmeal.exception;

public class StaleMealPlanPreviewException extends RuntimeException {
    public StaleMealPlanPreviewException() {
        super("Рецепты, ограничения или калорийная цель изменились. Обновите предпросмотр перед сохранением");
    }
}
