package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class PurchaseBasketCalculatorTest {
    final PurchaseBasketCalculator calculator = new PurchaseBasketCalculator();

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    static Options options(BigDecimal budget, PurchaseMode mode, boolean estimates) {
        return new Options(
                LocalDate.of(2035, 1, 1),
                LocalDate.of(2035, 1, 7),
                budget,
                List.of(1L, 2L),
                "Тула",
                mode,
                null,
                null,
                null,
                estimates,
                bd("50"),
                0L);
    }

    static Selection selection(long product, String grams, String yield) {
        var nutrition = new NutritionValues(bd("500"), bd("20"), bd("10"), bd("30"));
        var recipe =
                new RecipeSnapshot(
                        UUID.randomUUID(),
                        "Рецепт",
                        null,
                        "Готовить",
                        10,
                        1,
                        List.of(
                                new RecipeIngredientSnapshot(
                                        product,
                                        "Продукт " + product,
                                        bd(grams),
                                        "Покупка " + product,
                                        bd(yield))),
                        nutrition,
                        nutrition);
        return new Selection(
                UUID.randomUUID().toString(),
                LocalDate.of(2035, 1, 1),
                MealType.BREAKFAST,
                recipe,
                "hash",
                BigDecimal.ONE,
                false,
                null,
                List.of());
    }

    static Offer offer(
            long product,
            long chain,
            String weight,
            String price,
            boolean estimated,
            String scope) {
        return new Offer(
                product + ":" + chain + ":" + price,
                product,
                chain,
                "Сеть " + chain,
                "Упаковка",
                bd(weight),
                bd(price),
                false,
                estimated,
                "Каталог",
                scope,
                null,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                scope);
    }

    @Test
    void buysOneSharedPackageAndSeparatesConsumptionFromCheckout() {
        var basket =
                calculator.calculate(
                        List.of(selection(1, "200", "1"), selection(1, "250", "1")),
                        List.of(offer(1, 1, "900", "90", false, "STORE_OBSERVATION")),
                        options(bd("100"), PurchaseMode.SINGLE_CHAIN, true));
        assertEquals(bd("90.00"), basket.knownPurchaseCost());
        assertEquals(bd("45.00"), basket.consumedIngredientCost());
        assertEquals(1, basket.items().getFirst().packageCount());
        assertEquals(0, basket.items().getFirst().remainderG().compareTo(bd("450")));
        assertEquals(BudgetStatus.WITHIN, basket.budgetStatus());
    }

    @Test
    void doesNotRoundAwayAnAdditionalPackage() {
        var basket =
                calculator.calculate(
                        List.of(selection(1, "900.001", "1")),
                        List.of(offer(1, 1, "900", "90", false, "STORE_OBSERVATION")),
                        options(null, PurchaseMode.SINGLE_CHAIN, true));
        assertEquals(2, basket.items().getFirst().packageCount());
    }

    @Test
    void convertsRawEdibleWeightToPurchasedWeight() {
        var basket =
                calculator.calculate(
                        List.of(selection(1, "150", ".75")),
                        List.of(offer(1, 1, "200", "25", false, "STORE_OBSERVATION")),
                        options(null, PurchaseMode.SINGLE_CHAIN, true));
        assertEquals(0, basket.items().getFirst().requiredG().compareTo(bd("200")));
        assertEquals(bd("25.00"), basket.knownPurchaseCost());
    }

    @Test
    void missingPricesAreUnknownAndKnownOverBudgetRemainsOver() {
        var entries = List.of(selection(1, "150", "1"), selection(2, "100", "1"));
        var offers = List.of(offer(1, 1, "200", "120", false, "STORE_OBSERVATION"));
        var basket =
                calculator.calculate(
                        entries, offers, options(bd("200"), PurchaseMode.SINGLE_CHAIN, true));
        assertEquals(BudgetStatus.UNKNOWN, basket.budgetStatus());
        assertNull(basket.estimatedTotal());
        assertEquals(PriceQuality.PARTIAL, basket.priceQuality());
        assertEquals(
                BudgetStatus.OVER,
                calculator
                        .calculate(
                                entries,
                                offers,
                                options(bd("100"), PurchaseMode.SINGLE_CHAIN, true))
                        .budgetStatus());
    }

    @Test
    void estimatesAreMarkedAndCanBeDisabled() {
        var entries = List.of(selection(1, "150", "1"));
        var offers = List.of(offer(1, 1, "200", "25", true, "CHAIN_CATALOGUE_ESTIMATE"));
        assertEquals(
                PriceQuality.COMPLETE_ESTIMATED,
                calculator
                        .calculate(entries, offers, options(null, PurchaseMode.SINGLE_CHAIN, true))
                        .priceQuality());
        assertEquals(
                1,
                calculator
                        .calculate(entries, offers, options(null, PurchaseMode.SINGLE_CHAIN, false))
                        .unknownProducts());
    }

    @Test
    void applicableOfferWinsOverCheaperCrossChainReference() {
        var offers =
                List.of(
                        offer(1, 1, "200", "25", true, "REFERENCE_ONLY"),
                        offer(1, 1, "200", "30", true, "CHAIN_CATALOGUE_ESTIMATE"));
        var options =
                new Options(
                        LocalDate.of(2035, 1, 1),
                        LocalDate.of(2035, 1, 1),
                        null,
                        List.of(1L),
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        0L);
        assertEquals(
                bd("30.00"),
                calculator
                        .calculate(List.of(selection(1, "150", "1")), offers, options)
                        .knownPurchaseCost());
    }

    @Test
    void splittingUsesAtMostTwoChainsAndRequiresMeaningfulSaving() {
        var entries = List.of(selection(1, "100", "1"), selection(2, "100", "1"));
        var offers =
                List.of(
                        offer(1, 1, "100", "20", false, "STORE_OBSERVATION"),
                        offer(2, 1, "100", "100", false, "STORE_OBSERVATION"),
                        offer(1, 2, "100", "100", false, "STORE_OBSERVATION"),
                        offer(2, 2, "100", "20", false, "STORE_OBSERVATION"));
        var basket =
                calculator.calculate(entries, offers, options(null, PurchaseMode.TWO_CHAINS, true));
        assertEquals(bd("40.00"), basket.knownPurchaseCost());
        assertEquals(2, basket.chains().size());
        assertEquals(bd("80.00"), basket.savingAgainstSingleChain());
    }

    @Test
    void weightedOffersDoNotInventAWholeKilogramPackage() {
        Offer base = offer(1, 1, "1000", "100", true, "CHAIN_CATALOGUE_ESTIMATE");
        Offer weighted =
                new Offer(
                        base.key(),
                        1L,
                        1L,
                        base.chainName(),
                        base.name(),
                        base.packageWeightG(),
                        base.packagePrice(),
                        true,
                        true,
                        base.sourceName(),
                        base.sourceScope(),
                        null,
                        base.observedAt(),
                        base.expiresAt(),
                        base.applicability());
        var basket =
                calculator.calculate(
                        List.of(selection(1, "250", "1")),
                        List.of(weighted),
                        options(null, PurchaseMode.SINGLE_CHAIN, true));
        assertNull(basket.items().getFirst().packageCount());
        assertEquals(bd("25.00"), basket.knownPurchaseCost());
    }

    @Test
    void smallSplitSavingsDoNotSendUserToAnotherChain() {
        var entries = List.of(selection(1, "100", "1"), selection(2, "100", "1"));
        var offers =
                List.of(
                        offer(1, 1, "100", "20", false, "STORE_OBSERVATION"),
                        offer(2, 1, "100", "60", false, "STORE_OBSERVATION"),
                        offer(1, 2, "100", "60", false, "STORE_OBSERVATION"),
                        offer(2, 2, "100", "20", false, "STORE_OBSERVATION"));
        var result =
                calculator.calculate(entries, offers, options(null, PurchaseMode.TWO_CHAINS, true));
        assertEquals(bd("80.00"), result.knownPurchaseCost());
        assertEquals(1, result.chains().size());
    }

    @Test
    void pantryIsSubtractedBeforePackagesAndNotValuedAsFreeFood() {
        var stock =
                new dev.jeefox.smartmeal.pantry.PantryModels.Snapshot(
                        1,
                        LocalDate.of(2035, 1, 1),
                        List.of(
                                new dev.jeefox.smartmeal.pantry.PantryModels.StockLot(
                                        UUID.randomUUID(),
                                        1L,
                                        bd("300"),
                                        LocalDate.of(2035, 1, 1),
                                        null,
                                        Instant.EPOCH)));
        var result =
                calculator.calculate(
                        List.of(selection(1, "1100", "1")),
                        List.of(offer(1, 1, "900", "90", false, "STORE_OBSERVATION")),
                        options(bd("100"), PurchaseMode.SINGLE_CHAIN, true),
                        stock);
        assertEquals(bd("90.00"), result.estimatedTotal());
        assertNull(result.consumedIngredientCost());
        assertEquals(1, result.items().getFirst().packageCount());
        assertEquals(0, bd("800").compareTo(result.items().getFirst().needToBuyG()));
        assertEquals(0, bd("100").compareTo(result.items().getFirst().remainderG()));
    }

    @Test
    void fullyCoveredProductNeedsNoStorePriceAndNoPurchaseChain() {
        var stock =
                new dev.jeefox.smartmeal.pantry.PantryModels.Snapshot(
                        1,
                        LocalDate.of(2035, 1, 1),
                        List.of(
                                new dev.jeefox.smartmeal.pantry.PantryModels.StockLot(
                                        UUID.randomUUID(),
                                        1L,
                                        bd("300"),
                                        LocalDate.of(2035, 1, 1),
                                        null,
                                        Instant.EPOCH)));
        var result =
                calculator.calculate(
                        List.of(selection(1, "200", "1")),
                        List.of(),
                        options(bd("100"), PurchaseMode.SINGLE_CHAIN, true),
                        stock);
        assertEquals(0, result.unknownProducts());
        assertEquals(0, result.pricedProducts());
        assertEquals(bd("0.00"), result.estimatedTotal());
        assertEquals(BudgetStatus.WITHIN, result.budgetStatus());
        assertTrue(result.chains().isEmpty());
        assertEquals("PANTRY", result.items().getFirst().applicability());
    }

    @Test
    void smallerNeedDoesNotClaimMoneySavingWithoutChangingPackageCount() {
        var stock =
                new dev.jeefox.smartmeal.pantry.PantryModels.Snapshot(
                        1,
                        LocalDate.of(2035, 1, 1),
                        List.of(
                                new dev.jeefox.smartmeal.pantry.PantryModels.StockLot(
                                        UUID.randomUUID(),
                                        1L,
                                        bd("300"),
                                        LocalDate.of(2035, 1, 1),
                                        null,
                                        Instant.EPOCH)));
        var entries = List.of(selection(1, "450", "1"));
        var offers = List.of(offer(1, 1, "900", "90", false, "STORE_OBSERVATION"));
        var options = options(null, PurchaseMode.SINGLE_CHAIN, true);
        assertEquals(
                calculator.calculate(entries, offers, options).estimatedTotal(),
                calculator.calculate(entries, offers, options, stock).estimatedTotal());
    }

    @Test
    void disabledPantryPreservesFullPurchaseDemand() {
        var stock =
                new dev.jeefox.smartmeal.pantry.PantryModels.Snapshot(
                        1,
                        LocalDate.of(2035, 1, 1),
                        List.of(
                                new dev.jeefox.smartmeal.pantry.PantryModels.StockLot(
                                        UUID.randomUUID(),
                                        1L,
                                        bd("300"),
                                        LocalDate.of(2035, 1, 1),
                                        null,
                                        Instant.EPOCH)));
        var o = options(null, PurchaseMode.SINGLE_CHAIN, true);
        var disabled =
                new Options(
                        o.from(),
                        o.to(),
                        o.budgetRub(),
                        o.allowedChainIds(),
                        o.city(),
                        o.purchaseMode(),
                        o.maxPreparationTimeByMeal(),
                        o.maxRecipeOccurrences(),
                        o.minRepeatGapDays(),
                        o.allowEstimatedPrices(),
                        o.minSplitSavingRub(),
                        o.seed(),
                        false);
        assertEquals(
                1,
                calculator
                        .calculate(List.of(selection(1, "200", "1")), List.of(), disabled, stock)
                        .unknownProducts());
    }

    @Test
    void threeThirdsDoNotBuyAnExtraPackageDueToIntermediateRounding() {
        var original = selection(1, "1000", "1");
        var r = original.recipe();
        var snapshot =
                new RecipeSnapshot(
                        r.recipeId(),
                        r.name(),
                        r.description(),
                        r.instructions(),
                        r.preparationTimeMin(),
                        3,
                        r.ingredients(),
                        r.nutritionTotal(),
                        r.nutritionPerServing());
        var entries = new ArrayList<Selection>();
        for (int i = 0; i < 3; i++)
            entries.add(
                    new Selection(
                            "third-" + i,
                            original.date(),
                            original.mealType(),
                            snapshot,
                            "hash",
                            BigDecimal.ONE,
                            false,
                            null,
                            List.of()));
        var result =
                calculator.calculate(
                        entries,
                        List.of(offer(1, 1, "1000", "100", false, "STORE_OBSERVATION")),
                        options(null, PurchaseMode.SINGLE_CHAIN, true));
        assertEquals(1, result.items().getFirst().packageCount());
        assertEquals(bd("100.00"), result.estimatedTotal());
    }

    @Test
    void decimalDivisionNoiseDoesNotCreateASecondPackage() {
        var original = selection(1, "2", "1");
        var r = original.recipe();
        var snapshot =
                new RecipeSnapshot(
                        r.recipeId(),
                        r.name(),
                        r.description(),
                        r.instructions(),
                        r.preparationTimeMin(),
                        3,
                        r.ingredients(),
                        r.nutritionTotal(),
                        r.nutritionPerServing());
        var entries = new ArrayList<Selection>();
        for (int i = 0; i < 3; i++)
            entries.add(
                    new Selection(
                            "third-" + i,
                            original.date(),
                            original.mealType(),
                            snapshot,
                            "hash",
                            BigDecimal.ONE,
                            false,
                            null,
                            List.of()));
        var result =
                calculator.calculate(
                        entries,
                        List.of(offer(1, 1, "2", "100", false, "STORE_OBSERVATION")),
                        options(null, PurchaseMode.SINGLE_CHAIN, true));
        assertEquals(1, result.items().getFirst().packageCount());
        assertEquals(0, result.items().getFirst().remainderG().signum());
    }
}
