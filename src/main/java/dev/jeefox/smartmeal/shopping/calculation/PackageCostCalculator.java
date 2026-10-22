package dev.jeefox.smartmeal.shopping.calculation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Component
public class PackageCostCalculator {

    public PackageCostResult calculate(
            BigDecimal requiredWeightG, Integer packageWeightG, BigDecimal price) {
        Objects.requireNonNull(
                requiredWeightG, "Требуемый вес (requiredWeightG) не должен быть null");
        Objects.requireNonNull(packageWeightG, "Вес упаковки (packageWeightG) не должен быть null");
        Objects.requireNonNull(price, "Цена (price) не должна быть null");

        if (requiredWeightG.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Требуемый вес должен быть больше 0");
        }

        if (packageWeightG <= 0) {
            throw new IllegalArgumentException("Вес упаковки должен быть больше 0");
        }

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Цена должна быть больше 0");
        }

        int packageCount =
                WeightPrecision.packages(requiredWeightG, BigDecimal.valueOf(packageWeightG));
        BigDecimal totalPrice = price.multiply(new BigDecimal(packageCount));
        BigDecimal remainderG =
                new BigDecimal(packageCount)
                        .multiply(BigDecimal.valueOf(packageWeightG))
                        .subtract(requiredWeightG)
                        .max(BigDecimal.ZERO);
        remainderG = remainderG.setScale(0, RoundingMode.FLOOR);

        return new PackageCostResult(packageCount, totalPrice, remainderG);
    }
}
