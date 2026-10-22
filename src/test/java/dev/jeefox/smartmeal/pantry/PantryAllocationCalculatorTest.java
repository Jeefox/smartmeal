package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.*;
import static dev.jeefox.smartmeal.pantry.PantryModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class PantryAllocationCalculatorTest {
    final LocalDate today = LocalDate.of(2030, 1, 1);

    BigDecimal g(String value) {
        return new BigDecimal(value);
    }

    StockLot lot(String amount, LocalDate expiry) {
        return new StockLot(UUID.randomUUID(), 1L, g(amount), today, expiry, Instant.EPOCH);
    }

    Demand demand(String entry, int day, String amount) {
        return new Demand(entry, 1L, "Крупа", today.plusDays(day), 0, g(amount));
    }

    Snapshot snapshot(StockLot... lots) {
        return new Snapshot(1, today, List.of(lots));
    }

    @Test
    void consumesSoonestExpiryBeforeUnknownAndKeepsSnapshotImmutable() {
        var later = lot("200", today.plusDays(10));
        var first = lot("100", today.plusDays(2));
        var unknown = lot("500", null);
        var stock = snapshot(unknown, later, first);
        var result = PantryAllocationCalculator.allocate(List.of(demand("a", 0, "250")), stock);
        assertEquals(first.id(), result.allocations().getFirst().lotId());
        assertEquals(later.id(), result.allocations().get(1).lotId());
        assertFalse(result.unknownExpiry());
        assertEquals(
                result, PantryAllocationCalculator.allocate(List.of(demand("a", 0, "250")), stock));
        assertEquals(g("100"), first.quantityG());
    }

    @Test
    void respectsEachMealDateAndDoesNotReuseAParty() {
        var l = lot("300", today.plusDays(1));
        var result =
                PantryAllocationCalculator.allocate(
                        List.of(demand("later", 2, "200"), demand("first", 0, "200")), snapshot(l));
        assertEquals(g("200"), result.coveredG().get(1L));
        assertEquals(g("200"), result.shortageG().get(1L));
        assertEquals("first", result.allocations().getFirst().entryId());
    }

    @Test
    void expiryIsInclusiveButPastExpiryNeverAvailable() {
        var result =
                PantryAllocationCalculator.allocate(
                        List.of(demand("a", 0, "300")),
                        snapshot(lot("100", today), lot("200", today.minusDays(1))));
        assertEquals(g("100"), result.coveredG().get(1L));
        assertEquals(g("200"), result.shortageG().get(1L));
    }

    @Test
    void unknownExpiryIsExplicit() {
        assertTrue(
                PantryAllocationCalculator.allocate(
                                List.of(demand("a", 0, "1")), snapshot(lot("10", null)))
                        .unknownExpiry());
    }

    @Test
    void doesNotUseFutureReceiptsOrWrongProducts() {
        var future =
                new StockLot(
                        UUID.randomUUID(), 1L, g("500"), today.plusDays(2), null, Instant.EPOCH);
        var other = new StockLot(UUID.randomUUID(), 2L, g("500"), today, null, Instant.EPOCH);
        var result =
                PantryAllocationCalculator.allocate(
                        List.of(demand("a", 0, "100")), snapshot(future, other));
        assertEquals(g("100"), result.shortageG().get(1L));
        assertTrue(result.allocations().isEmpty());
    }

    @Test
    void allocatesAcrossEntriesWithoutExceedingBalance() {
        var result =
                PantryAllocationCalculator.allocate(
                        List.of(demand("b", 1, "200"), demand("a", 0, "200")),
                        snapshot(lot("300", null)));
        assertEquals(g("300"), result.coveredG().get(1L));
        assertEquals(g("100"), result.shortageG().get(1L));
    }

    @Test
    void purchaseMassUsesEdibleYieldExactlyOnce() {
        var n = new NutritionValues(g("100"), g("1"), g("1"), g("1"));
        var recipe =
                new RecipeSnapshot(
                        UUID.randomUUID(),
                        "Банан",
                        null,
                        "Очистить",
                        1,
                        3,
                        List.of(
                                new RecipeIngredientSnapshot(
                                        1L, "Банан", g("150"), "Банан", g("0.6"))),
                        n,
                        n);
        var demand = calculate("a", today, 0, recipe, g("2"));
        assertEquals(
                0,
                g("166.6667")
                        .compareTo(
                                demand.getFirst()
                                        .quantityG()
                                        .setScale(4, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void equalExpiryHasDeterministicTieBreak() {
        var id1 = new UUID(0, 1);
        var id2 = new UUID(0, 2);
        var a = new StockLot(id1, 1L, g("10"), today, null, Instant.EPOCH);
        var b = new StockLot(id2, 1L, g("10"), today, null, Instant.EPOCH);
        assertEquals(
                id1,
                PantryAllocationCalculator.allocate(List.of(demand("e", 0, "1")), snapshot(b, a))
                        .allocations()
                        .getFirst()
                        .lotId());
    }

    @Test
    void invisibleDivisionResidueDoesNotCountAsUsableStock() {
        var result =
                PantryAllocationCalculator.allocate(
                        List.of(demand("a", 0, "100")), snapshot(lot("1E-28", null)));
        assertTrue(result.allocations().isEmpty());
        assertFalse(result.unknownExpiry());
        assertEquals(g("100"), result.shortageG().get(1L));
    }
}
