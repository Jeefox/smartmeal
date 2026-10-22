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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ShoppingListService {

    private final UserRepository userRepository;
    private dev.jeefox.smartmeal.pantry.PantryService pantry;

    @org.springframework.beans.factory.annotation.Autowired
    public void setPantry(dev.jeefox.smartmeal.pantry.PantryService pantry) {
        this.pantry = pantry;
    }

    private final MealPlanRepository mealPlanRepository;
    private final MealPlanEntryRepository mealPlanEntryRepository;

    public ShoppingListService(
            UserRepository userRepository,
            MealPlanRepository mealPlanRepository,
            MealPlanEntryRepository mealPlanEntryRepository) {
        this.userRepository = userRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.mealPlanEntryRepository = mealPlanEntryRepository;
    }

    @Transactional
    public ShoppingListResponse calculate(String email, LocalDate from, LocalDate to) {

        if (from == null
                || to == null
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) > 30) {
            throw new InvalidShoppingListPeriodException();
        }

        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(() -> new UsernameNotFoundException(email));

        List<MealPlan> plans =
                mealPlanRepository
                        .findByUser_IdAndPlanDateBetweenOrderByPlanDateAsc(user.getId(), from, to)
                        .stream()
                        .filter(p -> p.getStatus() != MealPlanStatus.CANCELLED)
                        .toList();

        boolean containsDrafts =
                plans.stream().anyMatch(plan -> plan.getStatus() == MealPlanStatus.DRAFT);

        if (plans.isEmpty()) {
            return new ShoppingListResponse(from, to, false, List.of());
        }

        Map<UUID, LocalDate> planDates = new HashMap<>();
        for (MealPlan plan : plans) {
            planDates.put(plan.getId(), plan.getPlanDate());
        }

        List<MealPlanEntry> entries =
                mealPlanEntryRepository.findByMealPlan_IdIn(new ArrayList<>(planDates.keySet()));

        var inactive =
                pantry == null
                        ? Set.<UUID>of()
                        : pantry.inactiveEntries(
                                email, entries.stream().map(MealPlanEntry::getId).toList());
        entries = entries.stream().filter(e -> !inactive.contains(e.getId())).toList();
        var demands =
                new ArrayList<dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.Demand>();
        for (var entry : entries)
            demands.addAll(
                    dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.calculate(
                            entry.getId().toString(),
                            planDates.get(entry.getMealPlan().getId()),
                            entry.getMealType().ordinal() * 100000 + entry.getPosition(),
                            entry.getRecipeSnapshot(),
                            entry.getPortions()));
        Map<Long, BigDecimal> coverage = new HashMap<>();
        if (pantry != null) {
            var stock =
                    pantry.snapshot(
                            email,
                            entries.stream()
                                    .map(MealPlanEntry::getId)
                                    .collect(java.util.stream.Collectors.toSet()));
            var protectedIds =
                    entries.stream()
                            .filter(e -> e.getMealPlan().getStatus() == MealPlanStatus.CONFIRMED)
                            .map(e -> e.getId().toString())
                            .collect(java.util.stream.Collectors.toSet());
            var confirmedAllocation =
                    dev.jeefox.smartmeal.pantry.PantryAllocationCalculator.allocate(
                            demands.stream()
                                    .filter(d -> protectedIds.contains(d.entryId()))
                                    .toList(),
                            stock);
            Map<UUID, BigDecimal> used = new HashMap<>();
            confirmedAllocation
                    .allocations()
                    .forEach(a -> used.merge(a.lotId(), a.quantityG(), BigDecimal::add));
            coverage.putAll(confirmedAllocation.coveredG());
            var remainder =
                    new dev.jeefox.smartmeal.pantry.PantryModels.Snapshot(
                            stock.revision(),
                            stock.today(),
                            stock.lots().stream()
                                    .map(
                                            l ->
                                                    new dev.jeefox.smartmeal.pantry.PantryModels
                                                            .StockLot(
                                                            l.id(),
                                                            l.productId(),
                                                            l.quantityG()
                                                                    .subtract(
                                                                            used.getOrDefault(
                                                                                    l.id(),
                                                                                    BigDecimal
                                                                                            .ZERO)),
                                                            l.receivedOn(),
                                                            l.expiresOn(),
                                                            l.createdAt()))
                                    .toList());
            var draftAllocation =
                    dev.jeefox.smartmeal.pantry.PantryAllocationCalculator.allocate(
                            demands.stream()
                                    .filter(d -> !protectedIds.contains(d.entryId()))
                                    .toList(),
                            remainder);
            draftAllocation.coveredG().forEach((id, q) -> coverage.merge(id, q, BigDecimal::add));
        }

        // Вспомогательный тип, доступный только внутри этого метода.
        record ProductLabel(String name, LocalDate date) {}

        Map<Long, BigDecimal> quantities = new HashMap<>();
        Map<Long, ProductLabel> labels = new HashMap<>();

        for (MealPlanEntry entry : entries) {
            RecipeSnapshot snapshot = entry.getRecipeSnapshot();
            LocalDate planDate = planDates.get(entry.getMealPlan().getId());

            for (RecipeIngredientSnapshot ingredient : snapshot.ingredients()) {
                Long productId = ingredient.productId();

                // Quantities are populated from the shared canonical demand below.

                ProductLabel candidate = new ProductLabel(ingredient.shoppingName(), planDate);

                labels.merge(
                        productId,
                        candidate,
                        (existing, incoming) -> {
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

        for (var demand : demands)
            quantities.merge(demand.productId(), demand.quantityG(), BigDecimal::add);
        List<ShoppingListItemResponse> items =
                quantities.entrySet().stream()
                        .map(
                                item ->
                                        new ShoppingListItemResponse(
                                                item.getKey(),
                                                labels.get(item.getKey()).name(),
                                                dev.jeefox.smartmeal.shopping.calculation
                                                        .WeightPrecision.shortage(
                                                        item.getValue(),
                                                        coverage.getOrDefault(
                                                                item.getKey(), BigDecimal.ZERO)),
                                                item.getValue(),
                                                coverage.getOrDefault(
                                                        item.getKey(), BigDecimal.ZERO)))
                        .sorted(
                                Comparator.comparing(ShoppingListItemResponse::productName)
                                        .thenComparing(ShoppingListItemResponse::productId))
                        .toList();

        return new ShoppingListResponse(from, to, containsDrafts, items);
    }
}
