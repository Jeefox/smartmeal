package dev.jeefox.smartmeal.store.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record StoreProductPageResponse(
        List<StoreProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public StoreProductPageResponse(Page<StoreProductResponse> page) {
        this(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
