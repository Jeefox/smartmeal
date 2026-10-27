package dev.jeefox.smartmeal.shopping.dto;

import java.math.BigDecimal;

public record ShoppingListItemResponse(
        Long productId,
        String productName,
        BigDecimal quantityG,
        BigDecimal requiredG,
        BigDecimal fromPantryG) {
    public ShoppingListItemResponse {
        productName = dev.jeefox.smartmeal.shopping.ShoppingProductNames.display(productName);
        quantityG = quantityG.setScale(30, java.math.RoundingMode.HALF_UP);
        requiredG = requiredG.setScale(30, java.math.RoundingMode.HALF_UP);
        fromPantryG = fromPantryG.setScale(30, java.math.RoundingMode.HALF_UP);
    }

    public ShoppingListItemResponse(Long productId, String productName, BigDecimal quantityG) {
        this(productId, productName, quantityG, quantityG, BigDecimal.ZERO);
    }
}
