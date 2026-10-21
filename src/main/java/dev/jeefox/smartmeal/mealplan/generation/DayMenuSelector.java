package dev.jeefox.smartmeal.mealplan.generation;

import dev.jeefox.smartmeal.exception.MenuGenerationUnavailableException;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component
public class DayMenuSelector {
    public record Candidate(UUID recipeId, String name, int preparationTimeMin, int servings,
                            NutritionValues total, BigDecimal historyPenalty, LocalDate lastPlannedDate) {
    }
    public record Choice(MealType mealType, Candidate recipe, BigDecimal portions) {
    }
    private record State(List<Choice> choices, BigDecimal calories, BigDecimal localScore, String key) {
    }
    private static final List<MealType> MEALS = List.of(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER);
    private static final List<BigDecimal> SHARES = List.of(new BigDecimal("0.25"), new BigDecimal("0.40"), new BigDecimal("0.35"));
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int BEAM_WIDTH = 100;

    public record PurchaseCost(boolean complete, BigDecimal knownCost) {
    }

    public List<Choice> select(int target, Map<MealType, List<Candidate>> candidates) {
        return select(target, candidates, null, null);
    }

    public List<Choice> select(int target, Map<MealType, List<Candidate>> candidates,
                              BigDecimal budget, Function<List<Choice>, PurchaseCost> pricing) {
        BigDecimal targetBd = BigDecimal.valueOf(target);
        List<State> beam = List.of(new State(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, ""));
        BigDecimal plannedShare = BigDecimal.ZERO;
        for (int mealIndex = 0; mealIndex < MEALS.size(); mealIndex++) {
            MealType meal = MEALS.get(mealIndex);
            plannedShare = plannedShare.add(SHARES.get(mealIndex));
            BigDecimal slotTarget = targetBd.multiply(SHARES.get(mealIndex));
            BigDecimal partialTarget = targetBd.multiply(plannedShare);
            Comparator<Candidate> candidateOrder = Comparator.comparing((Candidate c) -> bestSlotScore(c, slotTarget, targetBd))
                    .thenComparing(Candidate::recipeId);
            List<Candidate> available = candidates.getOrDefault(meal, List.of()).stream()
                    .filter(c -> c.total().calories().signum() > 0).toList();
            List<Candidate> shortlist = available.stream().sorted(candidateOrder).limit(40).toList();
            if (pricing != null) {
                // Keep affordable alternatives even when nutrition/history alone would drop them.
                var combined = new LinkedHashSet<>(shortlist);
                Map<Candidate, PurchaseCost> singleCosts = new HashMap<>();
                available.forEach(c -> singleCosts.put(c, pricing.apply(List.of(new Choice(meal, c, BigDecimal.ONE)))));
                combined.addAll(available.stream().sorted(Comparator
                        .comparing((Candidate c) -> !singleCosts.get(c).complete())
                        .thenComparing(c -> singleCosts.get(c).knownCost())
                        .thenComparing(candidateOrder)).limit(40).toList());
                shortlist = List.copyOf(combined);
            }
            List<State> expanded = new ArrayList<>();
            for (State previous : beam) {
                for (Candidate candidate : shortlist) {
                    if (previous.choices().stream().anyMatch(c -> c.recipe().recipeId().equals(candidate.recipeId()))) continue;
                    for (int quarterPortions = 2; quarterPortions <= 8; quarterPortions++) {
                        BigDecimal portions = BigDecimal.valueOf(quarterPortions).divide(BigDecimal.valueOf(4));
                        BigDecimal calories = candidate.total().calories().multiply(portions)
                                .divide(BigDecimal.valueOf(candidate.servings()), MC);
                        BigDecimal slotError = calories.subtract(slotTarget).abs().divide(targetBd, MC);
                        BigDecimal portionPenalty = portions.subtract(BigDecimal.ONE).abs().multiply(new BigDecimal("0.02"));
                        BigDecimal localScore = previous.localScore().add(slotError).add(portionPenalty).add(candidate.historyPenalty());
                        List<Choice> choices = new ArrayList<>(previous.choices());
                        choices.add(new Choice(meal, candidate, portions));
                        expanded.add(new State(List.copyOf(choices), previous.calories().add(calories), localScore,
                                previous.key() + "/" + candidate.recipeId() + ":" + portions));
                    }
                }
            }
            if (expanded.isEmpty()) {
                throw new MenuGenerationUnavailableException("Недостаточно разных подходящих рецептов для " + meal);
            }
            Comparator<State> order = Comparator.comparing((State s) -> s.calories().subtract(partialTarget).abs()
                            .divide(targetBd, MC).multiply(BigDecimal.TEN).add(s.localScore()))
                    .thenComparing(State::key);
            if (pricing == null) {
                beam = expanded.stream().sorted(order).limit(BEAM_WIDTH).toList();
            } else {
                Map<State, PurchaseCost> costs = new HashMap<>();
                expanded.forEach(state -> costs.put(state, pricing.apply(state.choices())));
                boolean finalMeal = mealIndex == MEALS.size() - 1;
                Comparator<State> budgetOrder = Comparator
                        .comparingInt((State state) -> budgetRank(state, costs.get(state), budget, targetBd, finalMeal))
                        .thenComparing(state -> costs.get(state).knownCost().subtract(budget).max(BigDecimal.ZERO))
                        .thenComparing(order);
                if (finalMeal) {
                    beam = expanded.stream().sorted(budgetOrder).limit(BEAM_WIDTH).toList();
                } else {
                    // Reserve half the beam for cheap partial menus; later meals may share their packages.
                    var kept = new LinkedHashSet<>(expanded.stream().sorted(budgetOrder).limit(BEAM_WIDTH / 2).toList());
                    kept.addAll(expanded.stream().sorted(Comparator
                            .comparing((State state) -> !costs.get(state).complete())
                            .thenComparing(state -> costs.get(state).knownCost()).thenComparing(order))
                            .limit(BEAM_WIDTH / 2).toList());
                    beam = List.copyOf(kept);
                }
            }
        }
        return beam.getFirst().choices();
    }

