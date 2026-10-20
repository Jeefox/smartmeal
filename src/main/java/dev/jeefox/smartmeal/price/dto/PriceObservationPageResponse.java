package dev.jeefox.smartmeal.price.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PriceObservationPageResponse(
        List<PriceObservationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public PriceObservationPageResponse(Page<PriceObservationResponse> page) {
        this(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
