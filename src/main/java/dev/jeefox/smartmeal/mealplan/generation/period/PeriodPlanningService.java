package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** CPU work happens outside database transactions. Only versioned persistence is transactional. */
@Service
public class PeriodPlanningService {
    @org.springframework.beans.factory.annotation.Autowired
    private dev.jeefox.smartmeal.mealplan.balanced.BalancedMealPlanner balancedPlanner;

    private final PlanningContextLoader contexts;
    private final PeriodMenuSelector selector;
    private final PeriodMenuEvaluator evaluator;
    private final GenerationStore store;

    public PeriodPlanningService(
            PlanningContextLoader contexts,
            PeriodMenuSelector selector,
            PeriodMenuEvaluator evaluator,
            GenerationStore store) {
        this.contexts = contexts;
        this.selector = selector;
        this.evaluator = evaluator;
        this.store = store;
    }

    public View create(String email, Options options) {
        return store.create(
                email,
                options.balancedMeals()
                        ? balancedPlanner.select(contexts.load(email, options), options, List.of())
                        : selector.select(contexts.load(email, options), options, List.of()));
    }

    public View get(String email, UUID id) {
        return store.get(email, id);
    }

    private PlanningContext fresh(String email, View view) {
        if (!view.expiresAt().isAfter(java.time.Instant.now()))
            throw new PlanningException(
                    org.springframework.http.HttpStatus.GONE,
                    "GENERATION_EXPIRED",
                    "Срок предпросмотра истёк. Подберите меню ещё раз.");
        PlanningContext context = contexts.load(email, view.result().options());
        if (!context.fingerprint().equals(view.result().contextFingerprint()))
            throw PlanningException.conflict(
                    "PLANNING_CONTEXT_CHANGED",
                    "Изменились профиль, ограничения или рецепты. Создайте новый подбор.");
        return context;
    }

    public View refresh(String email, UUID id, long revision) {
        View old = store.get(email, id);
        PlanningContext context = fresh(email, old);
        return store.replace(
                email,
                id,
                revision,
                evaluator.evaluate(
                        context,
                        old.result().options(),
                        old.result().entries(),
                        old.result().searchStatus()));
    }

    public View regenerate(String email, UUID id, long revision) {
        View old = store.get(email, id);
        PlanningContext context = fresh(email, old);
        Options options = old.result().options().withSeed(old.result().options().seed() + 1);
        return store.replace(
                email,
                id,
                revision,
                options.balancedMeals()
                        ? balancedPlanner.select(context, options, old.result().entries())
                        : selector.select(
                                context,
                                options,
                                old.result().entries().stream()
                                        .filter(Selection::locked)
                                        .toList()));
    }

    public View lock(String email, UUID id, String entryId, Lock request) {
        View old = store.get(email, id);
        PlanningContext context = fresh(email, old);
        Selection entry = find(old, entryId);
        var changed =
                old.result().entries().stream()
                        .map(
                                e ->
                                        e.id().equals(entryId)
                                                ? new Selection(
                                                        e.id(),
                                                        e.date(),
                                                        e.mealType(),
                                                        e.recipe(),
                                                        e.recipeFingerprint(),
                                                        e.portions(),
                                                        request.locked(),
                                                        e.coverImageUrl(),
                                                        e.explanations())
                                                : e)
                        .toList();
        return store.replace(
                email,
                id,
                request.expectedRevision(),
                evaluator.evaluate(context, old.result().options(), changed, "FOUND"));
    }

    private void replaceable(View view, Selection entry) {
        if (!view.savedPlanIds().isEmpty())
            throw PlanningException.conflict(
                    "GENERATION_ALREADY_SAVED",
                    "Это меню уже сохранено. Приготовленные блюда не заменяются через"
                            + " предпросмотр.");
        if (entry.locked()) throw PlanningException.conflict("Сначала снимите закрепление блюда");
    }

    private static BigDecimal deficit(BigDecimal actual, BigDecimal target) {
        return target.subtract(actual).max(BigDecimal.ZERO);
    }

