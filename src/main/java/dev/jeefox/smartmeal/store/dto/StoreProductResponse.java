package dev.jeefox.smartmeal.store.dto;

import dev.jeefox.smartmeal.store.entity.StoreProduct;

import java.time.Instant;

public record StoreProductResponse(
        Long id,
        Long storeId,
        Long productId,
        String name,
        Integer packageWeightG,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static StoreProductResponse from(StoreProduct product){
        return new StoreProductResponse(
                product.getId(),
                product.getStore().getId(),
                product.getProduct().getId(),
                product.getName(),
                product.getPackageWeightG(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
