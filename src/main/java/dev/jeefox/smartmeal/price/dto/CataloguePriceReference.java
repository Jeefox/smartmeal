package dev.jeefox.smartmeal.price.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A published catalogue offer whose applicability to the selected store is unconfirmed. */
public record CataloguePriceReference(Long id, Long productId, String productName, String name,
                                      Integer packageWeightG, BigDecimal price, String sourceName,
                                      String sourceUrl, String sourceScope, Instant retrievedAt, Instant expiresAt, boolean soldByWeight) {
}
