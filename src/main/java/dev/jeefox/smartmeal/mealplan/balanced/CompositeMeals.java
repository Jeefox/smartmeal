package dev.jeefox.smartmeal.mealplan.balanced;

import static dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.*;
import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.generation.period.*;
import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import java.math.*;
import java.time.*;
import java.util.*;

/** Pure, bounded composition of already-filtered recipe candidates. */
public final class CompositeMeals {
    private CompositeMeals() {}

    private static final MathContext MC = MathContext.DECIMAL128;

    public static RecipeSnapshot aggregate(List<Component> components) {
        if (components.isEmpty() || components.size() > 3)
            throw new IllegalArgumentException("Expected one to three components");
        List<RecipeIngredientSnapshot> ingredients = new ArrayList<>();
        List<NutritionValues> nutrition = new ArrayList<>();
        for (var c : components) {
            BigDecimal multiplier =
                    c.amounts().stream()
                            .map(Amount::multiplier)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            nutrition.add(c.recipe().nutritionTotal().scaled(multiplier));
            for (var i : c.recipe().ingredients())
                ingredients.add(
                        new RecipeIngredientSnapshot(
                                i.productId(),
                                i.productName(),
                                i.quantityG().multiply(multiplier),
                                i.shoppingName(),
                                i.edibleYield()));
        }
        var total = NutritionValues.sum(nutrition);
        var root = components.getFirst().recipe();
        String title = String.join(" + ", components.stream().map(c -> c.recipe().name()).toList());
        String instructions =
                String.join(
                        "\n\n",
                        components.stream()
                                .map(c -> c.recipe().name() + "\n" + c.recipe().instructions())
                                .toList());
        return new RecipeSnapshot(
                root.recipeId(),
                title,
                "Составной приём пищи. Количества указаны отдельно для каждого участника.",
                instructions,
                components.stream().mapToInt(c -> c.recipe().preparationTimeMin()).sum(),
                1,
                ingredients,
                total,
                total.rounded(),
                components);
    }

    public static Selection selection(
            List<Component> cs, LocalDate date, MealType meal, boolean locked, String image) {
        var s = aggregate(cs);
        return new Selection(
                date + "-" + meal,
                date,
                meal,
                s,
                RecipeSnapshotFingerprint.calculate(s),
                BigDecimal.ONE,
                locked,
                image,
                List.of(
                        "Размер каждого компонента ограничен отдельно для каждого человека",
                        "Выход готового блюда: "
                                + (cs.stream()
                                                .anyMatch(
                                                        c ->
                                                                c.profile().yieldQuality()
                                                                        == YieldQuality.ESTIMATED)
                                        ? "приблизительная оценка"
                                        : "из источника рецепта")));
    }

