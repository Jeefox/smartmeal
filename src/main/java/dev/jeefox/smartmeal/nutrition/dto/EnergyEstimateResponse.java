package dev.jeefox.smartmeal.nutrition.dto;

import java.math.BigDecimal;

public record EnergyEstimateResponse (
        BigDecimal restingCalories,
        BigDecimal maintenanceCalories,
        BigDecimal recommendedCalories
){
}
