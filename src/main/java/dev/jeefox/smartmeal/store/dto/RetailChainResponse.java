package dev.jeefox.smartmeal.store.dto;

import dev.jeefox.smartmeal.store.entity.RetailChain;

public record RetailChainResponse(
        Long id,
        String code,
        String name
) {
    public static RetailChainResponse from(RetailChain retailChain){
        return new RetailChainResponse(
                retailChain.getId(),
                retailChain.getCode(),
                retailChain.getName()
        );
    }
}
