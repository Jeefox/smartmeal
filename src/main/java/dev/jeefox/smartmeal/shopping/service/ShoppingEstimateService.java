package dev.jeefox.smartmeal.shopping.service;

import dev.jeefox.smartmeal.exception.StoreInactiveException;
import dev.jeefox.smartmeal.exception.StoreNotFoundException;
import dev.jeefox.smartmeal.price.entity.PriceObservation;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ShoppingEstimateService {
    private static final BigDecimal ZERO_COST = new BigDecimal("0.00");
    private static final Comparator<ShoppingEstimateItemResponse> OFFER_ORDER =
            Comparator.comparing(ShoppingEstimateItemResponse::purchaseCost)
                    .thenComparing(ShoppingEstimateItemResponse::storeProductId);

    private final StoreRepository storeRepository;
    private final StoreProductRepository storeProductRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final ShoppingListService shoppingListService;
    private final PackageCostCalculator packageCostCalculator;

    public ShoppingEstimateService(StoreRepository storeRepository,
                                   StoreProductRepository storeProductRepository,
                                   PriceObservationRepository priceObservationRepository,
                                   ShoppingListService shoppingListService,
                                   PackageCostCalculator packageCostCalculator) {
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
        List<ShoppingEstimateItemResponse> items = new ArrayList<>();
        for (ShoppingListItemResponse need : shoppingList.items()) {
            List<StoreProduct> candidates = packagesByProduct.getOrDefault(need.productId(), List.of());
            items.add(estimateItem(need, candidates));
        }
        boolean complete = items.stream()
                .allMatch(item -> item.purchaseCost() != null);

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
                                                       List<StoreProduct> candidates) {
        // The current shopping list rounds grams; zero does not mean the product is free.
        if (need.quantityG().signum() <= 0) {
            return uncovered(need);
        }
        ShoppingEstimateItemResponse best = null;
        for (StoreProduct candidate : candidates) {
            PriceObservation observation = priceObservationRepository
                    .findFirstByStoreProduct_IdAndPromotionalFalseAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(candidate.getId())
                    .orElse(null);
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

    private ShoppingEstimateItemResponse uncovered(ShoppingListItemResponse need) {
        return new ShoppingEstimateItemResponse(need.productId(), need.productName(), need.quantityG(),
                null, null, null, null,
                null, null, null, null, null);
    }
}
