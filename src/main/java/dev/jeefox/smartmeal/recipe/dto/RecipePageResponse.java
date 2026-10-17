package dev.jeefox.smartmeal.recipe.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record RecipePageResponse(
        List<RecipeSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public RecipePageResponse(Page<RecipeSummaryResponse> page) {
        this(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
