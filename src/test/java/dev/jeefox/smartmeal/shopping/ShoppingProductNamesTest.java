package dev.jeefox.smartmeal.shopping;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShoppingProductNamesTest {
    @Test
    void familiarNamesReplaceOnlyKnownLegacyLabels() {
        assertEquals("Бананы", ShoppingProductNames.display("Банан без кожуры"));
        assertEquals("Рис", ShoppingProductNames.display("Рис сухой"));
        assertEquals("Макароны", ShoppingProductNames.display("Макароны сухие"));
        assertEquals("Гречка", ShoppingProductNames.display("Гречневая крупа"));
        assertEquals("Перловка", ShoppingProductNames.display("Крупа перловая сухая"));
        assertEquals(
                "Фасоль консервированная",
                ShoppingProductNames.display("Фасоль консервированная без заливки"));
        assertEquals(
                "Куриное бедро без кожи",
                ShoppingProductNames.display("Куриное бедро без кожи сырое"));
        assertEquals(
                "Фасоль стручковая замороженная",
                ShoppingProductNames.display("Фасоль стручковая замороженная"));
        assertEquals("Мой рис для плова", ShoppingProductNames.display("Мой рис для плова"));
        assertNull(ShoppingProductNames.display(null));
    }

    @Test
    void oldShoppingResponsesHavePlainNamesWithoutChangingQuantities() {
        var q = new java.math.BigDecimal("150");
        var item =
                new dev.jeefox.smartmeal.shopping.dto.ShoppingListItemResponse(
                        1L, "Банан без кожуры", q);
        assertEquals("Бананы", item.productName());
        assertEquals(0, q.compareTo(item.quantityG()));
    }

    @Test
    void persistedBasketLabelsAreNormalizedOnReadButIngredientSnapshotsAreUntouched() {
        var json = new tools.jackson.databind.ObjectMapper();
        var basket =
                json.readValue(
                        "{\"productId\":1,\"productName\":\"Макароны"
                            + " сухие\",\"requiredG\":125,\"purchasedG\":500,\"estimated\":false}",
                        dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.BasketItem
                                .class);
        assertEquals("Макароны", basket.productName());
        assertEquals(0, new java.math.BigDecimal("125").compareTo(basket.requiredG()));
        assertEquals(0, new java.math.BigDecimal("500").compareTo(basket.purchasedG()));
        var estimate =
                json.readValue(
                        "{\"productId\":1,\"productName\":\"Рис сухой\",\"requiredWeightG\":150,\"referencePrice\":false}",
                        dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateItemResponse.class);
        assertEquals("Рис", estimate.productName());
        var snapshot =
                new dev.jeefox.smartmeal.mealplan.snapshot.RecipeIngredientSnapshot(
                        1L, "Банан без кожуры", java.math.BigDecimal.TEN);
        assertEquals("Банан без кожуры", snapshot.productName());
        assertEquals(java.math.BigDecimal.ONE, snapshot.edibleYield());
    }
}
