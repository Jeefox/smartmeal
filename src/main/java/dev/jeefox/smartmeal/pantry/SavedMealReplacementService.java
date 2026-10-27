package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;

import jakarta.validation.constraints.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class SavedMealReplacementService {
    public record Status(
            long revision, boolean canReplace, String reason, UUID undoId, String undoName) {}

    public record Preview(
            long revision, LocalDate from, LocalDate to, List<Alternative> alternatives) {}

    public record Apply(
            @Min(0) long expectedRevision,
            @NotNull UUID recipeId,
            @NotNull @Positive @Digits(integer = 4, fraction = 2) BigDecimal portions,
            @NotBlank @Size(max = 100) String candidateKey,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String comparisonKey) {}

    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManager em;

    private final NamedParameterJdbcTemplate jdbc;
    private final PantryService pantry;
    private final SavedMenuPlanner planner;
    private final SavedShoppingReconciler shopping;
    private final ObjectMapper json;

    public SavedMealReplacementService(
            NamedParameterJdbcTemplate jdbc,
            PantryService pantry,
            SavedMenuPlanner planner,
            SavedShoppingReconciler shopping,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.pantry = pantry;
        this.planner = planner;
        this.shopping = shopping;
        this.json = json;
    }

    private long revision(UUID user) {
        return jdbc.queryForObject(
                "select revision from pantry_accounts where user_id=:u",
                Map.of("u", user),
                Long.class);
    }

    private List<Map<String, Object>> latest(UUID user, UUID entry) {
        return jdbc.queryForList(
                "select * from meal_entry_replacements where user_id=:u and entry_id=:e and"
                    + " undone_at is null order by applied_at desc,id limit 1",
                Map.of("u", user, "e", entry));
    }

    private boolean matches(UUID entry, Map<String, Object> h) {
        return jdbc.queryForObject(
                        "select count(*) from meal_plan_entries where id=:e and recipe_id=:r and"
                            + " portions=:p and recipe_snapshot=cast(:s as jsonb)",
                        Map.of(
                                "e",
                                entry,
                                "r",
                                h.get("after_recipe_id"),
                                "p",
                                h.get("after_portions"),
                                "s",
                                h.get("after_snapshot").toString()),
                        Integer.class)
                == 1;
    }

    public Status status(String email, UUID entry) {
        var user = pantry.lockOwner(email);
        var row = planner.entry(user, entry);
        var blocked = planner.blocked(user, row);
        var h = latest(user, entry);
        boolean undo = blocked == null && !h.isEmpty() && matches(entry, h.getFirst());
        return new Status(
                revision(user),
                blocked == null,
                blocked,
                undo ? (UUID) h.getFirst().get("id") : null,
                undo
                        ? json.readValue(
                                        h.getFirst().get("before_snapshot").toString(),
                                        RecipeSnapshot.class)
                                .name()
                        : null);
    }

    public Preview preview(String email, UUID entry) {
        var frame = planner.load(email, entry);
        return new Preview(
                frame.context().pantry().revision(),
                frame.options().from(),
                frame.options().to(),
                planner.choices(frame).stream().map(SavedMenuPlanner.Choice::alternative).toList());
    }

    public PantryModels.Action apply(String email, UUID key, UUID entry, Apply r) {
        return pantry.execute(
                email,
                key,
                "SAVED_MEAL_REPLACE:" + entry,
                r,
                r.expectedRevision(),
                w -> {
                    var frame = planner.load(email, entry);
                    var choice =
                            planner.choices(frame).stream()
                                    .filter(
                                            c ->
                                                    c.alternative().recipeId().equals(r.recipeId())
                                                            && c.alternative()
                                                                    .candidateKey()
                                                                    .equals(r.candidateKey())
                                                            && c.alternative()
                                                                            .portions()
                                                                            .compareTo(r.portions())
                                                                    == 0
                                                            && c.alternative()
                                                                    .comparisonKey()
                                                                    .equals(r.comparisonKey()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    PantryException.conflict(
                                                            "REPLACEMENT_COMPARISON_CHANGED",
                                                            "Меню, цены или ограничения изменились."
                                                                + " Обновите варианты перед"
                                                                + " подтверждением"));
                    var p = new HashMap<String, Object>();
                    p.put("id", w.operation());
                    p.put("u", w.user());
                    p.put("e", entry);
                    p.put("oldRecipe", frame.entry().get("recipe_id"));
                    p.put("oldSnapshot", frame.entry().get("snapshot"));
                    p.put("oldPortions", frame.entry().get("portions"));
                    p.put("newRecipe", choice.selection().recipe().recipeId());
                    p.put("newSnapshot", json.writeValueAsString(choice.selection().recipe()));
                    p.put("newPortions", choice.selection().portions());
                    p.put("beforeBasket", json.writeValueAsString(choice.before().shopping()));
                    p.put("afterBasket", json.writeValueAsString(choice.after().shopping()));
                    jdbc.update(
                            "insert into"
                                + " meal_entry_replacements(id,user_id,entry_id,operation_id,before_recipe_id,before_snapshot,before_portions,after_recipe_id,after_snapshot,after_portions,before_basket,after_basket,applied_at)"
                                + " values(:id,:u,:e,:id,:oldRecipe,cast(:oldSnapshot as"
                                + " jsonb),:oldPortions,:newRecipe,cast(:newSnapshot as"
                                + " jsonb),:newPortions,cast(:beforeBasket as"
                                + " jsonb),cast(:afterBasket as jsonb),clock_timestamp())",
                            p);
                    em.flush();
                    jdbc.update(
                            "update meal_plan_entries set"
                                + " recipe_id=:newRecipe,recipe_snapshot=cast(:newSnapshot as"
                                + " jsonb),portions=:newPortions where id=:e",
                            p);
                    em.refresh(
                            em.getReference(
                                    dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry.class,
                                    entry));
                    changed(w.user(), frame.entry());
                    return List.of();
                });
    }

    private void changed(UUID user, Map<String, Object> entry) {
        jdbc.update(
                "update meal_plans set updated_at=clock_timestamp() where id=:p and user_id=:u",
                Map.of("p", entry.get("meal_plan_id"), "u", user));
        shopping.overlapping(user, planner.date(entry));
    }

    public PantryModels.Action undo(
            String email, UUID key, UUID entry, UUID change, PantryModels.Command r) {
        return pantry.execute(
                email,
                key,
                "SAVED_MEAL_UNDO",
                List.of(entry, change, r),
                r.expectedRevision(),
                w -> {
                    var frame = planner.load(email, entry);
                    var h = latest(w.user(), entry);
                    if (h.isEmpty()
                            || !change.equals(h.getFirst().get("id"))
                            || !matches(entry, h.getFirst()))
                        throw PantryException.conflict(
                                "SAVED_MEAL_UNDO_STALE",
                                "После этой замены блюдо уже изменилось. Обновите меню");
                    var old = h.getFirst();
                    planner.validateUndo(
                            frame,
                            json.readValue(
                                    old.get("before_snapshot").toString(), RecipeSnapshot.class));
                    em.flush();
                    jdbc.update(
                            "update meal_plan_entries set recipe_id=:r,recipe_snapshot=cast(:s as"
                                + " jsonb),portions=:p where id=:e",
                            Map.of(
                                    "e",
                                    entry,
                                    "r",
                                    old.get("before_recipe_id"),
                                    "s",
                                    old.get("before_snapshot").toString(),
                                    "p",
                                    old.get("before_portions")));
                    jdbc.update(
                            "update meal_entry_replacements set"
                                + " undone_at=clock_timestamp(),undo_operation_id=:op where id=:id"
                                + " and user_id=:u",
                            Map.of("id", change, "u", w.user(), "op", w.operation()));
                    em.refresh(
                            em.getReference(
                                    dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry.class,
                                    entry));
                    changed(w.user(), frame.entry());
                    return List.of();
                });
    }
}
