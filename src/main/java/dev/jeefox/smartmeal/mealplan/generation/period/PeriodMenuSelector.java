package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** Bounded deterministic search over the whole period, including shared grocery packages. */
@Component
public class PeriodMenuSelector {
    private final PurchaseBasketCalculator baskets;
    private final PeriodMenuEvaluator evaluator;
    private final int beamWidth;
    private final int operationLimit;

    private record State(List<Selection> entries, Basket basket, double score, String key) {}

    public PeriodMenuSelector(
            PurchaseBasketCalculator baskets,
            PeriodMenuEvaluator evaluator,
            @Value("${smartmeal.planning.beam-width:48}") int beamWidth,
            @Value("${smartmeal.planning.operation-limit:100000}") int operationLimit) {
        this.baskets = baskets;
        this.evaluator = evaluator;
        this.beamWidth = beamWidth;
        this.operationLimit = operationLimit;
    }

    public Document select(PlanningContext context, Options options, List<Selection> locks) {
        Map<UUID, Candidate> byId =
                context.allCandidates().stream()
                        .collect(
                                Collectors.toMap(
                                        c -> c.snapshot().recipeId(), c -> c, (a, b) -> a));
        var checkedLocks = evaluator.evaluate(context, options, locks, "FOUND");
        Map<String, Selection> fixed =
                checkedLocks.entries().stream().collect(Collectors.toMap(Selection::id, e -> e));
        for (var meal : PeriodMenuEvaluator.MEALS) {
            if (context.candidates().getOrDefault(meal, List.of()).isEmpty())
                return evaluator.evaluate(context, options, List.of(), "NO_FEASIBLE_FOUND");
        }
        // Find a complete fallback without pricing every search prefix.
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        List<Selection> complete =
                findComplete(
                        context,
                        options,
                        fixed,
                        byId,
                        Math.min(deadline, System.nanoTime() + Duration.ofSeconds(2).toNanos()));
        List<State> beam =
                List.of(
                        new State(
                                List.of(),
                                baskets.calculate(
                                        List.of(), context.offers(), options, context.pantry()),
                                0,
                                ""));
        int operations = 0;
        boolean limited = false;
        int dayCount = (int) ChronoUnit.DAYS.between(options.from(), options.to()) + 1;
        for (int day = 0; day < dayCount; day++)
            for (int mealIndex = 0; mealIndex < 3; mealIndex++) {
                LocalDate date = options.from().plusDays(day);
                MealType meal = PeriodMenuEvaluator.MEALS.get(mealIndex);
                Selection locked = fixed.get(date + "-" + meal);
                List<Candidate> pool = context.candidates().getOrDefault(meal, List.of());
                int currentMeal = mealIndex;
                Comparator<Candidate> nutritional =
                        Comparator.comparingDouble(
                                        (Candidate c) ->
                                                slotError(c, context.target(), currentMeal))
                                .thenComparingLong(
                                        c -> tie(c.snapshot().recipeId(), options.seed()));
                var shortlist =
                        new LinkedHashSet<>(pool.stream().sorted(nutritional).limit(16).toList());
                Map<Candidate, Basket> singleCosts = new HashMap<>();
                for (var c : pool) {
                    if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
                        return evaluator.evaluate(
                                context,
                                options,
                                complete == null ? beam.getFirst().entries() : complete,
                                "LIMIT_REACHED");
                    singleCosts.put(
                            c,
                            baskets.calculate(
                                    List.of(
                                            evaluator.selection(
                                                    c, date, meal, c.portions().getFirst(), false)),
                                    context.offers(),
                                    options,
                                    context.pantry()));
                }
                shortlist.addAll(
                        pool.stream()
                                .sorted(
                                        Comparator.comparingInt(
                                                        (Candidate c) ->
                                                                singleCosts
                                                                        .get(c)
                                                                        .unknownProducts())
                                                .thenComparing(
                                                        c -> singleCosts.get(c).knownPurchaseCost())
                                                .thenComparing(nutritional))
                                .limit(12)
                                .toList());
                if (locked != null) {
                    shortlist.clear();
                    shortlist.add(context.candidate(locked.recipe().recipeId()));
                }
                List<State> expanded = new ArrayList<>();
                outer:
                for (State state : beam)
                    for (Candidate candidate : shortlist) {
                        if (!evaluator.canAdd(state.entries(), candidate, date, options)) continue;
                        // Reserve future fixed recipes before admitting an unpinned occurrence.
                        if (locked == null) {
                            var future =
                                    fixed.values().stream()
                                            .filter(
                                                    e ->
                                                            !e.date().isBefore(date)
                                                                    && !e.id().equals(
                                                                                    date + "-"
                                                                                            + meal))
                                            .filter(
                                                    e ->
                                                            state.entries().stream()
                                                                    .noneMatch(
                                                                            existing ->
                                                                                    existing.id()
                                                                                            .equals(
                                                                                                    e
                                                                                                            .id())))
                                            .toList();
                            if (!evaluator.canAdd(future, candidate, date, options)) continue;
                            long count =
                                    state.entries().stream()
                                                    .filter(
                                                            e ->
                                                                    e.recipe()
                                                                            .recipeId()
                                                                            .equals(
                                                                                    candidate
                                                                                            .snapshot()
                                                                                            .recipeId()))
                                                    .count()
                                            + future.stream()
                                                    .filter(
                                                            e ->
                                                                    e.recipe()
                                                                            .recipeId()
                                                                            .equals(
                                                                                    candidate
                                                                                            .snapshot()
                                                                                            .recipeId()))
                                                    .count();
                            if (count >= options.maxRecipeOccurrences()) continue;
                        }
                        List<BigDecimal> portions =
                                locked == null ? candidate.portions() : List.of(locked.portions());
                        for (BigDecimal portion : portions) {
                            if (operations++ >= operationLimit
                                    || System.nanoTime() > deadline
                                    || Thread.currentThread().isInterrupted()) {
                                limited = true;
                                break outer;
                            }
                            List<Selection> selected = new ArrayList<>(state.entries());
                            selected.add(
                                    evaluator.selection(
                                            candidate, date, meal, portion, locked != null));
                            Basket cost =
                                    baskets.calculate(
                                            selected, context.offers(), options, context.pantry());
                            if (!GroceryPolicy.fits(options, cost)) continue;
                            double score = score(selected, context, date, currentMeal, byId);
                            String key =
                                    state.key()
                                            + "/"
                                            + tie(candidate.snapshot().recipeId(), options.seed())
                                            + ":"
                                            + portion;
                            expanded.add(new State(List.copyOf(selected), cost, score, key));
                        }
                    }
                if (expanded.isEmpty())
                    return evaluator.evaluate(
                            context,
                            options,
                            complete == null ? beam.getFirst().entries() : complete,
                            limited || complete != null ? "LIMIT_REACHED" : "NO_FEASIBLE_FOUND");
                Comparator<State> normal =
                        Comparator.comparingDouble(State::score).thenComparing(State::key);
                Comparator<State> price =
                        Comparator.comparingInt((State s) -> s.basket().unknownProducts())
                                .thenComparing(
                                        s ->
                                                options.budgetRub() == null
                                                        ? BigDecimal.ZERO
                                                        : s.basket()
                                                                .knownPurchaseCost()
                                                                .subtract(options.budgetRub())
                                                                .max(BigDecimal.ZERO))
                                .thenComparing(normal);
                LinkedHashSet<State> kept =
                        new LinkedHashSet<>(
                                expanded.stream()
                                        .sorted(normal)
                                        .limit(Math.max(1, beamWidth / 2))
                                        .toList());
                kept.addAll(
                        expanded.stream()
                                .sorted(price.thenComparing(s -> s.basket().knownPurchaseCost()))
                                .limit(Math.max(1, beamWidth / 4))
                                .toList());
                kept.addAll(
                        expanded.stream()
                                .sorted(
                                        Comparator.comparingInt(
                                                        (State s) -> s.basket().unknownProducts())
                                                .thenComparing(s -> s.basket().knownPurchaseCost())
                                                .thenComparing(normal))
                                .limit(Math.max(1, beamWidth / 4))
                                .toList());
                beam = List.copyOf(kept);
                if (limited && (day != dayCount - 1 || mealIndex != 2))
                    return evaluator.evaluate(
                            context,
                            options,
                            complete == null ? beam.getFirst().entries() : complete,
                            "LIMIT_REACHED");
            }
        Comparator<State> finish =
                Comparator.comparingInt((State s) -> finalRank(s, context, options))
                        .thenComparing(
                                s ->
                                        options.budgetRub() == null
                                                ? BigDecimal.ZERO
                                                : s.basket()
                                                        .knownPurchaseCost()
                                                        .subtract(options.budgetRub())
                                                        .max(BigDecimal.ZERO))
                        .thenComparingDouble(State::score)
                        .thenComparing(s -> s.basket().knownPurchaseCost())
                        .thenComparingDouble(s -> expiryTieBreak(s.entries(), context, options))
                        .thenComparing(State::key);
        List<State> finalists = new ArrayList<>(beam);
        if (complete != null)
            finalists.add(
                    new State(
                            complete,
                            baskets.calculate(
                                    complete, context.offers(), options, context.pantry()),
                            score(complete, context, options.to(), 2, byId),
                            "complete-fallback"));
        State best = finalists.stream().min(finish).orElseThrow();
        // Local single-slot improvement uses the exact same weekly basket, eligibility and repeat
        // rules.
        int tries = 0;
        improve:
        for (int index = 0; index < best.entries().size() && tries < 400; index++) {
            Selection old = best.entries().get(index);
            if (old.locked()) continue;
            for (Candidate replacement :
                    context.candidates().get(old.mealType()).stream().limit(24).toList()) {
                List<Selection> rest = new ArrayList<>(best.entries());
                rest.remove(index);
                if (!evaluator.canAdd(rest, replacement, old.date(), options)) continue;
                for (BigDecimal portion : replacement.portions()) {
                    if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
                        limited = true;
                        break improve;
                    }
                    if (tries++ >= 400) break;
                    List<Selection> selected = new ArrayList<>(best.entries());
                    selected.set(
                            index,
                            evaluator.selection(
                                    replacement, old.date(), old.mealType(), portion, false));
                    Basket cost =
                            baskets.calculate(
                                    selected, context.offers(), options, context.pantry());
                    if (!GroceryPolicy.fits(options, cost)) continue;
                    State attempt =
                            new State(
                                    List.copyOf(selected),
                                    cost,
                                    score(selected, context, options.to(), 2, byId),
                                    "local:" + replacement.snapshot().recipeId());
                    if (finish.compare(attempt, best) < 0) best = attempt;
                }
            }
        }
        return evaluator.evaluate(
                context, options, best.entries(), limited ? "LIMIT_REACHED" : "FOUND");
    }

    /** Bounded feasibility search: repeats and pins are hard rules, prices are optimized later. */
    private List<Selection> findComplete(
            PlanningContext context,
            Options options,
            Map<String, Selection> fixed,
            Map<UUID, Candidate> byId,
            long deadline) {
        List<Selection> selected = new ArrayList<>();
        int[] remaining = {5000};
        return completeSlot(context, options, fixed, byId, selected, 0, remaining, deadline)
                ? List.copyOf(selected)
                : null;
    }

    private boolean completeSlot(
            PlanningContext context,
            Options options,
            Map<String, Selection> fixed,
            Map<UUID, Candidate> byId,
            List<Selection> selected,
            int slot,
            int[] remaining,
            long deadline) {
        int slots = ((int) ChronoUnit.DAYS.between(options.from(), options.to()) + 1) * 3;
        if (slot == slots) return true;
        if (remaining[0] <= 0
                || System.nanoTime() > deadline
                || Thread.currentThread().isInterrupted()) return false;
        LocalDate date = options.from().plusDays(slot / 3);
        MealType meal = PeriodMenuEvaluator.MEALS.get(slot % 3);
        Selection locked = fixed.get(date + "-" + meal);
        var future =
                fixed.values().stream()
                        .filter(e -> !e.date().isBefore(date) && !e.id().equals(date + "-" + meal))
                        .filter(
                                e ->
                                        selected.stream()
                                                .noneMatch(
                                                        existing -> existing.id().equals(e.id())))
                        .toList();
        List<Candidate> pool =
                locked == null
                        ? context.candidates().getOrDefault(meal, List.of())
                        : List.of(context.candidate(locked.recipe().recipeId()));
        List<Selection> choices = new ArrayList<>();
        for (Candidate candidate : pool) {
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
                return false;
            List<Selection> reserved = new ArrayList<>(selected);
            reserved.addAll(future);
            if (!evaluator.canAdd(reserved, candidate, date, options)) continue;
            Selection best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (BigDecimal portion :
                    locked == null ? candidate.portions() : List.of(locked.portions())) {
                Selection choice =
                        evaluator.selection(candidate, date, meal, portion, locked != null);
                selected.add(choice);
                double value =
                        GroceryPolicy.fits(context, options, selected)
                                ? score(selected, context, date, slot % 3, byId)
                                : Double.POSITIVE_INFINITY;
                if (options.maxShoppingProducts() != null && Double.isFinite(value))
                    value += GroceryPolicy.count(context, options, selected) * .12;
                selected.removeLast();
                if (value < bestScore) {
                    best = choice;
                    bestScore = value;
                }
            }
            if (best != null) choices.add(best);
        }
        choices.sort(
                Comparator.comparingDouble(
                                (Selection choice) -> {
                                    List<Selection> attempt = new ArrayList<>(selected);
                                    attempt.add(choice);
                                    return score(attempt, context, date, slot % 3, byId);
                                })
                        .thenComparingLong(
                                choice -> tie(choice.recipe().recipeId(), options.seed())));
        for (Selection choice : choices) {
            if (remaining[0]-- <= 0
                    || System.nanoTime() > deadline
                    || Thread.currentThread().isInterrupted()) return false;
            selected.add(choice);
            if (completeSlot(
                    context, options, fixed, byId, selected, slot + 1, remaining, deadline))
                return true;
            selected.removeLast();
        }
        return false;
    }

    private double score(
            List<Selection> selected,
            PlanningContext context,
            LocalDate date,
            int mealIndex,
            Map<UUID, Candidate> byId) {
        double score = nutritionScore(selected, context.target(), date, mealIndex);
        for (var entry : selected) {
            Candidate c = byId.get(entry.recipe().recipeId());
            if (c.lastPlannedDate() != null)
                score +=
                        Math.max(0, 22 - ChronoUnit.DAYS.between(c.lastPlannedDate(), entry.date()))
                                * .002;
            score += entry.portions().subtract(BigDecimal.ONE).abs().doubleValue() * .005;
        }
        var families =
                selected.stream()
                        .map(e -> byId.get(e.recipe().recipeId()).family())
                        .filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        return score + families.values().stream().mapToLong(n -> Math.max(0, n - 2)).sum() * .01;
    }

    private double expiryTieBreak(
            List<Selection> entries, PlanningContext context, Options options) {
        if (!options.usePantry() || context.pantry().lots().isEmpty()) return 0;
        var demands =
                new ArrayList<dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.Demand>();
        for (var e : entries)
            demands.addAll(
                    dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.calculate(
                            e.id(), e.date(), e.mealType().ordinal(), e.recipe(), e.portions()));
        var lots = context.pantry().lots().stream().collect(Collectors.toMap(l -> l.id(), l -> l));
        double used = 0;
        for (var a :
                dev.jeefox.smartmeal.pantry.PantryAllocationCalculator.allocate(
                                demands, context.pantry())
                        .allocations()) {
            var lot = lots.get(a.lotId());
            if (lot.expiresOn() != null
                    && !lot.expiresOn().isAfter(options.to())
                    && lot.quantityG().signum() > 0)
                used +=
                        a.quantityG().doubleValue()
                                / lot.quantityG().doubleValue()
                                / (1
                                        + Math.max(
                                                0,
                                                ChronoUnit.DAYS.between(
                                                        context.pantry().today(),
                                                        lot.expiresOn())));
        }
        return -used;
    }

    private int finalRank(State state, PlanningContext context, Options options) {
        boolean fit =
                state.entries().stream()
                        .collect(Collectors.groupingBy(Selection::date))
                        .values()
                        .stream()
                        .allMatch(
                                day ->
                                        evaluator
                                                        .nutrition(day)
                                                        .calories()
                                                        .subtract(
                                                                BigDecimal.valueOf(
                                                                        context.target()))
                                                        .abs()
                                                        .compareTo(
                                                                BigDecimal.valueOf(context.target())
                                                                        .multiply(
                                                                                new BigDecimal(
                                                                                        "0.10")))
                                                <= 0);
        int rank = fit ? 0 : 4;
        if (state.basket().unknownProducts() > 0) return rank + 2;
        if (options.budgetRub() != null
                && state.basket().knownPurchaseCost().compareTo(options.budgetRub()) > 0)
            return rank + 1;
        return rank;
    }

    private double slotError(Candidate c, int target, int meal) {
        BigDecimal goal =
                BigDecimal.valueOf(target)
                        .multiply(
                                new BigDecimal[] {
                                            new BigDecimal(".25"),
                                            new BigDecimal(".40"),
                                            new BigDecimal(".35")
                                        }
                                        [meal]);
        return c.portions().stream()
                        .map(
                                p ->
                                        c.snapshot()
                                                .nutritionTotal()
                                                .calories()
                                                .multiply(p)
                                                .divide(
                                                        BigDecimal.valueOf(c.snapshot().servings()),
                                                        MathContext.DECIMAL128)
                                                .subtract(goal)
                                                .abs())
                        .min(BigDecimal::compareTo)
                        .orElse(BigDecimal.TEN)
                        .doubleValue()
                / target;
    }

    private double nutritionScore(
            List<Selection> entries, int target, LocalDate current, int mealIndex) {
        double score = 0;
        for (var day :
                entries.stream().collect(Collectors.groupingBy(Selection::date)).entrySet()) {
            BigDecimal share =
                    day.getKey().equals(current)
                            ? new BigDecimal[] {
                                        new BigDecimal(".25"), new BigDecimal(".65"), BigDecimal.ONE
                                    }
                                    [mealIndex]
                            : BigDecimal.ONE;
            BigDecimal goal = BigDecimal.valueOf(target).multiply(share);
            score +=
                    evaluator
                            .nutrition(day.getValue())
                            .calories()
                            .subtract(goal)
                            .abs()
                            .divide(BigDecimal.valueOf(target), MathContext.DECIMAL128)
                            .doubleValue();
        }
        return score;
    }

    private long tie(UUID id, long seed) {
        return id.getMostSignificantBits()
                ^ Long.rotateLeft(id.getLeastSignificantBits(), 13)
                ^ Long.rotateLeft(seed * 0x9e3779b97f4a7c15L, 17);
    }
}
