package dev.jeefox.smartmeal.pantry;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/me/meal-plan-entries/{entry}/replacements")
public class SavedMealReplacementController {
    private final SavedMealReplacementService service;

    public SavedMealReplacementController(SavedMealReplacementService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public SavedMealReplacementService.Status status(Authentication a, @PathVariable UUID entry) {
        return service.status(a.getName(), entry);
    }

    @GetMapping
    public SavedMealReplacementService.Preview preview(Authentication a, @PathVariable UUID entry) {
        return service.preview(a.getName(), entry);
    }

    @PostMapping
    public PantryModels.Action apply(
            Authentication a,
            @PathVariable UUID entry,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody SavedMealReplacementService.Apply r) {
        return service.apply(a.getName(), key, entry, r);
    }

    @PostMapping("/{change}/undo")
    public PantryModels.Action undo(
            Authentication a,
            @PathVariable UUID entry,
            @PathVariable UUID change,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PantryModels.Command r) {
        return service.undo(a.getName(), key, entry, change, r);
    }
}
