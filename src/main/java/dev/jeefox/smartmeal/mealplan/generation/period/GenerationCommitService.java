package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.repository.*;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import dev.jeefox.smartmeal.user.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class GenerationCommitService {
    @org.springframework.beans.factory.annotation.Autowired
    private dev.jeefox.smartmeal.household.HouseholdSnapshots households;

    private final GenerationStore store;
    private final MenuGenerationRepository generations;
    private final UserRepository users;
    private final PlanningContextLoader contexts;
    private final PeriodMenuEvaluator evaluator;
    private final MealPlanRepository plans;
    private final MealPlanEntryRepository entries;
    private final RecipeRepository recipes;

    public GenerationCommitService(
            GenerationStore store,
            MenuGenerationRepository generations,
            UserRepository users,
            PlanningContextLoader contexts,
            PeriodMenuEvaluator evaluator,
            MealPlanRepository plans,
            MealPlanEntryRepository entries,
            RecipeRepository recipes) {
        this.store = store;
        this.generations = generations;
        this.users = users;
        this.contexts = contexts;
        this.evaluator = evaluator;
        this.plans = plans;
        this.entries = entries;
        this.recipes = recipes;
    }

    @Transactional
    public View commit(String email, UUID id, UUID key, Commit request) {
        // Same user lock order as existing preview saves: serialize user/date allocation.
        var owner = users.findByEmailForUpdate(email).orElseThrow();
        var generation = store.owned(email, id, true);
        if (generation.getCommitKey() != null) {
            if (generation.getCommitKey().equals(key)) return generation.view();
            throw PlanningException.conflict(
                    "GENERATION_ALREADY_SAVED", "Меню уже сохранено другим запросом");
        }
        if (generations.findByUser_IdAndCommitKey(owner.getId(), key).isPresent())
            throw PlanningException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Этот ключ сохранения уже использован для другого меню");
        GenerationStore.checkEditable(generation, request.expectedRevision());
        var document = generation.getDocument();
        PlanningContext context = contexts.load(email, document.options());
        if (document.options().usePantry()
                && (document.pantryRevision() == null
                        || document.pantryRevision() != context.pantry().revision()
                        || !context.pantry().today().equals(document.pantryAsOf())))
            throw PlanningException.conflict(
                    "PANTRY_CHANGED",
                    "Запасы изменились. Обновите оценку покупок перед сохранением.");
        if (!context.fingerprint().equals(document.contextFingerprint()))
            throw PlanningException.conflict(
                    "PLANNING_CONTEXT_CHANGED",
                    "Профиль, ограничения или рецепты изменились. Создайте новый подбор.");
        var checked =
                evaluator.evaluate(
                        context, document.options(), document.entries(), document.searchStatus());
        if (checked.status().equals("NO_FEASIBLE_FOUND"))
            throw PlanningException.conflict("MENU_INCOMPLETE", "Сначала заполните все дни меню");
        if (!checked.shopping().equals(document.shopping()))
            throw PlanningException.conflict(
                    "SHOPPING_ESTIMATE_CHANGED",
                    "Оценка покупок изменилась. Пересоберите меню для проверки новых цен.");
        if (!checked.issues().isEmpty() && !request.acceptWarnings())
            throw PlanningException.conflict(
                    "WARNINGS_NOT_ACCEPTED",
                    "Проверьте предупреждения и явно подтвердите сохранение с ними");
        var existing =
                plans.findByUser_IdAndPlanDateBetweenOrderByPlanDateAsc(
                        owner.getId(), document.options().from(), document.options().to());
        if (!existing.isEmpty())
            throw PlanningException.conflict(
                    "MEAL_PLAN_DATE_CONFLICT",
                    "На часть выбранных дат уже есть меню. Существующие планы не перезаписаны.");
        List<UUID> ids = new ArrayList<>();
        for (Day day : checked.days()) {
            MealPlan plan = plans.saveAndFlush(new MealPlan(owner, day.date()));
            households.save(plan.getId(), checked.participants());
            ids.add(plan.getId());
            for (Selection selected : day.entries())
                entries.save(
                        new MealPlanEntry(
                                plan,
                                recipes.getReferenceById(selected.recipe().recipeId()),
                                selected.mealType(),
                                selected.portions(),
                                1,
                                selected.recipe()));
        }
        entries.flush();
        generation.committed(key, ids);
        generations.flush();
        return generation.view();
    }
}
