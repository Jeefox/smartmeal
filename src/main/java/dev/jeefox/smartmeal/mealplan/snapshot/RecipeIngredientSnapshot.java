package dev.jeefox.smartmeal.mealplan.snapshot;

import java.math.BigDecimal;

public record RecipeIngredientSnapshot(
        Long productId,
        String productName,
        BigDecimal quantityG,
        String shoppingName,
        BigDecimal edibleYield
) {
    // Old JSON snapshots have neither shopping field: preserve their original meaning.
    public RecipeIngredientSnapshot {
        shoppingName = shoppingName == null ? productName : shoppingName;
        edibleYield = edibleYield == null ? BigDecimal.ONE : edibleYield;
        if (edibleYield.signum() <= 0 || edibleYield.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Invalid edible yield in recipe snapshot");
        }
    }

    public RecipeIngredientSnapshot(Long productId, String productName, BigDecimal quantityG) {
        this(productId, productName, quantityG, productName, BigDecimal.ONE);
    }
}
