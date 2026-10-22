package dev.jeefox.smartmeal.shopping.service;

import dev.jeefox.smartmeal.exception.InvalidShoppingComparisonException;
import dev.jeefox.smartmeal.shopping.dto.ShoppingComparisonResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
public class ShoppingComparisonService {

    private final ShoppingEstimateService shoppingEstimateService;
    private final ShoppingListService shoppingListService;

    public ShoppingComparisonService(
            ShoppingEstimateService shoppingEstimateService,
            ShoppingListService shoppingListService) {
        this.shoppingEstimateService = shoppingEstimateService;
        this.shoppingListService = shoppingListService;
    }

    @Transactional
    public ShoppingComparisonResponse compare(
            String email, LocalDate from, LocalDate to, List<Long> storeIds) {

        if (storeIds == null) {
            throw new InvalidShoppingComparisonException("Список магазинов не должен быть null");
        }
        List<Long> uniqueList = new ArrayList<>(new LinkedHashSet<>(storeIds));
        if (uniqueList.isEmpty() || uniqueList.size() > 10) {
            throw new InvalidShoppingComparisonException(
                    "Выберите от 1 до 10 уникальных магазинов");
        }
        if (uniqueList.stream().anyMatch((id -> id == null || id <= 0))) {
            throw new InvalidShoppingComparisonException(
                    "ID магазинов должны быть заполнены и положительны");
        }
        ShoppingListResponse shoppingList = shoppingListService.calculate(email, from, to);

        List<ShoppingEstimateResponse> storeEstimateResponses = new ArrayList<>();

        for (Long uniqueId : uniqueList) {
            storeEstimateResponses.add(shoppingEstimateService.estimate(shoppingList, uniqueId));
        }

        storeEstimateResponses.sort(
                Comparator.comparing(ShoppingEstimateResponse::complete)
                        .reversed()
                        .thenComparing(ShoppingEstimateResponse::knownPurchaseCost)
                        .thenComparing(ShoppingEstimateResponse::storeId));
        boolean containsDrafts = shoppingList.containsDrafts();

        return new ShoppingComparisonResponse(
                from, to, containsDrafts, List.copyOf(storeEstimateResponses));
    }
}