    public static List<Selection> choices(
            PlanningContext context,
            Options options,
            LocalDate date,
            MealType meal,
            List<Component> required) {
        var profiles = context.balanced().profiles();
        List<Candidate> pool =
                context.candidates().getOrDefault(meal, List.of()).stream()
                        .filter(
                                c -> {
                                    var p = profiles.get(c.snapshot().recipeId());
                                    return p != null
                                            && p.cookedYieldG() != null
                                            && p.yieldQuality() != YieldQuality.UNKNOWN;
                                })
                        .sorted(Comparator.comparing(c -> c.snapshot().recipeId()))
                        .toList();
        var complete =
                pool.stream()
                        .filter(
                                c ->
                                        Set.of(Role.COMPLETE_MAIN, Role.SOUP, Role.BREAKFAST)
                                                .contains(
                                                        profiles.get(c.snapshot().recipeId())
                                                                .role()))
                        .toList();
        var protein =
                pool.stream()
                        .filter(
                                c ->
                                        profiles.get(c.snapshot().recipeId()).role()
                                                == Role.PROTEIN_MAIN)
                        .limit(8)
                        .toList();
        var sides =
                pool.stream()
                        .filter(c -> profiles.get(c.snapshot().recipeId()).role() == Role.SIDE)
                        .limit(8)
                        .toList();
        var vegetables =
                pool.stream()
                        .filter(c -> profiles.get(c.snapshot().recipeId()).role() == Role.VEGETABLE)
                        .limit(8)
                        .toList();
        List<List<Candidate>> sets = new ArrayList<>();
        complete.forEach(c -> sets.add(List.of(c)));
        for (var p : protein) {
            for (var v : vegetables) sets.add(List.of(p, v));
            for (var s : sides) {
                if (profiles.get(p.snapshot().recipeId()).containsStarch()) continue;
                sets.add(List.of(p, s));
                if (!profiles.get(p.snapshot().recipeId()).containsVegetables())
                    for (var v : vegetables) sets.add(List.of(p, s, v));
            }
        }
        List<Selection> result = new ArrayList<>();
        for (var set : sets) {
            if (set.stream().mapToInt(c -> c.snapshot().preparationTimeMin()).sum()
                    > options.maxPreparationTimeByMeal().get(meal)) continue;
            for (double energyBias : new double[] {.9, 1.0, 1.1}) {
                var cs = new ArrayList<Component>();
                for (var c : set) {
                    var profile = profiles.get(c.snapshot().recipeId());
                    var fixed =
                            required.stream()
                                    .filter(
                                            x ->
                                                    x.recipe()
                                                            .recipeId()
                                                            .equals(c.snapshot().recipeId()))
                                    .findFirst();
                    if (fixed.isPresent()) {
                        cs.add(fixed.get());
                        continue;
                    }
                    List<Amount> amounts = new ArrayList<>();
                    for (var person : context.participants()) {
                        double fraction =
                                meal == MealType.BREAKFAST
                                        ? .25
                                        : meal == MealType.LUNCH ? .40 : .35;
                        double share =
                                set.size() == 1
                                        ? 1
                                        : profile.role() == Role.PROTEIN_MAIN
                                                ? .5
                                                : profile.role() == Role.SIDE
                                                        ? (set.size() == 2 ? .5 : .35)
                                                        : set.size() == 2 ? .5 : .15;
                        double goal = person.dailyCalorieTarget() * fraction * share * energyBias;
                        BigDecimal best = profile.minG();
                        double score = Double.MAX_VALUE;
                        for (BigDecimal g : amountGrid(profile)) {
                            double kcal =
                                    c.snapshot()
                                            .nutritionTotal()
                                            .calories()
                                            .multiply(g)
                                            .divide(profile.cookedYieldG(), MC)
                                            .doubleValue();
                            double error = Math.abs(kcal - goal);
                            if (error < score) {
                                best = g;
                                score = error;
                            }
                        }
                        amounts.add(
                                new Amount(
                                        person.id(),
                                        best.divide(profile.cookedYieldG(), MC),
                                        best));
                    }
                    cs.add(
                            new Component(
                                    profile.role().name() + ":" + c.snapshot().recipeId(),
                                    profile.role(),
                                    c.snapshot(),
                                    profile,
                                    amounts,
                                    false));
                }
                if (required.stream()
                        .anyMatch(r -> cs.stream().noneMatch(c -> c.id().equals(r.id())))) continue;
                boolean volume = true;
                for (var person : context.participants()) {
                    var total =
                            cs.stream()
                                    .flatMap(c -> c.amounts().stream())
                                    .filter(a -> a.participantId().equals(person.id()))
                                    .map(Amount::cookedG)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                    var target = context.balanced().targets().get(person.id());
                    var max = target == null ? new BigDecimal("650") : target.maxMealG();
                    if (total.compareTo(max) > 0) volume = false;
                }
                if (volume)
                    result.add(selection(cs, date, meal, false, set.getFirst().coverImageUrl()));
            }
        }
        var sorted =
                result.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Selection::recipeFingerprint,
                                        x -> x,
                                        (a, b) -> a,
                                        LinkedHashMap::new))
                        .values()
                        .stream()
                        .sorted(
                                Comparator.comparingDouble(
                                                (Selection s) -> mealError(s, context, meal))
                                        .thenComparingLong(
                                                s ->
                                                        ((long) s.recipeFingerprint().hashCode())
                                                                ^ Long.rotateLeft(
                                                                        options.seed(), 17)))
                        .toList();
        var groups = new LinkedHashMap<UUID, List<Selection>>();
        for (var s : sorted)
            groups.computeIfAbsent(s.recipe().recipeId(), x -> new ArrayList<>()).add(s);
        var diverse = new ArrayList<Selection>();
        for (int round = 0; round < 8 && diverse.size() < 40; round++)
            for (var group : groups.values())
                if (group.size() > round && diverse.size() < 40) diverse.add(group.get(round));
        if (options.maxShoppingProducts() != null) {
            var compact =
                    result.stream()
                            .sorted(
                                    Comparator.comparingLong(
                                                    (Selection e) ->
                                                            GroceryPolicy.count(
                                                                    context, options, List.of(e)))
                                            .thenComparingDouble(e -> mealError(e, context, meal)))
                            .toList();
            var kept = new LinkedHashMap<String, Selection>();
            for (var e : compact) {
                kept.putIfAbsent(e.recipeFingerprint(), e);
                if (kept.size() >= 12) break;
            }
            for (var e : diverse) {
                kept.putIfAbsent(e.recipeFingerprint(), e);
                if (kept.size() >= 40) break;
            }
            return List.copyOf(kept.values());
        }
        return List.copyOf(diverse);
    }

    private static List<BigDecimal> amountGrid(Profile profile) {
        var set = new LinkedHashSet<BigDecimal>();
        for (var g = profile.minG();
                g.compareTo(profile.maxG()) <= 0 && set.size() < 128;
                g = g.add(profile.stepG())) set.add(g);
        set.add(profile.maxG());
        return List.copyOf(set);
    }

    private static double mealError(Selection s, PlanningContext context, MealType meal) {
        double factor = meal == MealType.BREAKFAST ? .25 : meal == MealType.LUNCH ? .40 : .35;
        double error = 0;
        for (var person : context.participants()) {
            var n = forPerson(s.recipe(), person.id());
            error +=
                    Math.abs(n.calories().doubleValue() - person.dailyCalorieTarget() * factor)
                            / person.dailyCalorieTarget();
            var target = context.balanced().targets().get(person.id());
            if (target != null && target.proteinTargetG() != null)
                error +=
                        Math.max(
                                        0,
                                        target.proteinTargetG().doubleValue() * factor
                                                - n.protein().doubleValue())
                                / Math.max(1, target.proteinTargetG().doubleValue())
                                * .25;
        }
        return error;
    }

    public static NutritionValues forPerson(RecipeSnapshot s, UUID person) {
        return NutritionValues.sum(
                s.components().stream()
                        .map(
                                c ->
                                        c.recipe()
                                                .nutritionTotal()
                                                .scaled(
                                                        c.amounts().stream()
                                                                .filter(
                                                                        a ->
                                                                                a.participantId()
                                                                                        .equals(
                                                                                                person))
                                                                .map(Amount::multiplier)
                                                                .reduce(
                                                                        BigDecimal.ZERO,
                                                                        BigDecimal::add)))
                        .toList());
    }

    public static void validate(PlanningContext context, Options options, Selection e) {
        var cs = e.recipe().components();
        if (cs.isEmpty() || cs.size() > 3 || e.portions().compareTo(BigDecimal.ONE) != 0)
            throw PlanningException.conflict("Неверный состав приёма пищи");
        if (cs.stream().map(c -> c.recipe().recipeId()).distinct().count() != cs.size())
            throw PlanningException.conflict("Компонент повторяется в одном приёме пищи");
        long starches = cs.stream().filter(c -> c.profile().containsStarch()).count();
        if (starches > 1) throw PlanningException.conflict("В приёме пищи дублируется гарнир");
        if (cs.size() > 1
                && cs.stream()
                        .anyMatch(
                                c -> Set.of(Role.COMPLETE_MAIN, Role.BREAKFAST).contains(c.role())))
            throw PlanningException.conflict(
                    "Самостоятельное блюдо не требует дополнительного гарнира");
        for (var c : cs) {
            var candidate = context.candidate(c.recipe().recipeId());
            var actualProfile = context.balanced().profiles().get(c.recipe().recipeId());
            if (actualProfile == null
                    || actualProfile.cookedYieldG() == null
                    || !actualProfile.equals(c.profile())
                    || c.role() != actualProfile.role()
                    || !RecipeSnapshotFingerprint.calculate(candidate.snapshot())
                            .equals(RecipeSnapshotFingerprint.calculate(c.recipe()))
                    || !candidate.mealTypes().contains(e.mealType()))
                throw PlanningException.conflict(
                        "Состав или данные порции изменились. Пересоберите меню.");
            if (c.amounts().size() != context.participants().size()
                    || c.amounts().stream().map(Amount::participantId).distinct().count()
                            != c.amounts().size())
                throw PlanningException.conflict("Неверные индивидуальные количества");
            for (var person : context.participants()) {
                var a =
                        c.amounts().stream()
                                .filter(x -> x.participantId().equals(person.id()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                PlanningException.conflict(
                                                        "Нет количества участника"));
                if (a.cookedG() == null
                        || a.cookedG().compareTo(c.profile().minG()) < 0
                        || a.cookedG().compareTo(c.profile().maxG()) > 0
                        || a.multiplier()
                                        .subtract(
                                                a.cookedG().divide(c.profile().cookedYieldG(), MC))
                                        .abs()
                                        .compareTo(new BigDecimal("1E-25"))
                                > 0)
                    throw PlanningException.conflict(
                            "Количество выходит за допустимые границы компонента");
            }
        }
        for (var person : context.participants()) {
            var g =
                    cs.stream()
                            .flatMap(c -> c.amounts().stream())
                            .filter(a -> a.participantId().equals(person.id()))
                            .map(Amount::cookedG)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            var t = context.balanced().targets().get(person.id());
            if (g.compareTo(t == null ? new BigDecimal("650") : t.maxMealG()) > 0)
                throw PlanningException.conflict("Слишком большая суммарная масса приёма пищи");
        }
        var rebuilt = aggregate(cs);
        if (!RecipeSnapshotFingerprint.calculate(rebuilt).equals(e.recipeFingerprint())
                || !rebuilt.equals(e.recipe())
                || rebuilt.preparationTimeMin()
                        > options.maxPreparationTimeByMeal().get(e.mealType()))
            throw PlanningException.conflict("Снимок составного приёма пищи изменён");
    }
}
