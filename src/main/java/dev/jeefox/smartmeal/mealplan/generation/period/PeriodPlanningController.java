package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/me/meal-plan-generations")
public class PeriodPlanningController {
    private final PeriodPlanningService service;
    private final GenerationCommitService saves;

    public PeriodPlanningController(PeriodPlanningService service, GenerationCommitService saves) {
        this.service = service;
        this.saves = saves;
    }

    @PostMapping
    public ResponseEntity<View> create(Authentication auth, @Valid @RequestBody Options options) {
        View view = service.create(auth.getName(), options);
        return ResponseEntity.created(URI.create("/api/me/meal-plan-generations/" + view.id()))
                .body(view);
    }

    @GetMapping("/{id}")
    public View get(Authentication auth, @PathVariable UUID id) {
        return service.get(auth.getName(), id);
    }

    @GetMapping("/{id}/entries/{entryId}/alternatives")
    public List<Alternative> alternatives(
            Authentication auth, @PathVariable UUID id, @PathVariable String entryId) {
        return service.alternatives(auth.getName(), id, entryId);
    }

    @PutMapping("/{id}/entries/{entryId}")
    public View replace(
            Authentication auth,
            @PathVariable UUID id,
            @PathVariable String entryId,
            @Valid @RequestBody Replacement request) {
        return service.replace(auth.getName(), id, entryId, request);
    }

    @PatchMapping("/{id}/entries/{entryId}/lock")
    public View lock(
            Authentication auth,
            @PathVariable UUID id,
            @PathVariable String entryId,
            @Valid @RequestBody Lock request) {
        return service.lock(auth.getName(), id, entryId, request);
    }

    @PostMapping("/{id}/refresh")
    public View refresh(
            Authentication auth, @PathVariable UUID id, @Valid @RequestBody Revision request) {
        return service.refresh(auth.getName(), id, request.expectedRevision());
    }

    @PostMapping("/{id}/regenerate")
    public View regenerate(
            Authentication auth, @PathVariable UUID id, @Valid @RequestBody Revision request) {
        return service.regenerate(auth.getName(), id, request.expectedRevision());
    }

    @PostMapping("/{id}/commit")
    public View commit(
            Authentication auth,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Commit request) {
        return saves.commit(auth.getName(), id, key, request);
    }
}
