package dev.jeefox.smartmeal.store.dto;

import dev.jeefox.smartmeal.store.entity.Store;

public record StoreResponse(
        Long id,
        Long chainId,
        String chainName,
        String city,
        String address
) {
    public static StoreResponse from(Store store){
        return new StoreResponse(
                store.getId(),
                store.getChain().getId(),
                store.getChain().getName(),
                store.getCity(),
                store.getAddress()
        );
    }
}
