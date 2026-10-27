package dev.jeefox.smartmeal.mealplan.snapshot;

import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RecipeSnapshotFingerprint {
    private RecipeSnapshotFingerprint() {}

    public static String calculate(RecipeSnapshot snapshot) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "recipe-snapshot-v1");
        append(canonical, snapshot.recipeId());
        append(canonical, snapshot.name());
        append(canonical, snapshot.description());
        append(canonical, snapshot.instructions());
        append(canonical, snapshot.preparationTimeMin());
        append(canonical, snapshot.servings());
        append(canonical, snapshot.ingredients().size());
        for (var item : snapshot.ingredients()) {
            append(canonical, item.productId());
            append(canonical, item.productName());
            append(canonical, item.quantityG());
            append(canonical, item.shoppingName());
            append(canonical, item.edibleYield());
        }
        nutrition(canonical, snapshot.nutritionTotal());
        nutrition(canonical, snapshot.nutritionPerServing());
        if (!snapshot.components().isEmpty())
            for (var component : snapshot.components()) {
                append(canonical, component.id());
                append(canonical, component.role());
                append(canonical, calculate(component.recipe()));
                append(canonical, component.profile());
                append(canonical, component.amounts());
                append(canonical, component.locked());
            }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void nutrition(StringBuilder target, NutritionValues nutrition) {
        append(target, nutrition.calories());
        append(target, nutrition.protein());
        append(target, nutrition.fat());
        append(target, nutrition.carbs());
        if (nutrition.knownFiber().signum() != 0 || nutrition.fiberComplete()) {
            append(target, nutrition.knownFiber());
            append(target, nutrition.fiberComplete());
        }
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("-1:");
            return;
        }
        String text =
                value instanceof BigDecimal decimal
                        ? decimal.stripTrailingZeros().toPlainString()
                        : value.toString();
        target.append(text.length()).append(':').append(text);
    }
}
