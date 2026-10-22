package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.*;
import static dev.jeefox.smartmeal.pantry.PantryModels.*;

import java.math.BigDecimal;
import java.util.*;

/** Pure FEFO allocation: every search candidate starts with an independent balance map. */
public final class PantryAllocationCalculator {
    private PantryAllocationCalculator() {}

    public record Allocation(String entryId, Long productId, UUID lotId, BigDecimal quantityG) {}

    public record Result(
            List<Allocation> allocations,
            Map<Long, BigDecimal> coveredG,
            Map<Long, BigDecimal> shortageG,
            boolean unknownExpiry) {}

    public static Result allocate(List<Demand> demands, Snapshot snapshot) {
        var lots =
                snapshot.lots().stream()
                        .sorted(
                                Comparator.comparing(
                                                StockLot::expiresOn,
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .thenComparing(StockLot::receivedOn)
                                        .thenComparing(StockLot::createdAt)
                                        .thenComparing(StockLot::id))
                        .toList();
        Map<UUID, BigDecimal> left = new HashMap<>();
        lots.forEach(l -> left.put(l.id(), l.quantityG()));
        var ordered =
                demands.stream()
                        .sorted(
                                Comparator.comparing(Demand::date)
                                        .thenComparingInt(Demand::order)
                                        .thenComparing(Demand::entryId)
                                        .thenComparing(Demand::productId))
                        .toList();
        List<Allocation> allocations = new ArrayList<>();
        Map<Long, BigDecimal> covered = new TreeMap<>(), shortage = new TreeMap<>();
        boolean unknown = false;
        for (var d : ordered) {
            BigDecimal need = d.quantityG();
            for (var l : lots) {
                if (!l.productId().equals(d.productId())
                        || l.receivedOn().isAfter(d.date())
                        || (l.expiresOn() != null && l.expiresOn().isBefore(d.date()))
                        || (l.expiresOn() != null && l.expiresOn().isBefore(snapshot.today())))
                    continue;
                BigDecimal take = left.get(l.id()).min(need);
                if (take.signum() <= 0
                        || dev.jeefox.smartmeal.shopping.calculation.WeightPrecision
                                .isArithmeticNoise(take, d.quantityG())) continue;
                allocations.add(new Allocation(d.entryId(), d.productId(), l.id(), take));
                covered.merge(d.productId(), take, BigDecimal::add);
                left.put(l.id(), left.get(l.id()).subtract(take));
                need = need.subtract(take);
                unknown |= l.expiresOn() == null;
                if (need.signum() == 0) break;
            }
            if (need.signum() > 0
                    && !dev.jeefox.smartmeal.shopping.calculation.WeightPrecision.isArithmeticNoise(
                            need, d.quantityG()))
                shortage.merge(d.productId(), need, BigDecimal::add);
        }
        return new Result(
                List.copyOf(allocations), Map.copyOf(covered), Map.copyOf(shortage), unknown);
    }
}
