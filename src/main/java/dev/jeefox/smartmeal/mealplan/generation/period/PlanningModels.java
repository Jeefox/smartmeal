package dev.jeefox.smartmeal.mealplan.generation.period;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class PlanningModels {
    private PlanningModels() {}

    public enum PurchaseMode {
        SINGLE_CHAIN,
        TWO_CHAINS
    }

    public enum BudgetStatus {
        WITHIN,
        OVER,
        UNKNOWN,
        NOT_SET
    }

    public enum PriceQuality {
        COMPLETE_OBSERVED,
        COMPLETE_ESTIMATED,
        PARTIAL
    }

    public record Options(
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @Positive @Digits(integer = 10, fraction = 2) BigDecimal budgetRub,
            @NotEmpty @Size(max = 6) List<@NotNull @Positive Long> allowedChainIds,
            @NotBlank @Size(max = 120) String city,
            PurchaseMode purchaseMode,
            Map<MealType, @Min(0) @Max(240) Integer> maxPreparationTimeByMeal,
            @Min(1) @Max(14) Integer maxRecipeOccurrences,
            @Min(0) @Max(14) Integer minRepeatGapDays,
            Boolean allowEstimatedPrices,
            @PositiveOrZero @Digits(integer = 6, fraction = 2) BigDecimal minSplitSavingRub,
            Long seed,
            Boolean usePantry,
            @Size(max = 9) List<@NotNull UUID> householdMemberIds) {
        public Options(
                LocalDate from,
                LocalDate to,
                BigDecimal budgetRub,
                List<Long> allowedChainIds,
                String city,
                PurchaseMode purchaseMode,
                Map<MealType, Integer> maxPreparationTimeByMeal,
                Integer maxRecipeOccurrences,
                Integer minRepeatGapDays,
                Boolean allowEstimatedPrices,
                BigDecimal minSplitSavingRub,
                Long seed,
                Boolean usePantry) {
            this(
                    from,
                    to,
                    budgetRub,
                    allowedChainIds,
                    city,
                    purchaseMode,
                    maxPreparationTimeByMeal,
                    maxRecipeOccurrences,
                    minRepeatGapDays,
                    allowEstimatedPrices,
                    minSplitSavingRub,
                    seed,
                    usePantry,
                    List.of());
        }

        public Options(
                LocalDate from,
                LocalDate to,
                BigDecimal budgetRub,
                List<Long> allowedChainIds,
                String city,
                PurchaseMode purchaseMode,
                Map<MealType, Integer> maxPreparationTimeByMeal,
                Integer maxRecipeOccurrences,
                Integer minRepeatGapDays,
                Boolean allowEstimatedPrices,
                BigDecimal minSplitSavingRub,
                Long seed) {
            this(
                    from,
                    to,
                    budgetRub,
                    allowedChainIds,
                    city,
                    purchaseMode,
                    maxPreparationTimeByMeal,
                    maxRecipeOccurrences,
                    minRepeatGapDays,
                    allowEstimatedPrices,
                    minSplitSavingRub,
                    seed,
                    true);
        }

        public Options {
            householdMemberIds =
                    householdMemberIds == null
                            ? List.of()
                            : Collections.unmodifiableList(new ArrayList<>(householdMemberIds));
            usePantry = usePantry == null || usePantry;
            city = city == null ? "Тула" : city.trim();
            purchaseMode = purchaseMode == null ? PurchaseMode.SINGLE_CHAIN : purchaseMode;
            EnumMap<MealType, Integer> limits = new EnumMap<>(MealType.class);
            limits.put(MealType.BREAKFAST, 15);
            limits.put(MealType.LUNCH, 45);
            limits.put(MealType.DINNER, 45);
            if (maxPreparationTimeByMeal != null) limits.putAll(maxPreparationTimeByMeal);
            maxPreparationTimeByMeal = Collections.unmodifiableMap(limits);
            allowedChainIds = allowedChainIds == null ? List.of() : List.copyOf(allowedChainIds);
            maxRecipeOccurrences = maxRecipeOccurrences == null ? 2 : maxRecipeOccurrences;
            minRepeatGapDays = minRepeatGapDays == null ? 2 : minRepeatGapDays;
            allowEstimatedPrices = allowEstimatedPrices == null || allowEstimatedPrices;
            minSplitSavingRub =
                    minSplitSavingRub == null ? new BigDecimal("50.00") : minSplitSavingRub;
            seed = seed == null ? 0L : seed;
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        @AssertTrue(message = "Период должен содержать от 1 до 14 дней, даты от 1900 до 2200 года")
        public boolean isPeriodValid() {
            return from == null
                    || to == null
                    || (!to.isBefore(from)
                            && !from.isBefore(LocalDate.of(1900, 1, 1))
                            && !to.isAfter(LocalDate.of(2200, 12, 31))
                            && ChronoUnit.DAYS.between(from, to) < 14);
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        @AssertTrue(message = "Укажите разные сети и время для завтрака, обеда и ужина")
        public boolean isSettingsValid() {
            return new HashSet<>(householdMemberIds).size() == householdMemberIds.size()
                    && new HashSet<>(allowedChainIds).size() == allowedChainIds.size()
                    && maxPreparationTimeByMeal.values().stream().allMatch(Objects::nonNull)
                    && !maxPreparationTimeByMeal.containsKey(MealType.SNACK);
        }

        public Options withSeed(long value) {
            return new Options(
                    from,
                    to,
                    budgetRub,
                    allowedChainIds,
                    city,
                    purchaseMode,
                    maxPreparationTimeByMeal,
                    maxRecipeOccurrences,
                    minRepeatGapDays,
                    allowEstimatedPrices,
                    minSplitSavingRub,
                    value,
                    usePantry,
                    householdMemberIds);
        }
    }

    public record Chain(Long id, String name) {}

    public record Candidate(
            RecipeSnapshot snapshot,
            Set<MealType> mealTypes,
            List<BigDecimal> portions,
            String family,
            String coverImageUrl,
            LocalDate lastPlannedDate) {}

    public record Offer(
            String key,
            Long productId,
            Long chainId,
            String chainName,
            String name,
            BigDecimal packageWeightG,
            BigDecimal packagePrice,
            boolean soldByWeight,
            boolean estimated,
            String sourceName,
            String sourceScope,
            String sourceUrl,
            Instant observedAt,
            Instant expiresAt,
            String applicability) {}

    public record BasketItem(
            Long productId,
            String productName,
            BigDecimal requiredG,
            BigDecimal purchasedG,
            BigDecimal remainderG,
            Integer packageCount,
            BigDecimal packageWeightG,
            BigDecimal packagePrice,
            BigDecimal purchaseCost,
            Long chainId,
            String chainName,
            String offerKey,
            String offerName,
            boolean estimated,
            String sourceName,
            String sourceScope,
            String sourceUrl,
            Instant observedAt,
            Instant expiresAt,
            String applicability,
            BigDecimal fromPantryG,
            BigDecimal needToBuyG) {
        public BasketItem(
                Long productId,
                String productName,
                BigDecimal requiredG,
                BigDecimal purchasedG,
                BigDecimal remainderG,
                Integer packageCount,
                BigDecimal packageWeightG,
                BigDecimal packagePrice,
                BigDecimal purchaseCost,
                Long chainId,
                String chainName,
                String offerKey,
                String offerName,
                boolean estimated,
                String sourceName,
                String sourceScope,
                String sourceUrl,
                Instant observedAt,
                Instant expiresAt,
                String applicability) {
            this(
                    productId,
                    productName,
                    requiredG,
                    purchasedG,
                    remainderG,
                    packageCount,
                    packageWeightG,
                    packagePrice,
                    purchaseCost,
                    chainId,
                    chainName,
                    offerKey,
                    offerName,
                    estimated,
                    sourceName,
                    sourceScope,
                    sourceUrl,
                    observedAt,
                    expiresAt,
                    applicability,
                    BigDecimal.ZERO,
                    requiredG);
        }
    }

    public record Basket(
            List<BasketItem> items,
            BigDecimal knownPurchaseCost,
            BigDecimal estimatedTotal,
            BigDecimal consumedIngredientCost,
            int pricedProducts,
            int estimatedProducts,
            int unknownProducts,
            PriceQuality priceQuality,
            BudgetStatus budgetStatus,
            List<Chain> chains,
            BigDecimal savingAgainstSingleChain) {}

    public record Selection(
            String id,
            LocalDate date,
            MealType mealType,
            RecipeSnapshot recipe,
            String recipeFingerprint,
            BigDecimal portions,
            boolean locked,
            String coverImageUrl,
            List<String> explanations,
            List<dev.jeefox.smartmeal.household.HouseholdModels.PersonPortion> personPortions) {
        public Selection(
                String id,
                LocalDate date,
                MealType mealType,
                RecipeSnapshot recipe,
                String recipeFingerprint,
                BigDecimal portions,
                boolean locked,
                String coverImageUrl,
                List<String> explanations) {
            this(
                    id,
                    date,
                    mealType,
                    recipe,
                    recipeFingerprint,
                    portions,
                    locked,
                    coverImageUrl,
                    explanations,
                    List.of());
        }

        public Selection {
            personPortions = personPortions == null ? List.of() : List.copyOf(personPortions);
        }
    }

    public record Day(
            LocalDate date,
            NutritionValues nutrition,
            BigDecimal calorieDeviation,
            boolean withinTargetTolerance,
            List<Selection> entries,
            List<dev.jeefox.smartmeal.household.HouseholdModels.PersonNutrition> people) {
        public Day(
                LocalDate date,
                NutritionValues nutrition,
                BigDecimal calorieDeviation,
                boolean withinTargetTolerance,
                List<Selection> entries) {
            this(date, nutrition, calorieDeviation, withinTargetTolerance, entries, List.of());
        }
    }

    public record Issue(String code, String message) {}

    public record Document(
            Options options,
            int dailyCalorieTarget,
            String contextFingerprint,
            Instant dataAsOf,
            List<Selection> entries,
            List<Day> days,
            Basket shopping,
            List<Issue> issues,
            String status,
            String searchStatus,
            String algorithmVersion,
            Long pantryRevision,
            LocalDate pantryAsOf,
            List<dev.jeefox.smartmeal.household.HouseholdModels.Participant> participants) {
        public Document(
                Options options,
                int dailyCalorieTarget,
                String contextFingerprint,
                Instant dataAsOf,
                List<Selection> entries,
                List<Day> days,
                Basket shopping,
                List<Issue> issues,
                String status,
                String searchStatus,
                String algorithmVersion,
                Long pantryRevision,
                LocalDate pantryAsOf) {
            this(
                    options,
                    dailyCalorieTarget,
                    contextFingerprint,
                    dataAsOf,
                    entries,
                    days,
                    shopping,
                    issues,
                    status,
                    searchStatus,
                    algorithmVersion,
                    pantryRevision,
                    pantryAsOf,
                    List.of());
        }

        public Document {
            participants = participants == null ? List.of() : List.copyOf(participants);
        }

        public Document(
                Options options,
                int dailyCalorieTarget,
                String contextFingerprint,
                Instant dataAsOf,
                List<Selection> entries,
                List<Day> days,
                Basket shopping,
                List<Issue> issues,
                String status,
                String searchStatus,
                String algorithmVersion) {
            this(
                    options,
                    dailyCalorieTarget,
                    contextFingerprint,
                    dataAsOf,
                    entries,
                    days,
                    shopping,
                    issues,
                    status,
                    searchStatus,
                    algorithmVersion,
                    null,
                    null);
        }
    }

    public record View(
            UUID id, long revision, Instant expiresAt, Document result, List<UUID> savedPlanIds) {}

    public record Revision(@Min(0) long expectedRevision) {}

    public record Replacement(
            @Min(0) long expectedRevision,
            @NotNull UUID recipeId,
            @NotNull @Positive @Digits(integer = 2, fraction = 2) BigDecimal portions) {}

    public record Lock(@Min(0) long expectedRevision, boolean locked) {}

    public record Commit(@Min(0) long expectedRevision, boolean acceptWarnings) {}

    public record Alternative(
            UUID recipeId,
            String name,
            BigDecimal portions,
            int preparationTimeMin,
            NutritionValues dayNutrition,
            BigDecimal newPurchaseCost,
            BigDecimal deltaPurchaseCost,
            BudgetStatus budgetStatus,
            PriceQuality priceQuality) {}
}
