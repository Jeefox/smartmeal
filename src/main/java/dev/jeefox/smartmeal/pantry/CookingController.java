package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.pantry.CookingModels.*;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/me/cooking-batches")
public class CookingController {
    private final CookingService service;
    private final PantryService pantry;

    public CookingController(CookingService service, PantryService pantry) {
        this.service = service;
        this.pantry = pantry;
    }

    @GetMapping
    public View view(
            Authentication a,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        var start = from == null ? pantry.today() : from;
        return service.view(a.getName(), start, to == null ? start.plusDays(6) : to);
    }

    @PostMapping("/confirm-period")
    public PantryModels.Action confirm(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Confirm r) {
        return service.confirm(a.getName(), key, r);
    }

    @PostMapping
    public PantryModels.Action create(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Create r) {
        return service.create(a.getName(), key, r);
    }

    @PostMapping("/{id}/prepare")
    public PantryModels.Action prepare(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Prepare r) {
        return service.prepare(a.getName(), key, id, r);
    }

    @PostMapping("/{id}/assign")
    public PantryModels.Action assign(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody Assign r) {
        return service.assign(a.getName(), key, id, r);
    }

    @PostMapping("/{id}/discard")
    public PantryModels.Action discard(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody PantryModels.Command r) {
        return service.discard(a.getName(), key, id, r);
    }

    @PostMapping("/entries/{id}/consume")
    public PantryModels.Action consume(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,
            @Valid @RequestBody PantryModels.Command r) {
        return service.consume(a.getName(), key, id, r);
    }
}
