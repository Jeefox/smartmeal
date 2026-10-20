package dev.jeefox.smartmeal.shopping.dto;

import java.math.BigDecimal;

public record ShoppingListItemResponse (
        Long productId,
        String productName,
        BigDecimal quantityG
){
}
