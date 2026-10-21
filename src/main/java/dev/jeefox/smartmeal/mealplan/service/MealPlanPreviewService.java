package dev.jeefox.smartmeal.mealplan.service;

import dev.jeefox.smartmeal.exception.DailyCalorieTargetNotSetException;
import dev.jeefox.smartmeal.exception.MenuGenerationUnavailableException;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanPreviewResponse;
import dev.jeefox.smartmeal.mealplan.dto.PreviewMealPlanRequest;
import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.generation.DayMenuSelector;
import dev.jeefox.smartmeal.mealplan.generation.MenuShoppingList;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanEntryRepository;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanRepository;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshotFactory;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshotFingerprint;
import dev.jeefox.smartmeal.nutrition.NutritionCalculator;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.recipe.repository.RecipeIngredientRepository;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateResponse;
import dev.jeefox.smartmeal.shopping.service.ShoppingEstimateService;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import dev.jeefox.smartmeal.user.repository.UserProfileRepository;
import dev.jeefox.smartmeal.user.repository.UserRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MealPlanPreviewService {
    private static final int CATALOGUE_LIMIT = 500;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final RecipeRepository recipes;
    private final RecipeIngredientRepository ingredients;
    private final MealPlanRepository plans;
    private final MealPlanEntryRepository entries;
    private final DayMenuSelector selector;
    private final ShoppingEstimateService estimates;

    public MealPlanPreviewService(UserRepository users, UserProfileRepository profiles, RecipeRepository recipes,
                                  RecipeIngredientRepository ingredients, MealPlanRepository plans,
                                  MealPlanEntryRepository entries, DayMenuSelector selector,
                                  ShoppingEstimateService estimates) {
        this.users = users;
        this.profiles = profiles;
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.plans = plans;
        this.entries = entries;
        this.selector = selector;
        this.estimates = estimates;
    }

    @Transactional(readOnly = true)
    public MealPlanPreviewResponse preview(String email, PreviewMealPlanRequest request) {
        User owner = users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException(email));
        Integer target = profiles.findById(owner.getId()).map(UserProfile::getDailyCalorieTarget)
                .filter(value -> value > 0).orElseThrow(DailyCalorieTargetNotSetException::new);
        Map<MealType, Integer> limits = new LinkedHashMap<>();
        limits.put(MealType.BREAKFAST, request.breakfastMaxPreparationTimeMin());
        limits.put(MealType.LUNCH, request.lunchMaxPreparationTimeMin());
        limits.put(MealType.DINNER, request.dinnerMaxPreparationTimeMin());
        Map<MealType, List<Recipe>> eligible = new EnumMap<>(MealType.class);
        Map<UUID, Recipe> unique = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        for (var limit : limits.entrySet()) {
            List<Recipe> found = recipes.findPreviewCandidates(owner.getId(), limit.getKey().name(), limit.getValue(),
                    PageRequest.of(0, CATALOGUE_LIMIT + 1));
            if (found.size() > CATALOGUE_LIMIT && !warnings.contains("CATALOGUE_SEARCH_LIMIT")) warnings.add("CATALOGUE_SEARCH_LIMIT");
            List<Recipe> capped = found.stream().limit(CATALOGUE_LIMIT).toList();
            eligible.put(limit.getKey(), capped);
            capped.forEach(recipe -> unique.put(recipe.getId(), recipe));
            if (capped.isEmpty()) throw new MenuGenerationUnavailableException("Нет подходящих рецептов для " + limit.getKey());
        }
        Map<UUID, List<RecipeIngredient>> ingredientsByRecipe = ingredients.findByRecipe_IdIn(new ArrayList<>(unique.keySet()))
                .stream().collect(Collectors.groupingBy(item -> item.getRecipe().getId()));
        LocalDate date = request.planDate();
        LocalDate historyFrom = date.isBefore(LocalDate.MIN.plusDays(21)) ? LocalDate.MIN : date.minusDays(21);
        List<MealPlan> history = plans.findByUser_IdAndPlanDateBetweenOrderByPlanDateAsc(owner.getId(), historyFrom, date)
                .stream().filter(plan -> plan.getPlanDate().isBefore(date)).toList();
        Map<UUID, LocalDate> planDates = history.stream().collect(Collectors.toMap(MealPlan::getId, MealPlan::getPlanDate));
        Map<UUID, LocalDate> lastDates = new HashMap<>();
        Map<UUID, BigDecimal> penalties = new HashMap<>();
        if (!history.isEmpty()) {
            for (var entry : entries.findByMealPlan_IdIn(new ArrayList<>(planDates.keySet()))) {
                UUID recipeId = entry.getRecipeSnapshot().recipeId();
                LocalDate usedOn = planDates.get(entry.getMealPlan().getId());
                long age = ChronoUnit.DAYS.between(usedOn, date);
                BigDecimal penalty = BigDecimal.valueOf(22 - age).divide(BigDecimal.valueOf(21), MathContext.DECIMAL128)
                        .multiply(new BigDecimal("0.20"));
                penalties.merge(recipeId, penalty, BigDecimal::add);
                lastDates.merge(recipeId, usedOn, (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Map<MealType, List<DayMenuSelector.Candidate>> candidates = new EnumMap<>(MealType.class);
        eligible.forEach((meal, list) -> candidates.put(meal, list.stream().map(recipe -> new DayMenuSelector.Candidate(
                recipe.getId(), recipe.getName(), recipe.getPreparationTimeMin(), recipe.getServings(),
                NutritionCalculator.calculateTotals(ingredientsByRecipe.getOrDefault(recipe.getId(), List.of())),
                penalties.getOrDefault(recipe.getId(), BigDecimal.ZERO), lastDates.get(recipe.getId()))).toList()));
        Function<List<DayMenuSelector.Choice>, ShoppingEstimateResponse> pricing = null;
        if (request.budget() != null) {
            var productIds = ingredientsByRecipe.values().stream().flatMap(List::stream)
                    .map(item -> item.getProduct().getId()).distinct().toList();
            var prepared = estimates.prepare(request.storeId(), productIds);
            pricing = selected -> prepared.apply(MenuShoppingList
                    .calculate(date, selected, ingredientsByRecipe));
        }
        var menuPricing = pricing;
        List<DayMenuSelector.Choice> choices = selector.select(target, candidates, request.budget(),
                pricing == null ? null : selected -> {
                    var estimate = menuPricing.apply(selected);
                    return new DayMenuSelector.PurchaseCost(estimate.complete(), estimate.knownPurchaseCost());
                });
        MealPlanPreviewResponse.BudgetEstimate budgetEstimate = null;
        if (pricing != null) {
            var estimate = pricing.apply(choices);
            String status = !estimate.complete() ? "UNKNOWN" : estimate.knownPurchaseCost().compareTo(request.budget()) <= 0
                    ? "WITHIN_BUDGET" : "OVER_BUDGET";
            BigDecimal excess = estimate.knownPurchaseCost().subtract(request.budget()).max(BigDecimal.ZERO);
            budgetEstimate = new MealPlanPreviewResponse.BudgetEstimate(request.budget(), status, excess, estimate);
            if (!estimate.complete()) warnings.add("PRICES_INCOMPLETE");
            if (estimate.items().stream().anyMatch(dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateItemResponse::referencePrice)) {
                warnings.add("CATALOGUE_ESTIMATES_USED");
            }
            if (excess.signum() > 0) warnings.add("BUDGET_EXCEEDED");
            warnings.add("BUDGET_SEARCH_LIMITED");
        }
        var total = selector.nutrition(choices);
        BigDecimal deviation = total.calories().subtract(BigDecimal.valueOf(target));
        boolean withinTolerance = deviation.abs().compareTo(BigDecimal.valueOf(target).multiply(new BigDecimal("0.10"))) <= 0;
        if (!withinTolerance) warnings.add("TARGET_NOT_REACHED");
        if (choices.stream().anyMatch(choice -> choice.recipe().lastPlannedDate() != null)) warnings.add("RECENT_RECIPES_USED");
        var responseEntries = choices.stream().map(choice -> new MealPlanPreviewResponse.Entry(choice.mealType(),
                choice.recipe().recipeId(), choice.recipe().name(), choice.portions(), choice.recipe().preparationTimeMin(),
                selector.nutrition(List.of(choice)), choice.recipe().lastPlannedDate(),
                RecipeSnapshotFingerprint.calculate(RecipeSnapshotFactory.create(unique.get(choice.recipe().recipeId()),
                        ingredientsByRecipe.get(choice.recipe().recipeId()))))).toList();
        return new MealPlanPreviewResponse(date, target, total, deviation, withinTolerance, responseEntries, List.copyOf(warnings), budgetEstimate);
    }
}
