package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import org.springframework.stereotype.Component;

import java.math.*;
import java.util.*;

/** All callers aggregate unrounded raw edible quantities before buying packages. */
@Component
public class PurchaseBasketCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public Basket calculate(List<Selection> entries, List<Offer> offers, Options options) {
        return calculate(
                entries,
                offers,
                options,
                dev.jeefox.smartmeal.pantry.PantryModels.Snapshot.empty());
    }

    public Basket calculate(
            List<Selection> entries,
            List<Offer> offers,
            Options options,
            dev.jeefox.smartmeal.pantry.PantryModels.Snapshot stock) {
        var demands =
                new ArrayList<dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.Demand>();
        for (var e : entries)
            demands.addAll(
                    dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.calculate(
                            e.id(), e.date(), e.mealType().ordinal(), e.recipe(), e.portions()));
        return calculateDemands(entries, offers, options, stock, demands);
    }

    public Basket calculateDemands(
            List<Selection> entries,
            List<Offer> offers,
            Options options,
            dev.jeefox.smartmeal.pantry.PantryModels.Snapshot stock,
            List<dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.Demand> demands) {
        var coverage =
                options.usePantry()
                        ? dev.jeefox.smartmeal.pantry.PantryAllocationCalculator.allocate(
                                        demands, stock)
                                .coveredG()
                        : Map.<Long, BigDecimal>of();
        return calculateCovered(entries, offers, options, demands, coverage);
    }

    public Basket calculateCovered(
            List<Selection> entries,
            List<Offer> offers,
            Options options,
            List<dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.Demand> demands,
            Map<Long, BigDecimal> coverage) {
        Map<Long, BigDecimal> quantities = new TreeMap<>();
        for (var d : demands) quantities.merge(d.productId(), d.quantityG(), BigDecimal::add);
        Map<Long, String> names = new HashMap<>();
        demands.forEach(d -> names.putIfAbsent(d.productId(), d.name()));
        Map<Long, Selection> namingEntries = new HashMap<>();
        for (Selection entry : entries) {
            for (var ingredient : entry.recipe().ingredients()) {
                Selection old = namingEntries.get(ingredient.productId());
                String oldName = names.get(ingredient.productId());
                if (old == null
                        || entry.date().isAfter(old.date())
                        || (entry.date().equals(old.date())
                                && ingredient.shoppingName().compareTo(oldName) < 0)) {
                    names.put(ingredient.productId(), ingredient.shoppingName());
                    namingEntries.put(ingredient.productId(), entry);
                }
            }
        }
        List<Basket> single =
                options.allowedChainIds().stream()
                        .map(id -> basket(quantities, coverage, names, offers, options, Set.of(id)))
                        .toList();
        Comparator<Basket> order =
                Comparator.comparingInt(Basket::unknownProducts)
                        .thenComparing(Basket::knownPurchaseCost)
                        .thenComparingInt(Basket::estimatedProducts)
                        .thenComparing(b -> b.chains().toString());
        Basket bestSingle = single.stream().min(order).orElseThrow();
        if (options.purchaseMode() == PurchaseMode.SINGLE_CHAIN) return bestSingle;
        Basket best = bestSingle;
        for (int i = 0; i < options.allowedChainIds().size(); i++) {
            for (int j = i + 1; j < options.allowedChainIds().size(); j++) {
                Basket candidate =
                        basket(
                                quantities,
                                coverage,
                                names,
                                offers,
                                options,
                                Set.of(
                                        options.allowedChainIds().get(i),
                                        options.allowedChainIds().get(j)));
                boolean betterCoverage = candidate.unknownProducts() < bestSingle.unknownProducts();
                BigDecimal saving =
                        bestSingle.knownPurchaseCost().subtract(candidate.knownPurchaseCost());
                if ((betterCoverage
                                || (candidate.unknownProducts() == 0
                                        && bestSingle.unknownProducts() == 0
                                        && saving.compareTo(options.minSplitSavingRub()) >= 0))
                        && order.compare(candidate, best) < 0) {
                    best =
                            new Basket(
                                    candidate.items(),
                                    candidate.knownPurchaseCost(),
                                    candidate.estimatedTotal(),
                                    candidate.consumedIngredientCost(),
                                    candidate.pricedProducts(),
                                    candidate.estimatedProducts(),
                                    candidate.unknownProducts(),
                                    candidate.priceQuality(),
                                    candidate.budgetStatus(),
                                    candidate.chains(),
                                    candidate.unknownProducts() == 0
                                                    && bestSingle.unknownProducts() == 0
                                            ? saving
                                            : null);
                }
            }
        }
        return best;
    }

    private Basket basket(
            Map<Long, BigDecimal> quantities,
            Map<Long, BigDecimal> coverage,
            Map<Long, String> names,
            List<Offer> offers,
            Options options,
            Set<Long> chains) {
        Map<Long, List<Offer>> grouped = new HashMap<>();
        for (Offer offer : offers) {
            if (chains.contains(offer.chainId())
                    && (options.allowEstimatedPrices() || !offer.estimated())) {
                grouped.computeIfAbsent(offer.productId(), ignored -> new ArrayList<>()).add(offer);
            }
        }
        List<BasketItem> items = new ArrayList<>();
        BigDecimal total = ZERO, consumed = ZERO;
        int unknown = 0, estimated = 0;
        Map<Long, String> usedChains = new TreeMap<>();
        for (var quantity : quantities.entrySet()) {
            long id = quantity.getKey();
            BigDecimal home = coverage.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal need =
                    dev.jeefox.smartmeal.shopping.calculation.WeightPrecision.shortage(
                            quantity.getValue(), home);
            BasketItem best = null;
            for (Offer offer :
                    need.signum() == 0 ? List.<Offer>of() : grouped.getOrDefault(id, List.of())) {
                BasketItem item = item(id, names.get(id), need, offer);
                // A same-chain catalogue is preferable to a cheaper cross-chain reference.
                if (best == null
                        || offerRank(item) < offerRank(best)
                        || (offerRank(item) == offerRank(best)
                                && (item.purchaseCost().compareTo(best.purchaseCost()) < 0
                                        || (item.purchaseCost().compareTo(best.purchaseCost()) == 0
                                                && item.offerKey().compareTo(best.offerKey())
                                                        < 0)))) best = item;
            }
            if (need.signum() == 0) {
                best =
                        new BasketItem(
                                id,
                                names.get(id),
                                need,
                                ZERO,
                                ZERO,
                                0,
                                null,
                                null,
                                ZERO,
                                null,
                                null,
                                null,
                                null,
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "PANTRY");
            } else if (best == null) {
                unknown++;
                best =
                        new BasketItem(
                                id,
                                names.get(id),
                                need,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "UNKNOWN");
            } else {
                total = total.add(best.purchaseCost());
                consumed =
                        consumed.add(
                                need.multiply(best.packagePrice())
                                        .divide(best.packageWeightG(), MC));
                if (best.estimated()) estimated++;
                usedChains.put(best.chainId(), best.chainName());
            }
            best =
                    new BasketItem(
                            best.productId(),
                            best.productName(),
                            quantity.getValue(),
                            best.purchasedG(),
                            best.remainderG(),
                            best.packageCount(),
                            best.packageWeightG(),
                            best.packagePrice(),
                            best.purchaseCost(),
                            best.chainId(),
                            best.chainName(),
                            best.offerKey(),
                            best.offerName(),
                            best.estimated(),
                            best.sourceName(),
                            best.sourceScope(),
                            best.sourceUrl(),
                            best.observedAt(),
                            best.expiresAt(),
                            best.applicability(),
                            home,
                            need);
            items.add(best);
        }
        items.sort(
                Comparator.comparing(BasketItem::productName).thenComparing(BasketItem::productId));
        BudgetStatus status =
                options.budgetRub() == null
                        ? BudgetStatus.NOT_SET
                        : total.compareTo(options.budgetRub()) > 0
                                ? BudgetStatus.OVER
                                : unknown > 0 ? BudgetStatus.UNKNOWN : BudgetStatus.WITHIN;
        return new Basket(
                List.copyOf(items),
                total,
                unknown == 0 ? total : null,
                unknown > 0 || coverage.values().stream().anyMatch(v -> v.signum() > 0)
                        ? null
                        : consumed.setScale(2, RoundingMode.HALF_UP),
                (int)
                        items.stream()
                                .filter(
                                        i ->
                                                i.needToBuyG().signum() > 0
                                                        && i.purchaseCost() != null)
                                .count(),
                estimated,
                unknown,
                unknown > 0
                        ? PriceQuality.PARTIAL
                        : estimated > 0
                                ? PriceQuality.COMPLETE_ESTIMATED
                                : PriceQuality.COMPLETE_OBSERVED,
                status,
                usedChains.entrySet().stream()
                        .map(e -> new Chain(e.getKey(), e.getValue()))
                        .toList(),
                ZERO);
    }

    private int offerRank(BasketItem item) {
        return "REFERENCE_ONLY".equals(item.applicability()) ? 2 : item.estimated() ? 1 : 0;
    }

    private BasketItem item(Long id, String name, BigDecimal need, Offer offer) {
        int count =
                offer.soldByWeight()
                        ? 0
                        : dev.jeefox.smartmeal.shopping.calculation.WeightPrecision.packages(
                                need, offer.packageWeightG());
        BigDecimal purchased =
                offer.soldByWeight()
                        ? need
                        : offer.packageWeightG().multiply(BigDecimal.valueOf(count));
        BigDecimal price =
                purchased
                        .multiply(offer.packagePrice())
                        .divide(offer.packageWeightG(), 2, RoundingMode.HALF_UP);
        return new BasketItem(
                id,
                name,
                need,
                purchased,
                purchased.subtract(need).max(BigDecimal.ZERO),
                offer.soldByWeight() ? null : count,
                offer.packageWeightG(),
                offer.packagePrice(),
                price,
                offer.chainId(),
                offer.chainName(),
                offer.key(),
                offer.name(),
                offer.estimated(),
                offer.sourceName(),
                offer.sourceScope(),
                offer.sourceUrl(),
                offer.observedAt(),
                offer.expiresAt(),
                offer.applicability());
    }
}
