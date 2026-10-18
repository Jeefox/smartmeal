package dev.jeefox.smartmeal.mealplan.controller;

import dev.jeefox.smartmeal.mealplan.dto.CreateMealPlanRequest;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanResponse;
import dev.jeefox.smartmeal.mealplan.service.MealPlanService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/me/meal-plans")
public class MealPlanController {

    private final MealPlanService mealPlanService;

    public MealPlanController(MealPlanService mealPlanService) {
        this.mealPlanService = mealPlanService;
    }

    @PostMapping
    public ResponseEntity<MealPlanResponse> create(
            Authentication authentication,
            @Valid @RequestBody CreateMealPlanRequest request
            ){
        MealPlanResponse response = mealPlanService.create(
                authentication.getName(),
                request
        );
        URI location = URI.create(
                "/api/me/meal-plans?date="+response.planDate()
        );
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public MealPlanResponse findByDate(
            Authentication authentication,
            @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)LocalDate date
            ){
        return mealPlanService.findByDate(
                authentication.getName(),
                date
        );
    }

    @PostMapping("/{planId}/confirm")
    public MealPlanResponse confirm(
            Authentication authentication,
            @PathVariable("planId") UUID planId) {
        return mealPlanService.confirmMenu(
                authentication.getName(), planId);
    }
}
