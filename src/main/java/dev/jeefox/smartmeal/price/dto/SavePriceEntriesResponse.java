package dev.jeefox.smartmeal.price.dto;

import java.util.List;
import java.util.UUID;

public record SavePriceEntriesResponse(UUID requestId, Long storeId, int savedCount, List<Entry> entries) {
    public record Entry(Long productId, Long storeProductId, Long priceObservationId, boolean createdStoreProduct) {
    }
}
