package dev.jeefox.smartmeal.shopping.dto;

import dev.jeefox.smartmeal.price.entity.PriceSource;

import java.math.BigDecimal;
import java.time.Instant;

public record ShoppingEstimateItemResponse(
        Long productId,
        String productName,
        BigDecimal requiredWeightG,
        Long storeProductId,
        String storeProductName,
        Integer packageWeightG,
        Integer packageCount,
        BigDecimal packagePrice,
        BigDecimal purchaseCost,
        BigDecimal remainderG,
        Instant priceObservedAt,
        PriceSource priceSource,
        boolean referencePrice,
        String referenceSourceUrl,
        String referenceSourceName,
        String referenceSourceScope
) {
    public ShoppingEstimateItemResponse(Long productId, String productName, BigDecimal requiredWeightG,
            Long storeProductId, String storeProductName, Integer packageWeightG, Integer packageCount,
            BigDecimal packagePrice, BigDecimal purchaseCost, BigDecimal remainderG,
            Instant priceObservedAt, PriceSource priceSource) {
        this(productId, productName, requiredWeightG, storeProductId, storeProductName, packageWeightG,
                packageCount, packagePrice, purchaseCost, remainderG, priceObservedAt, priceSource,
                false, null, null, null);
    }
}