    public static boolean acceptableNutrition(
            Document baseline, Document changed, java.time.LocalDate date) {
        var before =
                baseline.days().stream()
                        .filter(d -> d.date().equals(date))
                        .findFirst()
                        .orElseThrow();
        var after =
                changed.days().stream()
                        .filter(d -> d.date().equals(date))
                        .findFirst()
                        .orElseThrow();
        for (var person : after.people()) {
            var old =
                    before.people().stream()
                            .filter(p -> p.participantId().equals(person.participantId()))
                            .findFirst()
                            .orElseThrow();
            if (person.proteinTargetG() != null
                    && deficit(person.nutrition().protein(), person.proteinTargetG())
                                    .compareTo(
                                            deficit(
                                                    old.nutrition().protein(),
                                                    person.proteinTargetG()))
                            > 0) return false;
            if (person.fiberTargetG() != null
                    && Boolean.TRUE.equals(old.nutrition().fiberComplete())
                    && (!Boolean.TRUE.equals(person.nutrition().fiberComplete())
                            || deficit(person.nutrition().knownFiber(), person.fiberTargetG())
                                            .compareTo(
                                                    deficit(
                                                            old.nutrition().knownFiber(),
                                                            person.fiberTargetG()))
                                    > 0)) return false;
        }
        if (after.withinTargetTolerance()) return true;
        if (before.withinTargetTolerance()) return false;
        // An incomplete menu may be improved, but never at the expense of another participant.
        for (var person : after.people()) {
            var old =
                    before.people().stream()
                            .filter(p -> p.participantId().equals(person.participantId()))
                            .findFirst()
                            .orElseThrow();
            if (person.nutrition()
                            .calories()
                            .subtract(BigDecimal.valueOf(person.dailyCalorieTarget()))
                            .abs()
                            .compareTo(
                                    old.nutrition()
                                            .calories()
                                            .subtract(BigDecimal.valueOf(old.dailyCalorieTarget()))
                                            .abs())
                    > 0) return false;
        }
        return after.calorieDeviation().abs().compareTo(before.calorieDeviation().abs()) <= 0;
    }

