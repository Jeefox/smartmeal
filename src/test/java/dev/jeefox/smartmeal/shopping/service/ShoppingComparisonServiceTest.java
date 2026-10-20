package dev.jeefox.smartmeal.shopping.service;

import dev.jeefox.smartmeal.exception.InvalidShoppingComparisonException;
import dev.jeefox.smartmeal.exception.StoreNotFoundException;
import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateResponse;
import dev.jeefox.smartmeal.shopping.dto.ShoppingListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShoppingComparisonServiceTest {
    private final ShoppingEstimateService estimates = mock(ShoppingEstimateService.class);
    private final ShoppingListService shopping = mock(ShoppingListService.class);
    private final ShoppingComparisonService service = new ShoppingComparisonService(estimates, shopping);
    private final LocalDate date = LocalDate.of(2033, 1, 1);
    private final String email = "comparison@example.invalid";

    private ShoppingListResponse shoppingList;

    @BeforeEach
    void setUp() {
        shoppingList = new ShoppingListResponse(date, date, false, List.of());
        when(shopping.calculate(email, date, date)).thenReturn(shoppingList);
    }

    @Test
    void sortsCompleteFirstThenCostThenIdAndPropagatesDrafts() {
        shoppingList = new ShoppingListResponse(date, date, true, List.of());
        when(shopping.calculate(email, date, date)).thenReturn(shoppingList);
        offer(1L, false, "1.00", false);
        offer(2L, true, "200.00", false);
        offer(3L, true, "100.00", true);
        offer(4L, true, "100.00", false);
        var result = service.compare(email, date, date, List.of(1L, 2L, 4L, 3L));
        assertEquals(List.of(3L, 4L, 2L, 1L), result.shoppingEstimateResponseList().stream()
                .map(ShoppingEstimateResponse::storeId).toList());
        assertTrue(result.containsDrafts());
        assertEquals(date, result.from());
        assertEquals(date, result.to());
        verify(shopping, times(1)).calculate(email, date, date);
        for (long id = 1; id <= 4; id++) {
            verify(estimates).estimate(same(shoppingList), eq(id));
        }
        verify(estimates, never()).estimate(anyString(), any(), any(), anyLong());
    }

    @Test
    void deduplicatesBeforeCallingEstimate() {
        offer(1L, true, "0.00", false);
        var result = service.compare(email, date, date, List.of(1L, 1L, 1L));
        assertEquals(1, result.shoppingEstimateResponseList().size());
        assertFalse(result.containsDrafts());
        verify(estimates, times(1)).estimate(shoppingList, 1L);
    }

    @Test
    void rejectsInvalidListsBeforeCallingEstimate() {
        List<List<Long>> invalid = Arrays.asList(null, List.of(), Arrays.asList(1L, null),
                List.of(0L), List.of(-1L), LongStream.rangeClosed(1, 11).boxed().toList());
        for (List<Long> ids : invalid) {
            assertThrows(InvalidShoppingComparisonException.class,
                    () -> service.compare(email, date, date, ids));
        }
        verifyNoInteractions(estimates, shopping);
    }

    @Test
    void acceptsTenUniqueStoresEvenWithDuplicates() {
        List<Long> ids = new java.util.ArrayList<>(LongStream.rangeClosed(1, 10).boxed().toList());
        ids.add(1L);
        for (long id = 1; id <= 10; id++) {
            offer(id, true, "0.00", false);
        }
        assertEquals(10, service.compare(email, date, date, ids).shoppingEstimateResponseList().size());
        verify(shopping, times(1)).calculate(email, date, date);
    }

    @Test
    void propagatesMissingStoreInsteadOfReturningPartialComparison() {
        when(estimates.estimate(shoppingList, 1L)).thenThrow(new StoreNotFoundException(1L));
        assertThrows(StoreNotFoundException.class,
                () -> service.compare(email, date, date, List.of(1L, 2L)));
        verify(estimates, never()).estimate(shoppingList, 2L);
    }

    private void offer(Long id, boolean complete, String cost, boolean drafts) {
        when(estimates.estimate(shoppingList, id)).thenReturn(new ShoppingEstimateResponse(
                date, date, id, drafts, complete, new BigDecimal(cost), List.of()));
    }
}
