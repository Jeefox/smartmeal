package dev.jeefox.smartmeal.mealplan.snapshot;

import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecipeSnapshotFingerprintTest {
    private final UUID id = UUID.randomUUID();

    @Test
    void numericScaleDoesNotChangeFingerprint() {
        assertEquals(RecipeSnapshotFingerprint.calculate(snapshot("100", "1", "10.0", "Приготовить")),
                RecipeSnapshotFingerprint.calculate(snapshot("100.00", "1.000000", "10.00", "Приготовить")));
    }

    @Test
    void ingredientsInstructionsAndNutritionAffectFingerprint() {
        String original = RecipeSnapshotFingerprint.calculate(snapshot("100", "1", "10", "Приготовить"));
        assertNotEquals(original, RecipeSnapshotFingerprint.calculate(snapshot("101", "1", "10", "Приготовить")));
        assertNotEquals(original, RecipeSnapshotFingerprint.calculate(snapshot("100", "0.5", "10", "Приготовить")));
        assertNotEquals(original, RecipeSnapshotFingerprint.calculate(snapshot("100", "1", "11", "Приготовить")));
        assertNotEquals(original, RecipeSnapshotFingerprint.calculate(snapshot("100", "1", "10", "Другой способ")));
        assertTrue(original.matches("[a-f0-9]{64}"));
    }

    private RecipeSnapshot snapshot(String quantity, String yield, String calories, String instructions) {
        NutritionValues values = new NutritionValues(new BigDecimal(calories), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        return new RecipeSnapshot(id, "Рецепт", null, instructions, 10, 1,
                List.of(new RecipeIngredientSnapshot(1L, "Продукт", new BigDecimal(quantity), "Покупка", new BigDecimal(yield))),
                values, values);
    }
}
