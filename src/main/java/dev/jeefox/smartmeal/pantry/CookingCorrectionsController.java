package dev.jeefox.smartmeal.pantry;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/me/cooking-batches")
public class CookingCorrectionsController {
    private final CookingCorrections service;

    public CookingCorrectionsController(CookingCorrections service) {
        this.service = service;
    }

    @PostMapping("/{id}/weight-corrections")
    public PantryModels.Action weight(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody CookingCorrections.Weight r) {
        return service.weight(a.getName(), key, id, r);
    }

    @PostMapping("/entries/{id}/consumption-reversal")
    public PantryModels.Action reverse(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody CookingCorrections.Reversal r) {
        return service.undoConsumption(a.getName(), key, id, r);
    }

    @GetMapping("/{id}/corrections")
    public List<CookingCorrections.Change> history(Authentication a, @PathVariable UUID id) {
        return service.history(a.getName(), id);
    }
}
