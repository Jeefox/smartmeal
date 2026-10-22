package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** CPU work happens outside database transactions. Only versioned persistence is transactional. */
@Service
public class PeriodPlanningService {
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
                email, selector.select(contexts.load(email, options), options, List.of()));
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
                selector.select(
                        context,
                        options,
                        old.result().entries().stream().filter(Selection::locked).toList()));
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

    public View replace(String email, UUID id, String entryId, Replacement request) {
        View old = store.get(email, id);
        PlanningContext context = fresh(email, old);
        Selection entry = find(old, entryId);
        if (entry.locked()) throw PlanningException.conflict("Сначала снимите закрепление блюда");
        var selected = new ArrayList<>(old.result().entries());
        selected.removeIf(e -> e.id().equals(entryId));
        selected.add(
                evaluator.selection(
                        context.candidate(request.recipeId()),
                        entry.date(),
                        entry.mealType(),
                        request.portions(),
                        false));
        return store.replace(
                email,
                id,
                request.expectedRevision(),
                evaluator.evaluate(context, old.result().options(), selected, "FOUND"));
    }

    public List<Alternative> alternatives(String email, UUID id, String entryId) {
        View old = store.get(email, id);
        PlanningContext context = fresh(email, old);
        Selection entry = find(old, entryId);
        if (entry.locked()) throw PlanningException.conflict("Сначала снимите закрепление блюда");
        List<Selection> rest =
                old.result().entries().stream().filter(e -> !e.id().equals(entryId)).toList();
        List<Alternative> alternatives = new ArrayList<>();
        for (Candidate candidate : context.candidates().get(entry.mealType())) {
            if (candidate.snapshot().recipeId().equals(entry.recipe().recipeId())
                    || !evaluator.canAdd(rest, candidate, entry.date(), old.result().options()))
                continue;
            for (BigDecimal portions : candidate.portions()) {
                var selected = new ArrayList<>(rest);
                selected.add(
                        evaluator.selection(
                                candidate, entry.date(), entry.mealType(), portions, false));
                var evaluated =
                        evaluator.evaluate(context, old.result().options(), selected, "FOUND");
                var day =
                        evaluated.days().stream()
                                .filter(d -> d.date().equals(entry.date()))
                                .findFirst()
                                .orElseThrow();
                if (!day.withinTargetTolerance()) continue;
                BigDecimal cost = evaluated.shopping().estimatedTotal();
                alternatives.add(
                        new Alternative(
                                candidate.snapshot().recipeId(),
                                candidate.snapshot().name(),
                                portions,
                                candidate.snapshot().preparationTimeMin(),
                                day.nutrition(),
                                cost,
                                cost != null && old.result().shopping().estimatedTotal() != null
                                        ? cost.subtract(old.result().shopping().estimatedTotal())
                                        : null,
                                evaluated.shopping().budgetStatus(),
                                evaluated.shopping().priceQuality()));
            }
        }
        return alternatives.stream()
                .sorted(
                        Comparator.comparing(
                                        (Alternative a) -> a.budgetStatus() == BudgetStatus.OVER)
                                .thenComparing(a -> a.newPurchaseCost() == null)
                                .thenComparing(
                                        Alternative::newPurchaseCost,
                                        Comparator.nullsLast(BigDecimal::compareTo))
                                .thenComparing(Alternative::recipeId)
                                .thenComparing(Alternative::portions))
                .limit(5)
                .toList();
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
