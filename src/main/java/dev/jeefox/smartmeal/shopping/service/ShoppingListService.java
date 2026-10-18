package dev.jeefox.smartmeal.shopping.service;

import dev.jeefox.smartmeal.exception.InvalidShoppingListPeriodException;

import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanStatus;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanEntryRepository;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanRepository;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeIngredientSnapshot;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListItemResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListResponse;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ShoppingListService {

    private final UserRepository userRepository;
    private final MealPlanRepository mealPlanRepository;
    private final MealPlanEntryRepository mealPlanEntryRepository;

    public ShoppingListService(UserRepository userRepository,
                               MealPlanRepository mealPlanRepository,
                               MealPlanEntryRepository mealPlanEntryRepository) {
        this.userRepository = userRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.mealPlanEntryRepository = mealPlanEntryRepository;
    }

    @Transactional(readOnly = true)
    public ShoppingListResponse calculate(
            String email, LocalDate from, LocalDate to) {

        if (from == null || to == null
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) > 30) {
            throw new InvalidShoppingListPeriodException();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(email));

        List<MealPlan> plans = mealPlanRepository
                .findByUser_IdAndPlanDateBetweenOrderByPlanDateAsc(
                        user.getId(), from, to);

        boolean containsDrafts = plans.stream()
                .anyMatch(plan -> plan.getStatus() == MealPlanStatus.DRAFT);

        if (plans.isEmpty()) {
            return new ShoppingListResponse(from, to, false, List.of());
        }

        Map<UUID, LocalDate> planDates = new HashMap<>();
        for (MealPlan plan : plans) {
            planDates.put(plan.getId(), plan.getPlanDate());
        }

        List<MealPlanEntry> entries =
                mealPlanEntryRepository.findByMealPlan_IdIn(
                        new ArrayList<>(planDates.keySet()));

        // Вспомогательный тип, доступный только внутри этого метода.
        record ProductLabel(String name, LocalDate date) {}

        Map<Long, BigDecimal> quantities = new HashMap<>();
        Map<Long, ProductLabel> labels = new HashMap<>();

        for (MealPlanEntry entry : entries) {
            RecipeSnapshot snapshot = entry.getRecipeSnapshot();
            LocalDate planDate = planDates.get(entry.getMealPlan().getId());

            for (RecipeIngredientSnapshot ingredient : snapshot.ingredients()) {
                BigDecimal ingredientQuantity = ingredient.quantityG()
                        .multiply(entry.getPortions())
                        .divide(
                                BigDecimal.valueOf(snapshot.servings()),
                                MathContext.DECIMAL128);

                Long productId = ingredient.productId();

                quantities.merge(productId, ingredientQuantity, BigDecimal::add);

                ProductLabel candidate =
                        new ProductLabel(ingredient.productName(), planDate);

                labels.merge(productId, candidate, (existing, incoming) -> {
                    if (incoming.date().isAfter(existing.date())) {
                        return incoming;
                    }

                    if (incoming.date().equals(existing.date())
                            && incoming.name().compareTo(existing.name()) < 0) {
                        return incoming;
                    }

                    return existing;
                });
            }
        }

        List<ShoppingListItemResponse> items = quantities.entrySet().stream()
                .map(item -> new ShoppingListItemResponse(
                        item.getKey(),
                        labels.get(item.getKey()).name(),
                        item.getValue().setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator
                        .comparing(ShoppingListItemResponse::productName)
                        .thenComparing(ShoppingListItemResponse::productId))
                .toList();

        return new ShoppingListResponse(from, to, containsDrafts, items);
    }
}
