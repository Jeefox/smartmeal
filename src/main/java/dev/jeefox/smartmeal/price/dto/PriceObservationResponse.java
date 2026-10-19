package dev.jeefox.smartmeal.price.dto;

import dev.jeefox.smartmeal.price.entity.PriceObservation;
import dev.jeefox.smartmeal.price.entity.PriceSource;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceObservationResponse(
        Long id,
        Long storeProductId,
        BigDecimal price,
        Instant observedAt,
        PriceSource priceSource,
        Boolean promotional,
        Instant createdAt
) {

    public static PriceObservationResponse from(PriceObservation observation){
        return new PriceObservationResponse(
                observation.getId(),
                observation.getStoreProduct().getId(),
                observation.getPrice(),
                observation.getObservedAt(),
                observation.getPriceSource(),
                observation.isPromotional(),
                observation.getCreatedAt());
    }
}
