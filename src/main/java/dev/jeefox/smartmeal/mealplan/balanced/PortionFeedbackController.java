package dev.jeefox.smartmeal.mealplan.balanced;

import static dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.*;

import dev.jeefox.smartmeal.household.HouseholdSnapshots;
import dev.jeefox.smartmeal.mealplan.generation.period.PlanningException;
import dev.jeefox.smartmeal.pantry.PantryService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/me/meal-plan-entries/{entryId}/portion-feedback")
@Transactional
public class PortionFeedbackController {
    private final NamedParameterJdbcTemplate jdbc;
    private final PantryService pantry;
    private final HouseholdSnapshots households;

    public PortionFeedbackController(
            NamedParameterJdbcTemplate jdbc, PantryService pantry, HouseholdSnapshots households) {
        this.jdbc = jdbc;
        this.pantry = pantry;
        this.households = households;
    }

    private UUID owned(UUID u, UUID entry) {
        var ids =
                jdbc.queryForList(
                        "select e.meal_plan_id from meal_plan_entries e join meal_plans m on"
                            + " m.id=e.meal_plan_id where e.id=:e and m.user_id=:u",
                        Map.of("e", entry, "u", u),
                        UUID.class);
        if (ids.isEmpty())
            throw new PlanningException(
                    HttpStatus.NOT_FOUND, "ENTRY_NOT_FOUND", "Приём пищи не найден");
        return ids.getFirst();
    }

    @GetMapping
    public List<Feedback> get(Authentication a, @PathVariable UUID entryId) {
        UUID u = pantry.lockOwner(a.getName());
        owned(u, entryId);
        return jdbc.query(
                "select participant_id,rating,revision from portion_feedback where user_id=:u and"
                    + " meal_plan_entry_id=:e order by participant_id",
                Map.of("u", u, "e", entryId),
                (rs, n) ->
                        new Feedback(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)));
    }

    @PutMapping("/{personId}")
    public Feedback put(
            Authentication a,
            @PathVariable UUID entryId,
            @PathVariable UUID personId,
            @Valid @RequestBody FeedbackRequest r) {
        UUID u = pantry.lockOwner(a.getName());
        UUID plan = owned(u, entryId);
        if (!(personId.equals(u)
                || households.people(plan).stream().anyMatch(p -> p.id().equals(personId))))
            throw new PlanningException(
                    HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", "Участник не найден");
        if (!Set.of("TOO_LITTLE", "ENOUGH", "TOO_MUCH").contains(r.rating()))
            throw new PlanningException(
                    HttpStatus.BAD_REQUEST, "INVALID_FEEDBACK", "Выберите оценку количества");
        if (jdbc.queryForObject(
                        "select count(*) from meal_entry_inventory_states where"
                            + " meal_plan_entry_id=:e and state='DEDUCTED'",
                        Map.of("e", entryId),
                        Integer.class)
                == 0)
            throw PlanningException.conflict(
                    "MEAL_NOT_PREPARED", "Сначала подтвердите приготовление этого приёма пищи");
        var current =
                get(a, entryId).stream()
                        .filter(f -> f.participantId().equals(personId))
                        .findFirst();
        if (current.map(Feedback::revision).orElse(0L) != r.expectedRevision())
            throw PlanningException.conflict(
                    "FEEDBACK_STALE", "Оценка изменена на другом устройстве. Обновите её.");
        jdbc.update(
                "insert into portion_feedback(user_id,meal_plan_entry_id,participant_id,rating)"
                    + " values(:u,:e,:p,:r) on conflict(meal_plan_entry_id,participant_id) do"
                    + " update set rating=:r,revision=portion_feedback.revision+1,updated_at=now()",
                Map.of("u", u, "e", entryId, "p", personId, "r", r.rating()));
        return get(a, entryId).stream()
                .filter(f -> f.participantId().equals(personId))
                .findFirst()
                .orElseThrow();
    }
}
