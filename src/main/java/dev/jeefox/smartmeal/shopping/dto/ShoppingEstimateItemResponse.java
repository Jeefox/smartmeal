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
        PriceSource priceSource
) {
}
