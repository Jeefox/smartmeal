package dev.jeefox.smartmeal.shopping.dto;

import java.time.LocalDate;
import java.util.List;

public record ShoppingComparisonResponse(
        LocalDate from,
        LocalDate to,
        boolean containsDrafts,
        List<ShoppingEstimateResponse> shoppingEstimateResponseList) {
}
