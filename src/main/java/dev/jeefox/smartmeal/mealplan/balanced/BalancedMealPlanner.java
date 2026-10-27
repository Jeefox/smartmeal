package dev.jeefox.smartmeal.mealplan.balanced;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.generation.period.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.util.*;

@Component
public class BalancedMealPlanner {
    private final PeriodMenuEvaluator evaluator;
    private final PurchaseBasketCalculator baskets;

    public BalancedMealPlanner(PeriodMenuEvaluator evaluator, PurchaseBasketCalculator baskets) {
        this.evaluator = evaluator;
        this.baskets = baskets;
    }

    private record State(List<Selection> entries, double score) {}

    public Document select(PlanningContext c, Options o, List<Selection> previous) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        var cache = new HashMap<MealType, List<Selection>>();
        var slots = new ArrayList<List<Selection>>();
        for (LocalDate d = o.from(); !d.isAfter(o.to()); d = d.plusDays(1))
            for (var meal : PeriodMenuEvaluator.MEALS) {
                LocalDate date = d;
                var old =
                        previous.stream()
                                .filter(e -> e.date().equals(date) && e.mealType() == meal)
                                .findFirst();
                if (old.isPresent() && old.get().locked()) {
                    slots.add(List.of(old.get()));
                    continue;
                }
                var required =
                        old.stream()
                                .flatMap(e -> e.recipe().components().stream())
                                .filter(x -> x.locked())
                                .toList();
                var templates =
                        required.isEmpty()
                                ? cache.computeIfAbsent(
                                        meal, m -> CompositeMeals.choices(c, o, date, m, List.of()))
                                : CompositeMeals.choices(c, o, date, meal, required);
                slots.add(
                        templates.stream()
                                .map(
                                        t ->
                                                new Selection(
                                                        date + "-" + meal,
                                                        date,
                                                        meal,
                                                        t.recipe(),
                                                        t.recipeFingerprint(),
                                                        BigDecimal.ONE,
                                                        false,
                                                        t.coverImageUrl(),
                                                        t.explanations()))
                                .toList());
            }
        var fallback =
                findComplete(
                        c,
                        o,
                        slots,
                        0,
                        new ArrayList<>(),
                        Math.min(deadline, System.nanoTime() + Duration.ofSeconds(2).toNanos()));
        List<State> beam = List.of(new State(List.of(), 0));
        boolean limited = false;
        for (var choices : slots) {
            if (choices.isEmpty()) continue;
            List<State> expanded = new ArrayList<>();
            for (var state : beam) {
                for (var next : choices) {
                    if (!admissible(c, o, state.entries(), next, previous)) continue;
                    var selected = new ArrayList<>(state.entries());
                    selected.add(next);
                    var basket = baskets.calculate(selected, c.offers(), o, c.pantry());
                    if (!GroceryPolicy.fits(o, basket)) continue;
                    expanded.add(
                            new State(
                                    List.copyOf(selected),
                                    score(c, o, selected, basket, next.date(), next.mealType())));
                    if (System.nanoTime() > deadline) {
                        limited = true;
                        break;
                    }
                }
                if (limited) break;
            }
            if (!expanded.isEmpty()) beam = expanded.stream().sorted(order(c)).limit(8).toList();
            if (limited) break;
        }
        var finalStates = new ArrayList<>(beam);
        if (fallback != null) {
            var b = baskets.calculate(fallback, c.offers(), o, c.pantry());
            finalStates.add(new State(fallback, score(c, o, fallback, b, o.to(), MealType.DINNER)));
        }
        var best =
                finalStates.stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (State s) ->
                                                        s.entries().size() == slots.size() ? 0 : 1)
                                        .thenComparing(order(c)))
                        .findFirst()
                        .orElseThrow();
        var explained =
                best.entries().stream()
                        .map(
                                e -> {
                                    var reasons = new ArrayList<>(e.explanations());
                                    var main = c.candidate(e.recipe().recipeId());
                                    reasons.add(
                                            main.lastPlannedDate() == null
                                                    ? "Основное блюдо не встречалось в"
                                                          + " подтверждённых меню за предыдущие 21"
                                                          + " день"
                                                    : "Основное блюдо последний раз было в меню: "
                                                            + main.lastPlannedDate()
                                                            + "; история — предпочтение, а не"
                                                            + " запрет");
                                    if (o.maxShoppingProducts() != null)
                                        reasons.add(
                                                "Общие ингредиенты разных блюд учитываются одной"
                                                        + " покупкой; максимум "
                                                        + o.maxShoppingProducts()
                                                        + " разных продуктов на период");
                                    return new Selection(
                                            e.id(),
                                            e.date(),
                                            e.mealType(),
                                            e.recipe(),
                                            e.recipeFingerprint(),
                                            e.portions(),
                                            e.locked(),
                                            e.coverImageUrl(),
                                            List.copyOf(reasons));
                                })
                        .toList();
        return evaluator.evaluate(c, o, explained, limited ? "LIMIT_REACHED" : "FOUND");
    }

    private Comparator<State> order(PlanningContext c) {
        return Comparator.comparingInt((State s) -> completedDayViolations(c, s.entries()))
                .thenComparingDouble(State::score)
                .thenComparing(
                        s ->
                                s.entries().stream()
                                        .map(Selection::recipeFingerprint)
                                        .reduce("", String::concat));
    }

    private boolean admissible(
            PlanningContext c,
            Options o,
            List<Selection> entries,
            Selection next,
            List<Selection> previous) {
        var candidate = c.candidate(next.recipe().recipeId());
        if (!evaluator.canAdd(entries, candidate, next.date(), o)) return false;
        var groceries = new ArrayList<>(entries);
        groceries.add(next);
        if (!GroceryPolicy.fits(c, o, groceries)) return false;
        if (next.locked()) return true;
        var protectedEntries = new ArrayList<>(entries);
        previous.stream()
                .filter(Selection::locked)
                .filter(
                        e ->
                                !e.id().equals(next.id())
                                        && (e.date().isAfter(next.date())
                                                || e.date().equals(next.date())
                                                        && e.mealType().ordinal()
                                                                > next.mealType().ordinal()))
                .forEach(protectedEntries::add);
        var withNext = new ArrayList<>(protectedEntries);
        withNext.add(next);
        return evaluator.canAdd(protectedEntries, candidate, next.date(), o)
                && GroceryPolicy.fits(c, o, withNext);
    }

    private List<Selection> findComplete(
            PlanningContext c,
            Options o,
            List<List<Selection>> slots,
            int index,
            List<Selection> selected,
            long deadline) {
        if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) return null;
        if (index == slots.size()) return List.copyOf(selected);
        var future =
                slots.stream()
                        .filter(s -> s.size() == 1 && s.getFirst().locked())
                        .map(List::getFirst)
                        .toList();
        var ordered =
                slots.get(index).stream()
                        .sorted(
                                Comparator.comparingDouble(
                                                (Selection e) -> {
                                                    var trial = new ArrayList<>(selected);
                                                    trial.add(e);
                                                    return (o.maxShoppingProducts() == null
                                                                    ? 0
                                                                    : GroceryPolicy.count(
                                                                                    c, o, trial)
                                                                            * .12)
                                                            + historyPenalty(c, e);
                                                })
                                        .thenComparing(Selection::recipeFingerprint))
                        .toList();
        for (var e : ordered) {
            if (!admissible(c, o, selected, e, future)) continue;
            selected.add(e);
            var found = findComplete(c, o, slots, index + 1, selected, deadline);
            if (found != null) return found;
            selected.removeLast();
        }
        return null;
    }

    private int completedDayViolations(PlanningContext c, List<Selection> entries) {
        int bad = 0;
        for (var date : entries.stream().map(Selection::date).distinct().toList()) {
            var day = entries.stream().filter(e -> e.date().equals(date)).toList();
            if (day.size() != 3) continue;
            for (var p : c.participants()) {
                var n =
                        NutritionValues.sum(
                                day.stream()
                                        .map(e -> CompositeMeals.forPerson(e.recipe(), p.id()))
                                        .toList());
                if (n.calories()
                                .subtract(BigDecimal.valueOf(p.dailyCalorieTarget()))
                                .abs()
                                .compareTo(
                                        BigDecimal.valueOf(p.dailyCalorieTarget())
                                                .multiply(new BigDecimal(".10")))
                        > 0) bad++;
            }
        }
        return bad;
    }

    private double score(
            PlanningContext c,
            Options o,
            List<Selection> entries,
            Basket b,
            LocalDate date,
            MealType meal) {
        double error = 0;
        for (LocalDate d = o.from(); !d.isAfter(date); d = d.plusDays(1)) {
            LocalDate day = d;
            double share =
                    day.equals(date)
                            ? meal == MealType.BREAKFAST ? .25 : meal == MealType.LUNCH ? .65 : 1
                            : 1;
            var daily = entries.stream().filter(e -> e.date().equals(day)).toList();
            for (var p : c.participants()) {
                var n =
                        NutritionValues.sum(
                                daily.stream()
                                        .map(e -> CompositeMeals.forPerson(e.recipe(), p.id()))
                                        .toList());
                double calorieError =
                        Math.abs(n.calories().doubleValue() - p.dailyCalorieTarget() * share)
                                / p.dailyCalorieTarget();
                error += calorieError * 4;
                var t = c.balanced().targets().get(p.id());
                if (t != null) {
                    if (t.proteinTargetG() != null)
                        error +=
                                Math.max(
                                                0,
                                                t.proteinTargetG().doubleValue() * share
                                                        - n.protein().doubleValue())
                                        / Math.max(1, t.proteinTargetG().doubleValue());
                    if (t.fiberTargetG() != null && n.fiberComplete())
                        error +=
                                Math.max(
                                                0,
                                                t.fiberTargetG().doubleValue() * share
                                                        - n.knownFiber().doubleValue())
                                        / Math.max(1, t.fiberTargetG().doubleValue())
                                        * .5;
                }
            }
        }
        double budget = o.budgetRub() == null ? 10000 : o.budgetRub().doubleValue();
        error +=
                Math.max(0, b.knownPurchaseCost().doubleValue() - budget) / budget * 10
                        + b.knownPurchaseCost().doubleValue() / budget * .15
                        + b.unknownProducts() * .05;
        Map<UUID, Long> uses =
                entries.stream()
                        .flatMap(e -> e.recipe().components().stream())
                        .filter(x -> x.role() == BalancedModels.Role.SIDE)
                        .collect(
                                java.util.stream.Collectors.groupingBy(
                                        x -> x.recipe().recipeId(),
                                        java.util.stream.Collectors.counting()));
        error += uses.values().stream().mapToLong(x -> Math.max(0, x - 2)).sum() * .02;
        if (o.maxShoppingProducts() != null) error += GroceryPolicy.count(b) * .12;
        error += entries.stream().mapToDouble(e -> historyPenalty(c, e)).sum();
        return error;
    }

    static double historyPenalty(PlanningContext c, Selection entry) {
        double result = 0;
        for (var component : entry.recipe().components()) {
            var last = c.candidate(component.recipe().recipeId()).lastPlannedDate();
            if (last == null) continue;
            long days = java.time.temporal.ChronoUnit.DAYS.between(last, entry.date());
            // New dishes matter more than new sides: reusing buckwheat is desirable.
            double weight =
                    component.role() == BalancedModels.Role.SIDE
                                    || component.role() == BalancedModels.Role.VEGETABLE
                            ? .001
                            : .015;
            result += Math.max(0, 22 - days) * weight;
        }
        return result;
    }

    public List<Selection> alternatives(
            PlanningContext c,
            Options o,
            List<Selection> entries,
            String slot,
            String componentId) {
        var e = entries.stream().filter(x -> x.id().equals(slot)).findFirst().orElseThrow();
        var required =
                e.recipe().components().stream()
                        .filter(
                                x ->
                                        x.locked()
                                                || componentId != null
                                                        && !x.id().equals(componentId))
                        .toList();
        var rest = entries.stream().filter(x -> !x.id().equals(slot)).toList();
        return CompositeMeals.choices(c, o, e.date(), e.mealType(), required).stream()
                .filter(x -> !x.recipeFingerprint().equals(e.recipeFingerprint()))
                .filter(
                        x ->
                                evaluator.canAdd(
                                        rest, c.candidate(x.recipe().recipeId()), e.date(), o))
                .filter(
                        x -> {
                            var trial = new ArrayList<>(rest);
                            trial.add(x);
                            return GroceryPolicy.fits(c, o, trial);
                        })
                .sorted(Comparator.comparingDouble((Selection x) -> historyPenalty(c, x)))
                .toList();
    }
}
