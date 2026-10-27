package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.Role;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshotFingerprint;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class PeriodMenuEvaluator {
    public static final List<MealType> MEALS =
            List.of(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER);
    private final PurchaseBasketCalculator baskets;

    public PeriodMenuEvaluator(PurchaseBasketCalculator baskets) {
        this.baskets = baskets;
    }

    public Selection selection(
            Candidate candidate,
            LocalDate date,
            MealType meal,
            BigDecimal portions,
            boolean locked) {
        List<String> reasons = new ArrayList<>();
        reasons.add("Приготовление: " + candidate.snapshot().preparationTimeMin() + " мин");
        if (candidate.lastPlannedDate() == null)
            reasons.add("Не встречался в подтверждённых меню за последние 21 день");
        else reasons.add("Последний раз в меню: " + candidate.lastPlannedDate());
        return new Selection(
                date + "-" + meal,
                date,
                meal,
                candidate.snapshot(),
                RecipeSnapshotFingerprint.calculate(candidate.snapshot()),
                portions,
                locked,
                candidate.coverImageUrl(),
                List.copyOf(reasons));
    }

    public boolean canAdd(
            List<Selection> selected, Candidate recipe, LocalDate date, Options options) {
        var previous =
                selected.stream()
                        .filter(e -> e.recipe().recipeId().equals(recipe.snapshot().recipeId()))
                        .toList();
        return previous.size() < options.maxRecipeOccurrences()
                && previous.stream()
                        .noneMatch(
                                e ->
                                        Math.abs(ChronoUnit.DAYS.between(e.date(), date))
                                                <= options.minRepeatGapDays());
    }

    public Document evaluate(
            PlanningContext context,
            Options options,
            List<Selection> selected,
            String searchStatus) {
        List<Issue> issues = new ArrayList<>(context.issues());
        var people = context.participants();
        List<Selection> entries =
                selected.stream()
                        .map(
                                e ->
                                        new Selection(
                                                e.id(),
                                                e.date(),
                                                e.mealType(),
                                                e.recipe(),
                                                e.recipeFingerprint(),
                                                e.portions(),
                                                e.locked(),
                                                e.coverImageUrl(),
                                                e.explanations(),
                                                dev.jeefox.smartmeal.household.HouseholdPortions
                                                        .allocate(
                                                                e.portions(), e.recipe(), people)))
                        .sorted(
                                Comparator.comparing(Selection::date)
                                        .thenComparing(Selection::mealType))
                        .toList();
        Set<String> slots = new HashSet<>();
        List<Selection> checked = new ArrayList<>();
        for (Selection entry : entries) {
            if (entry.date().isBefore(options.from())
                    || entry.date().isAfter(options.to())
                    || !MEALS.contains(entry.mealType())
                    || !slots.add(entry.date() + ":" + entry.mealType()))
                throw PlanningException.conflict("Некорректные записи предпросмотра");
            Candidate candidate = context.candidate(entry.recipe().recipeId());
            boolean eligible =
                    context.candidates().getOrDefault(entry.mealType(), List.of()).stream()
                            .anyMatch(
                                    c -> c.snapshot().recipeId().equals(entry.recipe().recipeId()));
            if (options.balancedMeals()) {
                dev.jeefox.smartmeal.mealplan.balanced.CompositeMeals.validate(
                        context, options, entry);
                if (!canAdd(checked, candidate, entry.date(), options))
                    throw PlanningException.conflict("Нарушены правила повторов");
                checked.add(entry);
                continue;
            }
            if (!eligible
                    || candidate.portions().stream()
                            .noneMatch(p -> p.compareTo(entry.portions()) == 0)
                    || !RecipeSnapshotFingerprint.calculate(candidate.snapshot())
                            .equals(entry.recipeFingerprint())
                    || !canAdd(checked, candidate, entry.date(), options))
                throw PlanningException.conflict("Блюдо нарушает ограничения или рецепт изменился");
            checked.add(entry);
        }
        List<Day> days = new ArrayList<>();
        for (LocalDate date = options.from();
                !date.isAfter(options.to());
                date = date.plusDays(1)) {
            LocalDate d = date;
            var dayEntries = entries.stream().filter(e -> e.date().equals(d)).toList();
            NutritionValues nutrition = nutrition(dayEntries);
            BigDecimal deviation =
                    nutrition.calories().subtract(BigDecimal.valueOf(context.target()));
            boolean fit =
                    dayEntries.size() == 3
                            && deviation
                                            .abs()
                                            .compareTo(
                                                    BigDecimal.valueOf(context.target())
                                                            .multiply(new BigDecimal("0.10")))
                                    <= 0;
            if (options.balancedMeals())
                for (var p : people) {
                    var n =
                            dev.jeefox.smartmeal.nutrition.dto.NutritionValues.sum(
                                    dayEntries.stream()
                                            .map(
                                                    e ->
                                                            dev.jeefox.smartmeal.mealplan.balanced
                                                                    .CompositeMeals.forPerson(
                                                                    e.recipe(), p.id()))
                                            .toList());
                    if (n.calories()
                                    .subtract(BigDecimal.valueOf(p.dailyCalorieTarget()))
                                    .abs()
                                    .compareTo(
                                            BigDecimal.valueOf(p.dailyCalorieTarget())
                                                    .multiply(new BigDecimal("0.10")))
                            > 0) fit = false;
                }
            days.add(
                    new Day(
                            date,
                            nutrition,
                            deviation,
                            fit,
                            dayEntries,
                            dev.jeefox.smartmeal.household.HouseholdPortions.totals(
                                    people,
                                    dayEntries.stream()
                                            .flatMap(e -> e.personPortions().stream())
                                            .toList())));
        }
        Basket shopping = baskets.calculate(entries, context.offers(), options, context.pantry());
        if (options.usePantry()) {
            var demand =
                    new ArrayList<dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.Demand>();
            for (var e : entries)
                demand.addAll(
                        dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.calculate(
                                e.id(),
                                e.date(),
                                e.mealType().ordinal(),
                                e.recipe(),
                                e.portions()));
            if (dev.jeefox.smartmeal.pantry.PantryAllocationCalculator.allocate(
                            demand, context.pantry())
                    .unknownExpiry())
                issues.add(
                        new Issue(
                                "PANTRY_UNKNOWN_EXPIRY",
                                "У использованных домашних запасов не указан срок годности."
                                        + " Проверьте их перед приготовлением."));
        }
        boolean groceriesFit = GroceryPolicy.fits(options, shopping);
        if (!groceriesFit)
            issues.add(
                    new Issue(
                            "SHOPPING_PRODUCT_LIMIT",
                            "Нужно купить разных продуктов: "
                                    + GroceryPolicy.count(shopping)
                                    + ", заданный максимум: "
                                    + options.maxShoppingProducts()
                                    + ". Пересоберите меню с общими ингредиентами или измените"
                                    + " предел покупок."));
        boolean full = entries.size() == days.size() * 3;
        if (!full && options.maxShoppingProducts() != null)
            issues.add(
                    new Issue(
                            "COMPACT_BASKET_SEARCH",
                            "При ограничении до "
                                    + options.maxShoppingProducts()
                                    + " продуктов не найдено полного меню в рамках текущего поиска."
                                    + " Увеличьте предел до 10, разрешите больше повторов блюд или"
                                    + " добавьте рецепты с общими ингредиентами. Это не"
                                    + " доказательство невозможности такого меню."));
        if (!full)
            for (var meal : MEALS) {
                var pool = context.candidates().getOrDefault(meal, List.of());
                int available =
                        (int)
                                pool.stream()
                                        .filter(
                                                c ->
                                                        !options.balancedMeals()
                                                                || isMainWithYield(context, c))
                                        .count();
                long missingProfiles = pool.stream().filter(c -> missingYield(context, c)).count();
                if (options.balancedMeals() && missingProfiles > 0)
                    issues.add(
                            new Issue(
                                    "MISSING_YIELD_" + meal,
                                    "Для "
                                            + missingProfiles
                                            + " рецептов ("
                                            + switch (meal) {
                                                case BREAKFAST -> "завтрак";
                                                case LUNCH -> "обед";
                                                default -> "ужин";
                                            }
                                            + ") не указан выход готового блюда. Заполните выход в"
                                            + " настройках рецептов; такие блюда исключены из"
                                            + " нового подбора."));
                long needed =
                        (days.size() + options.maxRecipeOccurrences() - 1)
                                / options.maxRecipeOccurrences();
                if (available < needed)
                    issues.add(
                            new Issue(
                                    "INSUFFICIENT_" + meal,
                                    switch (meal) {
                                                case BREAKFAST -> "Завтрак";
                                                case LUNCH -> "Обед";
                                                default -> "Ужин";
                                            }
                                            + ": подходит рецептов "
                                            + available
                                            + ", при выбранном числе повторов требуется хотя бы "
                                            + needed
                                            + ". Увеличьте время или разрешите повторы."));
            }
        if (!full)
            issues.add(
                    new Issue(
                            "NO_FEASIBLE_FOUND",
                            "Не удалось заполнить все приёмы пищи при заданных ограничениях."
                                    + " Увеличьте время, разрешите повторы или добавьте рецепты."));
        if (full && days.stream().anyMatch(d -> !d.withinTargetTolerance()))
            issues.add(
                    new Issue(
                            "TARGET_NOT_REACHED",
                            "Некоторые дни выходят за допуск калорийности ±10%. Попробуйте другие"
                                    + " рецепты или порции."));
        if (shopping.unknownProducts() > 0)
            issues.add(
                    new Issue(
                            "PRICES_INCOMPLETE",
                            "Нет цены для "
                                    + shopping.unknownProducts()
                                    + " продуктов; полную стоимость проверить нельзя."));
        if (shopping.estimatedProducts() > 0)
            issues.add(
                    new Issue(
                            "ESTIMATED_PRICES",
                            "Для "
                                    + shopping.estimatedProducts()
                                    + " продуктов использованы приблизительные цены. Наличие в"
                                    + " выбранной сети не гарантировано."));
        if (shopping.budgetStatus() == BudgetStatus.OVER)
            issues.add(
                    new Issue(
                            "BUDGET_EXCEEDED",
                            "Найденный вариант превышает бюджет на "
                                    + shopping.knownPurchaseCost()
                                            .subtract(options.budgetRub())
                                            .toPlainString()
                                    + " ₽. Это не доказанный минимум."));
        if ("LIMIT_REACHED".equals(searchStatus))
            issues.add(
                    new Issue(
                            "SEARCH_LIMIT",
                            "Достигнут лимит поиска. Можно попробовать другой вариант; глобальный"
                                    + " минимум не гарантируется."));
        if (options.balancedMeals()) {
            for (var p : people) {
                var t = context.balanced().targets().get(p.id());
                if (t == null || t.proteinTargetG() == null || t.fiberTargetG() == null)
                    issues.add(
                            new Issue(
                                    "NUTRIENT_TARGETS_UNSET",
                                    "Для "
                                            + p.name()
                                            + " не все цели белка и клетчатки настроены"));
            }
            for (var day : days)
                for (var p : people) {
                    var n =
                            dev.jeefox.smartmeal.nutrition.dto.NutritionValues.sum(
                                    day.entries().stream()
                                            .map(
                                                    e ->
                                                            dev.jeefox.smartmeal.mealplan.balanced
                                                                    .CompositeMeals.forPerson(
                                                                    e.recipe(), p.id()))
                                            .toList());
                    var t = context.balanced().targets().get(p.id());
                    if (!n.fiberComplete())
                        issues.add(
                                new Issue(
                                        "FIBER_DATA_INCOMPLETE",
                                        day.date()
                                                + ", "
                                                + p.name()
                                                + ": данные клетчатки неполные; известная часть "
                                                + n.knownFiber().setScale(1, RoundingMode.HALF_UP)
                                                + " г"));
                    if (t != null
                            && t.proteinTargetG() != null
                            && n.protein().compareTo(t.proteinTargetG()) < 0)
                        issues.add(
                                new Issue(
                                        "PROTEIN_TARGET_NOT_REACHED",
                                        day.date()
                                                + ", "
                                                + p.name()
                                                + ": не достигнута цель белка"));
                    if (t != null
                            && t.fiberTargetG() != null
                            && n.fiberComplete()
                            && n.knownFiber().compareTo(t.fiberTargetG()) < 0)
                        issues.add(
                                new Issue(
                                        "FIBER_TARGET_NOT_REACHED",
                                        day.date()
                                                + ", "
                                                + p.name()
                                                + ": не достигнута цель клетчатки"));
                }
        }
        return new Document(
                options,
                context.target(),
                context.fingerprint(),
                context.asOf(),
                entries,
                List.copyOf(days),
                shopping,
                List.copyOf(issues),
                !full || !groceriesFit
                        ? "NO_FEASIBLE_FOUND"
                        : issues.isEmpty() ? "READY" : "READY_WITH_WARNINGS",
                searchStatus,
                options.balancedMeals() ? "balanced-composite-v1" : "period-beam-v2-pantry",
                options.usePantry() ? context.pantry().revision() : null,
                options.usePantry() ? context.pantry().today() : null,
                people);
    }

    private static boolean missingYield(PlanningContext context, Candidate candidate) {
        var profile = context.balanced().profiles().get(candidate.snapshot().recipeId());
        return profile == null || profile.cookedYieldG() == null;
    }

    private static boolean isMainWithYield(PlanningContext context, Candidate candidate) {
        if (missingYield(context, candidate)) return false;
        var role = context.balanced().profiles().get(candidate.snapshot().recipeId()).role();
        return Set.of(Role.COMPLETE_MAIN, Role.BREAKFAST, Role.SOUP, Role.PROTEIN_MAIN)
                .contains(role);
    }

    public NutritionValues nutrition(List<Selection> entries) {
        return NutritionValues.sum(
                        entries.stream()
                                .map(
                                        e ->
                                                e.recipe()
                                                        .nutritionTotal()
                                                        .scaled(
                                                                e.portions()
                                                                        .divide(
                                                                                BigDecimal.valueOf(
                                                                                        e.recipe()
                                                                                                .servings()),
                                                                                MathContext
                                                                                        .DECIMAL128)))
                                .toList())
                .rounded();
    }

    private BigDecimal sum(List<Selection> entries, int field) {
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : entries) {
            var n = entry.recipe().nutritionTotal();
            BigDecimal value =
                    switch (field) {
                        case 0 -> n.calories();
                        case 1 -> n.protein();
                        case 2 -> n.fat();
                        default -> n.carbs();
                    };
            total =
                    total.add(
                            value.multiply(entry.portions())
                                    .divide(
                                            BigDecimal.valueOf(entry.recipe().servings()),
                                            MathContext.DECIMAL128));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
