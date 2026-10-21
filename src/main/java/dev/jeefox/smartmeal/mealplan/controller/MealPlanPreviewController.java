package dev.jeefox.smartmeal.mealplan.controller;

import dev.jeefox.smartmeal.mealplan.dto.MealPlanPreviewResponse;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanResponse;
import dev.jeefox.smartmeal.mealplan.dto.PreviewMealPlanRequest;
import dev.jeefox.smartmeal.mealplan.dto.SaveMealPlanPreviewRequest;
import dev.jeefox.smartmeal.mealplan.service.MealPlanPreviewService;
import dev.jeefox.smartmeal.mealplan.service.SaveMealPlanPreviewService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/me/meal-plans/preview")
public class MealPlanPreviewController {
    private final MealPlanPreviewService service;
    private final SaveMealPlanPreviewService saveService;

    public MealPlanPreviewController(MealPlanPreviewService service, SaveMealPlanPreviewService saveService) {
        this.service = service;
        this.saveService = saveService;
    }

    @PostMapping
    public MealPlanPreviewResponse preview(Authentication authentication, @Valid @RequestBody PreviewMealPlanRequest request) {
        return service.preview(authentication.getName(), request);
    }
    @PostMapping("/save")
    public ResponseEntity<MealPlanResponse> save(Authentication authentication,
                                                @Valid @RequestBody SaveMealPlanPreviewRequest request) {
        MealPlanResponse response = saveService.save(authentication.getName(), request);
        return ResponseEntity.created(URI.create("/api/me/meal-plans?date=" + response.planDate())).body(response);
    }
}