    private int budgetRank(State state, PurchaseCost cost, BigDecimal budget, BigDecimal target, boolean finalMeal) {
        boolean caloriesFit = !finalMeal || state.calories().subtract(target).abs()
                .compareTo(target.multiply(new BigDecimal("0.10"))) <= 0;
        if (caloriesFit && cost.complete() && cost.knownCost().compareTo(budget) <= 0) return 0;
        if (caloriesFit && cost.complete()) return 1;
        if (caloriesFit) return 2;
        if (cost.complete()) return 3;
        return 4;
    }

    private BigDecimal bestSlotScore(Candidate candidate, BigDecimal slotTarget, BigDecimal target) {
        BigDecimal best = null;
        for (int quarters = 2; quarters <= 8; quarters++) {
            BigDecimal portions = BigDecimal.valueOf(quarters).divide(BigDecimal.valueOf(4));
            BigDecimal calories = candidate.total().calories().multiply(portions)
                    .divide(BigDecimal.valueOf(candidate.servings()), MC);
            BigDecimal score = calories.subtract(slotTarget).abs().divide(target, MC).add(candidate.historyPenalty());
            if (best == null || score.compareTo(best) < 0) best = score;
        }
        return best;
    }

    public NutritionValues nutrition(List<Choice> choices) {
        BigInteger denominator = BigInteger.ONE;
        for (Choice choice : choices) {
            BigInteger servings = BigInteger.valueOf(choice.recipe().servings());
            denominator = denominator.divide(denominator.gcd(servings)).multiply(servings);
        }
        return new NutritionValues(sum(choices, denominator, NutritionValues::calories),
                sum(choices, denominator, NutritionValues::protein), sum(choices, denominator, NutritionValues::fat),
                sum(choices, denominator, NutritionValues::carbs));
    }

    private BigDecimal sum(List<Choice> choices, BigInteger denominator, Function<NutritionValues, BigDecimal> field) {
        BigDecimal numerator = BigDecimal.ZERO;
        for (Choice choice : choices) {
            BigInteger multiplier = denominator.divide(BigInteger.valueOf(choice.recipe().servings()));
            numerator = numerator.add(field.apply(choice.recipe().total()).multiply(choice.portions()).multiply(new BigDecimal(multiplier)));
        }
        return numerator.divide(new BigDecimal(denominator), 2, RoundingMode.HALF_UP);
    }
}
