package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.pantry.PantryModels.*;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/me")
public class PantryController {
    private final PantryService pantry;

    public PantryController(PantryService pantry) {
        this.pantry = pantry;
    }

    @GetMapping("/pantry")
    public View view(Authentication a) {
        return pantry.view(a.getName());
    }

    @GetMapping("/pantry/lots")
    public java.util.List<Lot> lots(
            Authentication a, @RequestParam(required = false) Long productId) {
        return pantry.view(a.getName()).lots().stream()
                .filter(l -> productId == null || productId.equals(l.productId()))
                .toList();
    }

    @GetMapping("/pantry/operations")
    public History history(Authentication a, @RequestParam(defaultValue = "0") int page) {
        return pantry.history(a.getName(), page);
    }

    @PostMapping("/pantry/lots")
    public Action add(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody AddLot r) {
        return pantry.addLot(a.getName(), key, r);
    }

    @PostMapping("/pantry/purchases")
    public Action purchase(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Purchase r) {
        return pantry.purchase(a.getName(), key, r);
    }

    @PatchMapping("/pantry/lots/{id}")
    public Action metadata(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Metadata r) {
        return pantry.metadata(a.getName(), key, id, r);
    }

    @PostMapping("/pantry/lots/{id}/adjustments")
    public Action adjust(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Adjustment r) {
        return pantry.adjust(a.getName(), key, id, r, false);
    }

    @PostMapping("/pantry/lots/{id}/discards")
    public Action discard(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Adjustment r) {
        return pantry.adjust(a.getName(), key, id, r, true);
    }

    @GetMapping("/meal-plans/{id}/inventory")
    public EntryStates states(Authentication a, @PathVariable UUID id) {
        return pantry.states(a.getName(), id);
    }

    @PostMapping("/meal-plan-entries/{id}/ingredient-usage")
    public Action use(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Command r) {
        return pantry.usage(a.getName(), key, id, r);
    }

    @PostMapping("/meal-plan-entries/{id}/ingredient-usage/reversal")
    public Action reverse(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Command r) {
        return pantry.reverse(a.getName(), key, id, r);
    }

    @PutMapping("/meal-plan-entries/{id}/inventory-state")
    public Action state(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody StateCommand r) {
        return pantry.state(a.getName(), key, id, r);
    }

    @PostMapping("/meal-plans/{id}/cancel")
    public Action cancel(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Command r) {
        return pantry.plan(a.getName(), key, id, r, false);
    }

    @PostMapping("/meal-plans/{id}/reopen")
    public Action reopen(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Command r) {
        return pantry.plan(a.getName(), key, id, r, true);
    }
}
