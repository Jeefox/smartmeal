package dev.jeefox.smartmeal.mealplan.service;

import dev.jeefox.smartmeal.exception.*;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanResponse;
import dev.jeefox.smartmeal.mealplan.dto.SaveMealPlanPreviewRequest;
import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanEntryRepository;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanRepository;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshotFactory;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshotFingerprint;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.recipe.repository.RecipeIngredientRepository;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import dev.jeefox.smartmeal.user.repository.UserProfileRepository;
import dev.jeefox.smartmeal.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SaveMealPlanPreviewService {
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final MealPlanRepository plans;
    private final MealPlanEntryRepository entries;
    private final RecipeRepository recipes;
    private final RecipeIngredientRepository ingredients;
    private final MealPlanService planService;

    public SaveMealPlanPreviewService(UserRepository users, UserProfileRepository profiles, MealPlanRepository plans,
                                      MealPlanEntryRepository entries, RecipeRepository recipes,
                                      RecipeIngredientRepository ingredients, MealPlanService planService) {
        this.users = users;
        this.profiles = profiles;
        this.plans = plans;
        this.entries = entries;
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.planService = planService;
    }

    @Transactional
    public MealPlanResponse save(String email, SaveMealPlanPreviewRequest request) {
        validateSelection(request);
        User owner = users.findByEmailForUpdate(email).orElseThrow(() -> new UsernameNotFoundException(email));
        var date = request.options().planDate();
        if (plans.findByUser_IdAndPlanDate(owner.getId(), date).isPresent()) {
            throw new MealPlanAlreadyExistsException(date);
        }
        Integer target = profiles.findById(owner.getId()).map(UserProfile::getDailyCalorieTarget)
                .filter(value -> value > 0).orElseThrow(DailyCalorieTargetNotSetException::new);
        if (!target.equals(request.dailyCalorieTarget())) throw new StaleMealPlanPreviewException();
        Map<MealType, Integer> limits = new EnumMap<>(MealType.class);
        limits.put(MealType.BREAKFAST, request.options().breakfastMaxPreparationTimeMin());
        limits.put(MealType.LUNCH, request.options().lunchMaxPreparationTimeMin());
        limits.put(MealType.DINNER, request.options().dinnerMaxPreparationTimeMin());
        List<UUID> ids = request.entries().stream().map(SaveMealPlanPreviewRequest.Entry::recipeId).toList();
        Map<UUID, Recipe> selected = recipes.findAllById(ids).stream().collect(Collectors.toMap(Recipe::getId, r -> r));
        Map<UUID, List<RecipeIngredient>> contents = ingredients.findByRecipe_IdIn(ids).stream()
                .collect(Collectors.groupingBy(item -> item.getRecipe().getId()));
        Map<UUID, RecipeSnapshot> snapshots = new HashMap<>();
        for (var selection : request.entries()) {
            Recipe recipe = selected.get(selection.recipeId());
            if (recipe == null) throw new RecipeNotFoundException(selection.recipeId());
            if (!recipes.isPreviewEligible(owner.getId(), selection.mealType().name(), limits.get(selection.mealType()), recipe.getId())) {
                throw new StaleMealPlanPreviewException();
            }
            RecipeSnapshot snapshot = RecipeSnapshotFactory.create(recipe, contents.getOrDefault(recipe.getId(), List.of()));
            if (snapshot.nutritionTotal().calories().signum() <= 0
                    || !RecipeSnapshotFingerprint.calculate(snapshot).equals(selection.recipeFingerprint())) {
                throw new StaleMealPlanPreviewException();
            }
            snapshots.put(recipe.getId(), snapshot);
        }
        MealPlan plan;
        try {
            plan = plans.saveAndFlush(new MealPlan(owner, date));
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && "meal_plans_user_date_unique".equals(constraint.getConstraintName())) {
                    throw new MealPlanAlreadyExistsException(date);
                }
            }
            throw exception;
        }
        List<MealPlanEntry> savedEntries = new ArrayList<>();
        request.entries().stream().sorted(Comparator.comparing(SaveMealPlanPreviewRequest.Entry::mealType)).forEach(selection ->
                savedEntries.add(new MealPlanEntry(plan, selected.get(selection.recipeId()), selection.mealType(),
                        selection.portions(), 1, snapshots.get(selection.recipeId()))));
        entries.saveAll(savedEntries);
        return planService.findByDate(email, date);
    }

    private void validateSelection(SaveMealPlanPreviewRequest request) {
        Set<MealType> types = request.entries().stream().map(SaveMealPlanPreviewRequest.Entry::mealType).collect(Collectors.toSet());
        Set<UUID> recipeIds = new HashSet<>();
        if (!types.equals(Set.of(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER))) {
            throw new InvalidMealPlanSelectionException("Нужны завтрак, обед и ужин — по одному блюду каждого типа");
        }
        for (var selection : request.entries()) {
            if (!recipeIds.add(selection.recipeId())) {
                throw new InvalidMealPlanSelectionException("Рецепты внутри дня не должны повторяться");
            }
            if (selection.portions().remainder(new BigDecimal("0.25")).signum() != 0) {
                throw new InvalidMealPlanSelectionException("Количество порций должно быть кратно 0.25");
            }
        }
    }
}
