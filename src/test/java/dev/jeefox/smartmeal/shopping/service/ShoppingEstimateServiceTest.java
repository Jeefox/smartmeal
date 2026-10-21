package dev.jeefox.smartmeal.shopping.service;

import dev.jeefox.smartmeal.exception.StoreInactiveException;
import dev.jeefox.smartmeal.exception.StoreNotFoundException;
import dev.jeefox.smartmeal.price.entity.PriceObservation;
import dev.jeefox.smartmeal.price.entity.PriceSource;
import dev.jeefox.smartmeal.price.repository.PriceObservationRepository;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.shopping.calculation.PackageCostCalculator;
import dev.jeefox.smartmeal.shopping.dto.*;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.entity.StoreProduct;
import dev.jeefox.smartmeal.store.repository.StoreProductRepository;
import dev.jeefox.smartmeal.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShoppingEstimateServiceTest {
    private final String email = "estimate@example.invalid";
    private final LocalDate date = LocalDate.of(2033, 1, 1);
    private StoreRepository stores;
    private StoreProductRepository packages;
    private PriceObservationRepository prices;
    private ShoppingListService shopping;
    private PackageCostCalculator calculator;
    private ShoppingEstimateService service;

    @BeforeEach
    void setUp() {
        stores = mock(StoreRepository.class);
        packages = mock(StoreProductRepository.class);
        prices = mock(PriceObservationRepository.class);
        shopping = mock(ShoppingListService.class);
        calculator = spy(new PackageCostCalculator());
        service = new ShoppingEstimateService(stores, packages, prices, shopping, calculator, mock(dev.jeefox.smartmeal.price.service.CataloguePriceReferenceService.class));
        Store store = mock(Store.class);
        when(store.isActive()).thenReturn(true);
        when(stores.findById(1L)).thenReturn(Optional.of(store));
    }

    @Test
    void preparedOffersAreReusedWithoutQueriesForEachMenu() {
        StoreProduct pack = pack(10L, 1L, 2000);
        when(packages.findByStore_IdAndActiveTrueAndProduct_IdIn(1L, List.of(1L))).thenReturn(List.of(pack));
        price(10L, "40.00");
        var prepared = service.prepare(1L, List.of(1L));
        var list = new ShoppingListResponse(date, date, false, List.of(need(1L, "1500")));
        assertEquals(new BigDecimal("40.00"), prepared.apply(list).knownPurchaseCost());
        assertEquals(new BigDecimal("40.00"), prepared.apply(list).knownPurchaseCost());
        verify(prices, times(1)).findFirstByStoreProduct_IdAndPromotionalFalseAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(10L);
        verify(packages, times(1)).findByStore_IdAndActiveTrueAndProduct_IdIn(1L, List.of(1L));
    }

    @Test
    void emptyListIsCompleteAndDoesNotQueryPackagesOrPrices() {
        needs(true);
        ShoppingEstimateResponse response = estimate();
        assertTrue(response.complete());
        assertTrue(response.containsDrafts());
        assertEquals(new BigDecimal("0.00"), response.knownPurchaseCost());
        assertTrue(response.items().isEmpty());
        verifyNoInteractions(packages, prices, calculator);
    }

    @Test
    void choosesLowestCheckoutCostRatherThanCheapestUnitOrPackagePrice() {
        needs(false, need(1L, "1000"));
        StoreProduct large = pack(10L, 1L, 800);
        StoreProduct small = pack(11L, 1L, 500);
        when(packages.findByStore_IdAndActiveTrueAndProduct_IdIn(1L, List.of(1L)))
                .thenReturn(List.of(large, small));
        price(10L, "90.00");
        price(11L, "70.00");
        ShoppingEstimateResponse response = estimate();
        ShoppingEstimateItemResponse item = response.items().getFirst();
        assertTrue(response.complete());
        assertEquals(11L, item.storeProductId());
        assertEquals(2, item.packageCount());
        assertEquals(new BigDecimal("140.00"), item.purchaseCost());
        assertEquals(new BigDecimal("140.00"), response.knownPurchaseCost());
        assertEquals(0, item.remainderG().signum());
        assertEquals(Instant.parse("2026-10-01T09:00:00Z"), item.priceObservedAt());
        assertEquals(PriceSource.MANUAL, item.priceSource());
        verify(prices, never()).findFirstByStoreProduct_IdAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(anyLong());
    }

    @Test
    void breaksEqualCostTieBySmallestPackageIdRegardlessOfInputOrder() {
        needs(false, need(1L, "1000"));
        List<StoreProduct> offers = List.of(pack(20L, 1L, 1000), pack(10L, 1L, 500));
        when(packages.findByStore_IdAndActiveTrueAndProduct_IdIn(1L, List.of(1L)))
                .thenReturn(offers);
        price(20L, "100.00");
        price(10L, "50.00");
        assertEquals(10L, estimate().items().getFirst().storeProductId());
    }

    @Test
    void missingPricesAreUncoveredNotFree() {
        needs(false, need(1L, "1000"));
        StoreProduct offer = pack(10L, 1L, 800);
        when(packages.findByStore_IdAndActiveTrueAndProduct_IdIn(1L, List.of(1L)))
                .thenReturn(List.of(offer));
        when(prices.findFirstByStoreProduct_IdAndPromotionalFalseAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(10L))
                .thenReturn(Optional.empty());
        ShoppingEstimateResponse response = estimate();
        assertFalse(response.complete());
        assertEquals(new BigDecimal("0.00"), response.knownPurchaseCost());
        assertNull(response.items().getFirst().purchaseCost());
        assertNull(response.items().getFirst().storeProductId());
        assertEquals(new BigDecimal("1000"), response.items().getFirst().requiredWeightG());
        verifyNoInteractions(calculator);
    }

    @Test
    void partialCoverageSumsOnlyKnownCostsAndKeepsAllNeedRows() {
        needs(true, need(1L, "1000"), need(2L, "200"));
        StoreProduct offer = pack(10L, 1L, 800);
        when(packages.findByStore_IdAndActiveTrueAndProduct_IdIn(1L, List.of(1L, 2L)))
                .thenReturn(List.of(offer));
        price(10L, "90.00");
        ShoppingEstimateResponse response = estimate();
        assertFalse(response.complete());
        assertTrue(response.containsDrafts());
        assertEquals(2, response.items().size());
        assertEquals(new BigDecimal("180.00"), response.knownPurchaseCost());
        assertNull(response.items().get(1).purchaseCost());
        assertEquals(2L, response.items().get(1).productId());
    }

    @Test
    void zeroRoundedNeedRemainsUncoveredEvenWhenOfferExists() {
        needs(false, need(1L, "0.00"));
        StoreProduct offer = pack(10L, 1L, 800);
        when(packages.findByStore_IdAndActiveTrueAndProduct_IdIn(1L, List.of(1L)))
                .thenReturn(List.of(offer));
        ShoppingEstimateResponse response = estimate();
        assertFalse(response.complete());
        assertNull(response.items().getFirst().packageCount());
        assertNull(response.items().getFirst().purchaseCost());
        verifyNoInteractions(prices, calculator);
    }

    @Test
    void rejectsInactiveStoreWithoutLoadingPackages() {
        Store inactive = mock(Store.class);
        when(stores.findById(1L)).thenReturn(Optional.of(inactive));
        assertThrows(StoreInactiveException.class, this::estimate);
        verify(shopping).calculate(email, date, date);
        verifyNoInteractions(packages, prices, calculator);
    }

    @Test
    void rejectsMissingStoreWithoutLoadingPackages() {
        when(stores.findById(1L)).thenReturn(Optional.empty());
        assertThrows(StoreNotFoundException.class, this::estimate);
        verify(shopping).calculate(email, date, date);
        verifyNoInteractions(packages, prices, calculator);
    }

    @Test
    void suppliedShoppingListDoesNotTriggerAnotherCalculation() {
        ShoppingListResponse supplied = new ShoppingListResponse(date, date.plusDays(1), true, List.of());
        ShoppingEstimateResponse response = service.estimate(supplied, 1L);
        assertEquals(supplied.from(), response.from());
        assertEquals(supplied.to(), response.to());
        assertTrue(response.containsDrafts());
        assertTrue(response.complete());
        verifyNoInteractions(shopping, packages, prices, calculator);
    }

    private ShoppingEstimateResponse estimate() {
        return service.estimate(email, date, date, 1L);
    }

    private void needs(boolean draft, ShoppingListItemResponse... items) {
        when(shopping.calculate(email, date, date))
                .thenReturn(new ShoppingListResponse(date, date, draft, List.of(items)));
    }

    private ShoppingListItemResponse need(Long id, String quantity) {
        return new ShoppingListItemResponse(id, "Продукт " + id, new BigDecimal(quantity));
    }

    private StoreProduct pack(Long id, Long productId, int weight) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        StoreProduct item = mock(StoreProduct.class);
        when(item.getProduct()).thenReturn(product);
        when(item.getId()).thenReturn(id);
        when(item.getName()).thenReturn("Упаковка " + id);
        when(item.getPackageWeightG()).thenReturn(weight);
        return item;
    }

    private void price(Long packageId, String value) {
        PriceObservation observation = mock(PriceObservation.class);
        when(observation.getPrice()).thenReturn(new BigDecimal(value));
        when(observation.getObservedAt()).thenReturn(Instant.parse("2026-10-01T09:00:00Z"));
        when(observation.getPriceSource()).thenReturn(PriceSource.MANUAL);
        when(prices.findFirstByStoreProduct_IdAndPromotionalFalseAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(packageId))
                .thenReturn(Optional.of(observation));
    }
}
