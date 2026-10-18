package dev.jeefox.smartmeal.mealplan.controller;

import dev.jeefox.smartmeal.mealplan.dto.AddMealPlanEntryRequest;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanEntryResponse;
import dev.jeefox.smartmeal.mealplan.dto.UpdateMealPlanEntryRequest;
import dev.jeefox.smartmeal.mealplan.service.MealPlanEntryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/me/meal-plans/{planId}/entries")
public class MealPlanEntryController {
    private final MealPlanEntryService service;

    public MealPlanEntryController(MealPlanEntryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MealPlanEntryResponse> add(Authentication authentication,
            @PathVariable("planId") UUID planId, @Valid @RequestBody AddMealPlanEntryRequest request) {
        MealPlanEntryResponse response = service.add(authentication.getName(), planId, request);
        return ResponseEntity.created(URI.create("/api/me/meal-plans/" + planId + "/entries/" + response.id()))
                .body(response);
    }

    @GetMapping
    public List<MealPlanEntryResponse> findAll(Authentication authentication, @PathVariable("planId") UUID planId) {
        return service.findAll(authentication.getName(), planId);
    }

    @GetMapping("/{entryId}")
    public MealPlanEntryResponse findById(Authentication authentication, @PathVariable("planId") UUID planId,
                                        @PathVariable("entryId") UUID entryId) {
        return service.findById(authentication.getName(), planId, entryId);
    }

    @PutMapping("/{entryId}")
    public MealPlanEntryResponse updatePortions(Authentication authentication,
            @PathVariable("planId") UUID planId, @PathVariable("entryId") UUID entryId,
            @Valid @RequestBody UpdateMealPlanEntryRequest request) {
        return service.updatePortions(authentication.getName(), planId, entryId, request);
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> delete(Authentication authentication,
            @PathVariable("planId") UUID planId, @PathVariable("entryId") UUID entryId) {
        service.delete(authentication.getName(), planId, entryId);
        return ResponseEntity.noContent().build();
    }
}
