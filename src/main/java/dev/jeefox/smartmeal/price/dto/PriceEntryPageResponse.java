package dev.jeefox.smartmeal.price.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PriceEntryPageResponse(List<Item> content, int page, int size, long totalElements, int totalPages) {
    public record Item(Long productId, String productName, String shoppingName, long recipeCount, List<Offer> offers) {
    }
    public record Offer(Long storeProductId, String name, int packageWeightG, BigDecimal price, Instant observedAt) {
    }
}
