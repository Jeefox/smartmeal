package dev.jeefox.smartmeal.shopping.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ShoppingEstimateResponse(
        LocalDate from,
        LocalDate to,
        Long storeId,
        boolean containsDrafts,
        boolean complete,
        BigDecimal knownPurchaseCost,
        List<ShoppingEstimateItemResponse> items
) {
}
