package dev.jeefox.smartmeal.shopping.service;

import dev.jeefox.smartmeal.exception.StoreInactiveException;
import dev.jeefox.smartmeal.exception.StoreNotFoundException;
import dev.jeefox.smartmeal.price.entity.PriceObservation;
import dev.jeefox.smartmeal.price.dto.CataloguePriceReference;
import dev.jeefox.smartmeal.price.service.CataloguePriceReferenceService;
import dev.jeefox.smartmeal.price.repository.PriceObservationRepository;
import dev.jeefox.smartmeal.shopping.calculation.PackageCostCalculator;
import dev.jeefox.smartmeal.shopping.calculation.PackageCostResult;
import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateItemResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListItemResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListResponse;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.entity.StoreProduct;
import dev.jeefox.smartmeal.store.repository.StoreProductRepository;
import dev.jeefox.smartmeal.store.repository.StoreRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ShoppingEstimateService {
    private static final BigDecimal ZERO_COST = new BigDecimal("0.00");
    private static final Comparator<ShoppingEstimateItemResponse> OFFER_ORDER =
            Comparator.comparing(ShoppingEstimateItemResponse::purchaseCost)
                    .thenComparing(ShoppingEstimateItemResponse::storeProductId);

    private final CataloguePriceReferenceService references;
    private final StoreRepository storeRepository;
    private final StoreProductRepository storeProductRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final ShoppingListService shoppingListService;
    private final PackageCostCalculator packageCostCalculator;

    public ShoppingEstimateService(StoreRepository storeRepository,
                                   StoreProductRepository storeProductRepository,
                                   PriceObservationRepository priceObservationRepository,
                                   ShoppingListService shoppingListService,
                                   PackageCostCalculator packageCostCalculator, CataloguePriceReferenceService references) {
        this.references = references;
        this.storeRepository = storeRepository;
        this.storeProductRepository = storeProductRepository;
        this.priceObservationRepository = priceObservationRepository;
        this.shoppingListService = shoppingListService;
        this.packageCostCalculator = packageCostCalculator;
    }

    @Transactional(readOnly = true)
    public ShoppingEstimateResponse estimate(String email, LocalDate from, LocalDate to, Long storeId) {
        ShoppingListResponse shoppingList =
                shoppingListService.calculate(email, from, to);

        return estimate(shoppingList, storeId);
    }

    @Transactional(readOnly = true)
    public ShoppingEstimateResponse estimate(
            ShoppingListResponse shoppingList,
            Long storeId
    ){
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        if (!store.isActive()) {
            throw new StoreInactiveException(storeId);
        }

        if (shoppingList.items().isEmpty()) {
            return new ShoppingEstimateResponse(
                    shoppingList.from(), shoppingList.to(),
                    storeId, shoppingList.containsDrafts(),
                    true, ZERO_COST, List.of());
        }

        List<Long> productIds = shoppingList.items().stream()
                .map(ShoppingListItemResponse::productId).toList();
        List<StoreProduct> packages = storeProductRepository
                .findByStore_IdAndActiveTrueAndProduct_IdIn(storeId, productIds);
        Map<Long, List<StoreProduct>> packagesByProduct = packages.stream()
                .collect(Collectors.groupingBy(item -> item.getProduct().getId()));
        var positiveNeeds = shoppingList.items().stream().filter(item -> item.quantityG().signum() > 0)
                .map(ShoppingListItemResponse::productId).collect(Collectors.toSet());
        return estimatePrepared(shoppingList, storeId, packagesByProduct,
                loadPrices(packages.stream().filter(item -> positiveNeeds.contains(item.getProduct().getId())).toList()), references.forProducts(productIds));
    }

    /** Loads offers once so generation can evaluate many menus without querying per combination. */
    @Transactional(readOnly = true)
    public Function<ShoppingListResponse, ShoppingEstimateResponse> prepare(Long storeId, List<Long> productIds) {
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new StoreNotFoundException(storeId));
        if (!store.isActive()) throw new StoreInactiveException(storeId);
        List<StoreProduct> packages = productIds.isEmpty() ? List.of() : storeProductRepository
                .findByStore_IdAndActiveTrueAndProduct_IdIn(storeId, productIds);
        Map<Long, List<StoreProduct>> grouped = packages.stream()
                .collect(Collectors.groupingBy(item -> item.getProduct().getId()));
        Map<Long, PriceObservation> prices = loadPrices(packages);
        var fallback = references.forProducts(productIds);
        return list -> estimatePrepared(list, storeId, grouped, prices, fallback);
    }

    private Map<Long, PriceObservation> loadPrices(List<StoreProduct> packages) {
        Map<Long, PriceObservation> prices = new HashMap<>();
        for (StoreProduct item : packages) {
            priceObservationRepository
                    .findFirstByStoreProduct_IdAndPromotionalFalseAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(item.getId())
                    .ifPresent(price -> prices.put(item.getId(), price));
        }
        return prices;
    }

    private ShoppingEstimateResponse estimatePrepared(ShoppingListResponse shoppingList, Long storeId,
            Map<Long, List<StoreProduct>> packagesByProduct, Map<Long, PriceObservation> prices,
            Map<Long, List<CataloguePriceReference>> fallback) {
        List<ShoppingEstimateItemResponse> items = new ArrayList<>();
        for (ShoppingListItemResponse need : shoppingList.items()) {
            List<StoreProduct> candidates = packagesByProduct.getOrDefault(need.productId(), List.of());
            var item = estimateItem(need, candidates, prices);
            if (item.purchaseCost() == null && need.quantityG().signum() > 0) {
                item = referenceItem(need, fallback.getOrDefault(need.productId(), List.of()));
            }
            items.add(item);
        }
        boolean complete = items.stream()
                .allMatch(item -> item.purchaseCost() != null && !item.referencePrice());

        BigDecimal knownPurchaseCost = items.stream()
                .map(ShoppingEstimateItemResponse::purchaseCost)
                .filter(Objects::nonNull)
                .reduce(ZERO_COST, BigDecimal::add);

        return new ShoppingEstimateResponse(
                shoppingList.from(),
                shoppingList.to(),
                storeId,
                shoppingList.containsDrafts(),
                complete,
                knownPurchaseCost,
                List.copyOf(items)
        );
    }

    private ShoppingEstimateItemResponse estimateItem(ShoppingListItemResponse need,
                                                       List<StoreProduct> candidates, Map<Long, PriceObservation> prices) {
        // The current shopping list rounds grams; zero does not mean the product is free.
        if (need.quantityG().signum() <= 0) {
            return uncovered(need);
        }
        ShoppingEstimateItemResponse best = null;
        for (StoreProduct candidate : candidates) {
            PriceObservation observation = prices.get(candidate.getId());
            if (observation == null) {
                continue;
            }
            PackageCostResult cost = packageCostCalculator.calculate(
                    need.quantityG(), candidate.getPackageWeightG(), observation.getPrice());
            ShoppingEstimateItemResponse offer = new ShoppingEstimateItemResponse(
                    need.productId(), need.productName(), need.quantityG(), candidate.getId(),
                    candidate.getName(), candidate.getPackageWeightG(), cost.packageCount(),
                    observation.getPrice(), cost.purchaseCost(), cost.remainderG(),
                    observation.getObservedAt(), observation.getPriceSource());
            if (best == null || OFFER_ORDER.compare(offer, best) < 0) {
                best = offer;
            }
        }
        return best == null ? uncovered(need) : best;
    }

    private ShoppingEstimateItemResponse referenceItem(ShoppingListItemResponse need, List<CataloguePriceReference> offers) {
        ShoppingEstimateItemResponse best = null;
        for (var offer : offers) {
            var cost = offer.soldByWeight()
                    ? new PackageCostResult(0, need.quantityG().multiply(offer.price())
                        .divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_UP), BigDecimal.ZERO)
                    : packageCostCalculator.calculate(need.quantityG(), offer.packageWeightG(), offer.price());
            var candidate = new ShoppingEstimateItemResponse(need.productId(), need.productName(), need.quantityG(),
                    null, offer.name(), offer.packageWeightG(), offer.soldByWeight() ? null : cost.packageCount(), offer.price(), cost.purchaseCost(),
                    cost.remainderG(), offer.retrievedAt(), null, true, offer.sourceUrl(), offer.sourceName(), offer.sourceScope());
            if (best == null || candidate.purchaseCost().compareTo(best.purchaseCost()) < 0) best = candidate;
        }
        return best == null ? uncovered(need) : best;
    }

    private ShoppingEstimateItemResponse uncovered(ShoppingListItemResponse need) {
        return new ShoppingEstimateItemResponse(need.productId(), need.productName(), need.quantityG(),
                null, null, null, null,
                null, null, null, null, null);
    }
}
