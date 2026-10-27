package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;
import static dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.*;

import dev.jeefox.smartmeal.household.*;
import dev.jeefox.smartmeal.household.HouseholdModels.*;
import dev.jeefox.smartmeal.mealplan.balanced.*;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.generation.period.*;
import dev.jeefox.smartmeal.mealplan.snapshot.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.*;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class SavedMenuPlanner {
    private final NamedParameterJdbcTemplate jdbc;
    private final PantryService pantry;
    private final CookedCoverage cooked;
    private final PlanningContextLoader contexts;
    private final PeriodMenuEvaluator evaluator;
    private final PurchaseBasketCalculator baskets;
    private final HouseholdSnapshots households;
    private final ObjectMapper json;

    public SavedMenuPlanner(
            NamedParameterJdbcTemplate jdbc,
            PantryService pantry,
            CookedCoverage cooked,
            PlanningContextLoader contexts,
            PeriodMenuEvaluator evaluator,
            PurchaseBasketCalculator baskets,
            HouseholdSnapshots households,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.pantry = pantry;
        this.cooked = cooked;
        this.contexts = contexts;
        this.evaluator = evaluator;
        this.baskets = baskets;
        this.households = households;
        this.json = json;
    }

    public record Frame(
            UUID owner,
            Map<String, Object> entry,
            Options options,
            PlanningContext context,
            List<Map<String, Object>> rows,
            List<Selection> entries,
            Selection selected) {}

    public Map<String, Object> entry(UUID user, UUID id) {
        var rows =
                jdbc.queryForList(
                        "select e.*,e.recipe_snapshot::text snapshot,m.user_id,m.status"
                            + " plan_status,m.plan_date,m.daily_calorie_target_snapshot,coalesce(s.state,'OPEN')"
                            + " inventory_state from meal_plan_entries e join meal_plans m on"
                            + " m.id=e.meal_plan_id left join meal_entry_inventory_states s on"
                            + " s.meal_plan_entry_id=e.id where e.id=:e and m.user_id=:u",
                        Map.of("e", id, "u", user));
        if (rows.isEmpty()) throw PantryException.missing();
        return rows.getFirst();
    }

    public LocalDate date(Map<String, Object> row) {
        return ((java.sql.Date) row.get("plan_date")).toLocalDate();
    }

    public String blocked(UUID user, Map<String, Object> row) {
        if ("CANCELLED".equals(row.get("plan_status"))) return "Меню дня отменено";
        if (date(row).isBefore(pantry.today()))
            return "Прошедшие дни доступны только для просмотра";
        if (!"OPEN".equals(row.get("inventory_state")))
            return "Приготовленное, съеденное или пропущенное блюдо заменить нельзя";
        if ("SNACK".equals(row.get("meal_type")))
            return "Автозамена пока доступна для завтрака, обеда и ужина";
        if (jdbc.queryForObject(
                        "select count(*) from cooking_allocations where entry_id=:e and status in"
                                + " ('ACTIVE','CONSUMED')",
                        Map.of("e", row.get("id")),
                        Integer.class)
                > 0)
            return "Блюдо связано с общей партией. Сначала отмените план партии или проверьте"
                    + " готовые остатки";
        return null;
    }

    public void requireOpen(UUID user, Map<String, Object> row) {
        var reason = blocked(user, row);
        if (reason != null) throw PantryException.conflict("SAVED_MEAL_PROTECTED", reason);
    }

    public Frame load(String email, UUID id) {
        UUID user = pantry.lockOwner(email);
        var row = entry(user, id);
        requireOpen(user, row);
        var docs =
                jdbc.queryForList(
                        "select document::text from meal_plan_generations where user_id=:u and"
                                + " saved_plan_ids @> jsonb_build_array(cast(:p as text)) order by"
                                + " created_at desc,id limit 1",
                        Map.of("u", user, "p", row.get("meal_plan_id")),
                        String.class);
        var saved = households.people((UUID) row.get("meal_plan_id"));
        var memberIds = saved.stream().filter(p -> !p.accountOwner()).map(Participant::id).toList();
        var snapshot = json.readValue(row.get("snapshot").toString(), RecipeSnapshot.class);
        Options original =
                docs.isEmpty() ? null : json.readValue(docs.getFirst(), Document.class).options();
        var from = original == null ? date(row).with(java.time.DayOfWeek.MONDAY) : original.from();
        var to = original == null ? from.plusDays(6) : original.to();
        var chains =
                original == null
                        ? jdbc.queryForList(
                                "select id from retail_chains order by id limit 6",
                                Map.of(),
                                Long.class)
                        : original.allowedChainIds();
        var options =
                new Options(
                        from,
                        to,
                        null,
                        chains,
                        original == null ? "Тула" : original.city(),
                        original == null ? null : original.purchaseMode(),
                        original == null ? null : original.maxPreparationTimeByMeal(),
                        original == null ? 14 : original.maxRecipeOccurrences(),
                        original == null ? 0 : original.minRepeatGapDays(),
                        original == null ? true : original.allowEstimatedPrices(),
                        original == null ? null : original.minSplitSavingRub(),
                        original == null ? 0L : original.seed(),
                        true,
                        memberIds,
                        original == null
                                ? !snapshot.components().isEmpty()
                                : original.balancedMeals(),
                        original == null ? null : original.maxShoppingProducts());
        var base = contexts.load(email, options);
        if (saved.isEmpty())
            saved =
                    List.of(
                            new Participant(
                                    user,
                                    "Вы",
                                    row.get("daily_calorie_target_snapshot") == null
                                            ? base.target()
                                            : ((Number) row.get("daily_calorie_target_snapshot"))
                                                    .intValue(),
                                    true));
        var rows =
                jdbc.queryForList(
                        "select"
                            + " e.id,e.recipe_id,e.meal_type,e.portions,e.position,e.recipe_snapshot::text"
                            + " snapshot,m.plan_date,m.status plan_status,m.updated_at::text"
                            + " plan_updated,coalesce(s.state,'OPEN') inventory_state from"
                            + " meal_plan_entries e join meal_plans m on m.id=e.meal_plan_id left"
                            + " join meal_entry_inventory_states s on s.meal_plan_entry_id=e.id"
                            + " where m.user_id=:u and m.plan_date between :f and :t order by"
                            + " m.plan_date,e.meal_type,e.position,e.id",
                        Map.of("u", user, "f", from, "t", to));
        var own = new HashSet<UUID>();
        rows.forEach(r -> own.add((UUID) r.get("id")));
        var context =
                new PlanningContext(
                        user,
                        saved.stream().mapToInt(Participant::dailyCalorieTarget).sum(),
                        base.fingerprint() + ":" + digest(rows.toString() + saved),
                        base.asOf(),
                        base.candidates(),
                        base.offers(),
                        base.issues(),
                        pantry.snapshot(email, own),
                        saved,
                        base.balanced());
        var selections =
                rows.stream()
                        .filter(r -> !"CANCELLED".equals(r.get("plan_status")))
                        .map(
                                r -> {
                                    var recipe =
                                            json.readValue(
                                                    r.get("snapshot").toString(),
                                                    RecipeSnapshot.class);
                                    return new Selection(
                                            r.get("id").toString(),
                                            date(r),
                                            MealType.valueOf(r.get("meal_type").toString()),
                                            recipe,
                                            RecipeSnapshotFingerprint.calculate(recipe),
                                            (BigDecimal) r.get("portions"),
                                            false,
                                            null,
                                            List.of());
                                })
                        .toList();
        return new Frame(
                user,
                row,
                options,
                context,
                rows,
                selections,
                selections.stream()
                        .filter(e -> e.id().equals(id.toString()))
                        .findFirst()
                        .orElseThrow());
    }

    private String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            text.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Document document(Frame f, List<Selection> selections) {
        var state = new HashMap<String, Map<String, Object>>();
        f.rows().forEach(r -> state.put(r.get("id").toString(), r));
        var raw = new ArrayList<Demand>();
        for (var e : selections)
            if (!e.date().isBefore(pantry.today())
                    && "OPEN".equals(state.get(e.id()).get("inventory_state")))
                raw.addAll(
                        calculate(
                                e.id(),
                                e.date(),
                                e.mealType().ordinal(),
                                e.recipe(),
                                e.portions()));
        var adjusted =
                cooked
                        .forPeriod(
                                f.owner(),
                                raw,
                                pantry.today(),
                                f.options().from(),
                                f.options().to())
                        .stream()
                        .filter(
                                d ->
                                        !d.date().isBefore(f.options().from())
                                                && !d.date().isAfter(f.options().to()))
                        .toList();
        var own =
                adjusted.stream()
                        .map(d -> UUID.fromString(d.entryId()))
                        .collect(java.util.stream.Collectors.toSet());
        var stock = pantry.stock(f.owner(), own);
        var normal =
                f.rows().stream()
                        .map(r -> r.get("id").toString())
                        .collect(java.util.stream.Collectors.toSet());
        var priority =
                f.rows().stream()
                        .filter(r -> "CONFIRMED".equals(r.get("plan_status")))
                        .map(r -> r.get("id").toString())
                        .collect(java.util.stream.Collectors.toSet());
        adjusted.stream()
                .map(Demand::entryId)
                .filter(id -> !normal.contains(id))
                .forEach(priority::add);
        var coverage =
                PantryAllocationCalculator.allocatePrioritized(adjusted, stock, priority)
                        .coveredG();
        var basket =
                baskets.calculateCovered(
                        selections, f.context().offers(), f.options(), adjusted, coverage);
        var dayEntries =
                selections.stream()
                        .filter(e -> e.date().equals(f.selected().date()))
                        .map(
                                e ->
                                        new Selection(
                                                e.id(),
                                                e.date(),
                                                e.mealType(),
                                                e.recipe(),
                                                e.recipeFingerprint(),
                                                e.portions(),
                                                false,
                                                e.coverImageUrl(),
                                                e.explanations(),
                                                HouseholdPortions.allocate(
                                                        e.portions(),
                                                        e.recipe(),
                                                        f.context().participants())))
                        .toList();
        var nutrition = evaluator.nutrition(dayEntries);
        var deviation = nutrition.calories().subtract(BigDecimal.valueOf(f.context().target()));
        var people =
                HouseholdPortions.totals(
                        f.context().participants(),
                        dayEntries.stream().flatMap(e -> e.personPortions().stream()).toList());
        boolean fit =
                people.stream()
                        .allMatch(
                                p ->
                                        p.nutrition()
                                                        .calories()
                                                        .subtract(
                                                                BigDecimal.valueOf(
                                                                        p.dailyCalorieTarget()))
                                                        .abs()
                                                        .compareTo(
                                                                BigDecimal.valueOf(
                                                                                p
                                                                                        .dailyCalorieTarget())
                                                                        .multiply(
                                                                                new BigDecimal(
                                                                                        "0.10")))
                                                <= 0);
        var day = new Day(f.selected().date(), nutrition, deviation, fit, dayEntries, people);
        return new Document(
                f.options(),
                f.context().target(),
                f.context().fingerprint(),
                f.context().asOf(),
                selections,
                List.of(day),
                basket,
                List.of(),
                "FOUND",
                "FOUND",
                "saved-replacement-v1",
                f.context().pantry().revision(),
                pantry.today(),
                f.context().participants());
    }

    public record Choice(
            Alternative alternative, Selection selection, Document before, Document after) {}

    public List<Choice> choices(Frame f) {
        var original = f.selected();
        var rest = f.entries().stream().filter(e -> !e.id().equals(original.id())).toList();
        var candidates = new ArrayList<Selection>();
        if (f.options().balancedMeals()) {
            var required = original.recipe().components().stream().filter(c -> c.locked()).toList();
            // choices already enforce eligibility, cooking time, personal masses and locked parts.
            for (var e :
                    CompositeMeals.choices(
                            f.context(),
                            f.options(),
                            original.date(),
                            original.mealType(),
                            required))
                candidates.add(
                        new Selection(
                                original.id(),
                                e.date(),
                                e.mealType(),
                                e.recipe(),
                                e.recipeFingerprint(),
                                e.portions(),
                                false,
                                e.coverImageUrl(),
                                e.explanations()));
        } else
            for (var candidate :
                    f.context().candidates().getOrDefault(original.mealType(), List.of()))
                for (var portions : candidate.portions()) {
                    var e =
                            evaluator.selection(
                                    candidate,
                                    original.date(),
                                    original.mealType(),
                                    portions,
                                    false);
                    candidates.add(
                            new Selection(
                                    original.id(),
                                    e.date(),
                                    e.mealType(),
                                    e.recipe(),
                                    e.recipeFingerprint(),
                                    e.portions(),
                                    false,
                                    e.coverImageUrl(),
                                    e.explanations()));
                }
        var baseline = document(f, f.entries());
        var result = new ArrayList<Choice>();
        var view =
                new View(
                        UUID.fromString(original.id()),
                        f.context().pantry().revision(),
                        Instant.MAX,
                        baseline,
                        List.of());
        for (var next : candidates) {
            if (ReplacementComparisons.identity(original.recipe())
                    .equals(ReplacementComparisons.identity(next.recipe()))) continue;
            if (!evaluator.canAdd(
                    rest,
                    f.context().candidate(next.recipe().recipeId()),
                    next.date(),
                    f.options())) continue;
            var changed = new ArrayList<>(rest);
            changed.add(next);
            var after = document(f, changed);
            if (!GroceryPolicy.fits(f.options(), after.shopping())
                    || !PeriodPlanningService.acceptableNutrition(baseline, after, next.date()))
                continue;
            result.add(
                    new Choice(
                            ReplacementComparisons.compare(
                                    view, f.context(), original, next, baseline, after),
                            next,
                            baseline,
                            after));
        }
        var shortKeys =
                ReplacementComparisons.shortlist(result.stream().map(Choice::alternative).toList())
                        .stream()
                        .map(Alternative::comparisonKey)
                        .collect(java.util.stream.Collectors.toSet());
        return result.stream()
                .filter(c -> shortKeys.contains(c.alternative().comparisonKey()))
                .toList();
    }

    public void validateUndo(Frame f, RecipeSnapshot old) {
        var sources =
                old.components().isEmpty()
                        ? List.of(old)
                        : old.components().stream().map(c -> c.recipe()).toList();
        for (var source : sources)
            if (f.context().candidates().getOrDefault(f.selected().mealType(), List.of()).stream()
                    .noneMatch(
                            c ->
                                    c.snapshot().recipeId().equals(source.recipeId())
                                            && RecipeSnapshotFingerprint.calculate(c.snapshot())
                                                    .equals(
                                                            RecipeSnapshotFingerprint.calculate(
                                                                    source))))
                throw PantryException.conflict(
                        "UNDO_RECIPE_CHANGED",
                        "Предыдущий рецепт изменился или больше не соответствует ограничениям."
                                + " Выберите актуальную замену");
    }
}
