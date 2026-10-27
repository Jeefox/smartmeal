package dev.jeefox.smartmeal.nutrition.dto;

import java.math.*;
import java.util.*;

public record NutritionValues(
        BigDecimal calories,
        BigDecimal protein,
        BigDecimal fat,
        BigDecimal carbs,
        BigDecimal knownFiber,
        Boolean fiberComplete) {
    public NutritionValues {
        knownFiber = knownFiber == null ? BigDecimal.ZERO : knownFiber.stripTrailingZeros();
        fiberComplete = Boolean.TRUE.equals(fiberComplete);
    }

    public NutritionValues(
            BigDecimal calories, BigDecimal protein, BigDecimal fat, BigDecimal carbs) {
        this(calories, protein, fat, carbs, BigDecimal.ZERO, false);
    }

    public NutritionValues scaled(BigDecimal multiplier) {
        return new NutritionValues(
                calories.multiply(multiplier),
                protein.multiply(multiplier),
                fat.multiply(multiplier),
                carbs.multiply(multiplier),
                knownFiber.multiply(multiplier),
                fiberComplete);
    }

    public NutritionValues rounded() {
        return new NutritionValues(
                round(calories),
                round(protein),
                round(fat),
                round(carbs),
                round(knownFiber),
                fiberComplete);
    }

    private static BigDecimal round(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    public static NutritionValues sum(List<NutritionValues> values) {
        BigDecimal c = BigDecimal.ZERO, p = c, f = c, h = c, b = c;
        boolean complete = true;
        for (var v : values) {
            c = c.add(v.calories);
            p = p.add(v.protein);
            f = f.add(v.fat);
            h = h.add(v.carbs);
            b = b.add(v.knownFiber);
            complete &= v.fiberComplete;
        }
        return new NutritionValues(c, p, f, h, b, complete);
    }
}
