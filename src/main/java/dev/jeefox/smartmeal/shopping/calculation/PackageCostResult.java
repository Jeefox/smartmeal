package dev.jeefox.smartmeal.shopping.calculation;

import java.math.BigDecimal;

public record PackageCostResult(
        int packageCount,
        BigDecimal purchaseCost,
        BigDecimal remainderG
) {
}
