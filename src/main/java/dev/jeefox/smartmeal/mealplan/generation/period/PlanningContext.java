package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;

import java.time.Instant;
import java.util.*;

public record PlanningContext(
        UUID ownerId,
        int target,
        String fingerprint,
        Instant asOf,
        Map<MealType, List<Candidate>> candidates,
        List<Offer> offers,
        List<Issue> issues,
        dev.jeefox.smartmeal.pantry.PantryModels.Snapshot pantry,
        List<dev.jeefox.smartmeal.household.HouseholdModels.Participant> participants) {
    public PlanningContext(
            UUID ownerId,
            int target,
            String fingerprint,
            Instant asOf,
            Map<MealType, List<Candidate>> candidates,
            List<Offer> offers,
            List<Issue> issues,
            dev.jeefox.smartmeal.pantry.PantryModels.Snapshot pantry) {
        this(ownerId, target, fingerprint, asOf, candidates, offers, issues, pantry, List.of());
    }

    public PlanningContext {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }

    public PlanningContext(
            UUID ownerId,
            int target,
            String fingerprint,
            Instant asOf,
            Map<MealType, List<Candidate>> candidates,
            List<Offer> offers,
            List<Issue> issues) {
        this(
                ownerId,
                target,
                fingerprint,
                asOf,
                candidates,
                offers,
                issues,
                dev.jeefox.smartmeal.pantry.PantryModels.Snapshot.empty());
    }

    public List<Candidate> allCandidates() {
        return candidates.values().stream().flatMap(List::stream).distinct().toList();
    }

    public Candidate candidate(UUID id) {
        return allCandidates().stream()
                .filter(c -> c.snapshot().recipeId().equals(id))
                .findFirst()
                .orElseThrow(
                        () ->
                                PlanningException.conflict(
                                        "Рецепт больше не подходит настройкам подбора. Пересоберите"
                                                + " меню."));
    }
}
