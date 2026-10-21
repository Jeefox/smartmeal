package dev.jeefox.smartmeal.mealplan.service;

import dev.jeefox.smartmeal.exception.*;
import dev.jeefox.smartmeal.mealplan.dto.AddMealPlanEntryRequest;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanEntryResponse;
import dev.jeefox.smartmeal.mealplan.dto.UpdateMealPlanEntryRequest;
import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanEntryRepository;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanRepository;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshotFactory;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.recipe.repository.RecipeIngredientRepository;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class MealPlanEntryService {
    private final MealPlanRepository plans;
    private final MealPlanEntryRepository entries;
    private final RecipeRepository recipes;
    private final RecipeIngredientRepository ingredients;

    public MealPlanEntryService(MealPlanRepository plans, MealPlanEntryRepository entries,
                                RecipeRepository recipes, RecipeIngredientRepository ingredients) {
        this.plans = plans;
        this.entries = entries;
        this.recipes = recipes;
        this.ingredients = ingredients;
    }

    @Transactional
    public MealPlanEntryResponse add(String email, UUID planId, AddMealPlanEntryRequest request) {
        MealPlan plan = requireEditablePlan(email, planId);
        Recipe recipe = recipes.findById(request.recipeId())
                .orElseThrow(() -> new RecipeNotFoundException(request.recipeId()));
        List<RecipeIngredient> recipeIngredients = ingredients.findByRecipe_IdOrderByPositionAsc(recipe.getId());
        RecipeSnapshot snapshot = RecipeSnapshotFactory.create(recipe, recipeIngredients);
        int position = entries.findFirstByMealPlan_IdAndMealTypeOrderByPositionDesc(planId, request.mealType())
                .map(entry -> Math.addExact(entry.getPosition(), 1)).orElse(1);
        MealPlanEntry saved = entries.save(new MealPlanEntry(plan, recipe, request.mealType(),
                request.portions(), position, snapshot));
        plan.markModified();
        return MealPlanEntryResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<MealPlanEntryResponse> findAll(String email, UUID planId) {
        requireOwnedPlan(email, planId);
        return entries.findByMealPlan_IdOrderByMealTypeAscPositionAsc(planId).stream()
                .sorted(Comparator.comparingInt((MealPlanEntry entry) -> entry.getMealType().ordinal())
                        .thenComparingInt(MealPlanEntry::getPosition))
                .map(MealPlanEntryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MealPlanEntryResponse findById(String email, UUID planId, UUID entryId) {
        requireOwnedPlan(email, planId);
        return MealPlanEntryResponse.from(entries.findByIdAndMealPlan_Id(entryId, planId)
                .orElseThrow(() -> new MealPlanEntryNotFoundException(entryId)));
    }

    private void requireOwnedPlan(String email, UUID planId) {
        plans.findByIdAndUser_Email(planId, email)
                .orElseThrow(() -> new MealPlanNotFoundException(planId));
    }

    @Transactional
    public MealPlanEntryResponse updatePortions(String email, UUID planId, UUID entryId,
                                                UpdateMealPlanEntryRequest request) {
        MealPlan plan = requireEditablePlan(email, planId);
        MealPlanEntry entry = entries.findByIdAndMealPlan_Id(entryId, planId)
                .orElseThrow(() -> new MealPlanEntryNotFoundException(entryId));
        entry.updatePortions(request.portions());
        plan.markModified();
        return MealPlanEntryResponse.from(entry);
    }

    @Transactional
    public void delete(String email, UUID planId, UUID entryId) {
        MealPlan plan = requireEditablePlan(email, planId);
        MealPlanEntry entry = entries.findByIdAndMealPlan_Id(entryId, planId)
                .orElseThrow(() -> new MealPlanEntryNotFoundException(entryId));
        entries.delete(entry);
        plan.markModified();
    }

    private MealPlan requireEditablePlan(String email, UUID planId) {
        MealPlan plan = plans.findOwnedForUpdate(planId, email)
                .orElseThrow(() -> new MealPlanNotFoundException(planId));
        if (plan.getStatus() != MealPlanStatus.DRAFT) {
            throw new MealPlanNotEditableException();
        }
        return plan;
    }
}