    public View replace(String email, UUID id, String entryId, Replacement request) {
        View old = store.get(email, id);
        if (old.revision() != request.expectedRevision())
            throw PlanningException.conflict(
                    "GENERATION_REVISION_CONFLICT",
                    "Предпросмотр изменился. Обновите состояние меню перед новой заменой.");
        Selection entry = find(old, entryId);
        replaceable(old, entry);
        PlanningContext context = fresh(email, old);
        var baseline =
                evaluator.evaluate(
                        context,
                        old.result().options(),
                        old.result().entries(),
                        old.result().searchStatus());
        Selection next;
        if (old.result().options().balancedMeals()) {
            next =
                    balancedPlanner
                            .alternatives(
                                    context,
                                    old.result().options(),
                                    old.result().entries(),
                                    entryId,
                                    request.componentId())
                            .stream()
                            .filter(
                                    e ->
                                            e.recipeFingerprint().equals(request.candidateKey())
                                                    && e.recipe()
                                                            .recipeId()
                                                            .equals(request.recipeId()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            PlanningException.conflict(
                                                    "Вариант замены устарел. Обновите варианты."));
        } else {
            next =
                    evaluator.selection(
                            context.candidate(request.recipeId()),
                            entry.date(),
                            entry.mealType(),
                            request.portions(),
                            false);
        }
        if (ReplacementComparisons.identity(next.recipe())
                .equals(ReplacementComparisons.identity(entry.recipe())))
            throw PlanningException.conflict("Выберите другое блюдо");
        var selected = new ArrayList<>(old.result().entries());
        selected.removeIf(e -> e.id().equals(entryId));
        selected.add(next);
        var changed = evaluator.evaluate(context, old.result().options(), selected, "FOUND");
        if (!GroceryPolicy.fits(old.result().options(), changed.shopping())
                || !acceptableNutrition(baseline, changed, entry.date()))
            throw PlanningException.conflict(
                    "Замена нарушает ограничения меню. Обновите варианты.");
        var comparison =
                ReplacementComparisons.compare(old, context, entry, next, baseline, changed);
        if (request.comparisonKey() != null
                && !request.comparisonKey().equals(comparison.comparisonKey()))
            throw PlanningException.conflict(
                    "REPLACEMENT_COMPARISON_CHANGED",
                    "Цены или запасы изменились. Обновите варианты и проверьте покупки перед"
                            + " подтверждением.");
        return store.replaceQuoted(
                email, id, request.expectedRevision(), changed, context.pantry().revision());
    }

    public List<Alternative> alternatives(String email, UUID id, String entryId) {
        return alternatives(email, id, entryId, null);
    }

    public List<Alternative> alternatives(
            String email, UUID id, String entryId, String componentId) {
        View old = store.get(email, id);
        Selection entry = find(old, entryId);
        replaceable(old, entry);
        PlanningContext context = fresh(email, old);
        var baseline =
                evaluator.evaluate(
                        context,
                        old.result().options(),
                        old.result().entries(),
                        old.result().searchStatus());
        var rest = old.result().entries().stream().filter(e -> !e.id().equals(entryId)).toList();
        var candidates = new ArrayList<Selection>();
        if (old.result().options().balancedMeals())
            candidates.addAll(
                    balancedPlanner.alternatives(
                            context,
                            old.result().options(),
                            old.result().entries(),
                            entryId,
                            componentId));
        else
            for (var candidate : context.candidates().getOrDefault(entry.mealType(), List.of())) {
                if (candidate.snapshot().recipeId().equals(entry.recipe().recipeId())
                        || !evaluator.canAdd(rest, candidate, entry.date(), old.result().options()))
                    continue;
                for (var portions : candidate.portions())
                    candidates.add(
                            evaluator.selection(
                                    candidate, entry.date(), entry.mealType(), portions, false));
            }
        var result = new ArrayList<Alternative>();
        for (var next : candidates) {
            if (ReplacementComparisons.identity(next.recipe())
                    .equals(ReplacementComparisons.identity(entry.recipe()))) continue;
            var changedEntries = new ArrayList<>(rest);
            changedEntries.add(next);
            var changed =
                    evaluator.evaluate(context, old.result().options(), changedEntries, "FOUND");
            if (!GroceryPolicy.fits(old.result().options(), changed.shopping())
                    || !acceptableNutrition(baseline, changed, entry.date())) continue;
            result.add(
                    ReplacementComparisons.compare(old, context, entry, next, baseline, changed));
        }
        return ReplacementComparisons.shortlist(result);
    }

    public View lockComponent(
            String email, UUID id, String entryId, String componentId, Lock request) {
        var old = store.get(email, id);
        var context = fresh(email, old);
        var entry = find(old, entryId);
        if (entry.locked()) throw PlanningException.conflict("Сначала открепите приём пищи");
        var cs = entry.recipe().components();
        if (cs.stream().noneMatch(c -> c.id().equals(componentId)))
            throw PlanningException.conflict("Компонент не найден");
        var changed =
                cs.stream()
                        .map(
                                c ->
                                        c.id().equals(componentId)
                                                ? new dev.jeefox.smartmeal.mealplan.balanced
                                                        .BalancedModels.Component(
                                                        c.id(),
                                                        c.role(),
                                                        c.recipe(),
                                                        c.profile(),
                                                        c.amounts(),
                                                        request.locked())
                                                : c)
                        .toList();
        var replacement =
                dev.jeefox.smartmeal.mealplan.balanced.CompositeMeals.selection(
                        changed, entry.date(), entry.mealType(), false, entry.coverImageUrl());
        var entries =
                old.result().entries().stream()
                        .map(e -> e.id().equals(entryId) ? replacement : e)
                        .toList();
        return store.replace(
                email,
                id,
                request.expectedRevision(),
                evaluator.evaluate(context, old.result().options(), entries, "FOUND"));
    }

    private Selection find(View view, String entryId) {
        return view.result().entries().stream()
                .filter(e -> e.id().equals(entryId))
                .findFirst()
                .orElseThrow(
                        () ->
                                new PlanningException(
                                        org.springframework.http.HttpStatus.NOT_FOUND,
                                        "ENTRY_NOT_FOUND",
                                        "Блюдо предпросмотра не найдено"));
    }
}
