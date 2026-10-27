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

    public static Result allocatePrioritized(
            List<Demand> demands, Snapshot snapshot, Set<String> priority) {
        var first =
                allocate(
                        demands.stream().filter(d -> priority.contains(d.entryId())).toList(),
                        snapshot);
        var used = new HashMap<UUID, BigDecimal>();
        first.allocations().forEach(a -> used.merge(a.lotId(), a.quantityG(), BigDecimal::add));
        var remainder =
                new Snapshot(
                        snapshot.revision(),
                        snapshot.today(),
                        snapshot.lots().stream()
                                .map(
                                        l ->
                                                new StockLot(
                                                        l.id(),
                                                        l.productId(),
                                                        l.quantityG()
                                                                .subtract(
                                                                        used.getOrDefault(
                                                                                l.id(),
                                                                                BigDecimal.ZERO)),
                                                        l.receivedOn(),
                                                        l.expiresOn(),
                                                        l.createdAt()))
                                .toList());
        var second =
                allocate(
                        demands.stream().filter(d -> !priority.contains(d.entryId())).toList(),
                        remainder);
        var allocations = new ArrayList<>(first.allocations());
        allocations.addAll(second.allocations());
        var covered = new TreeMap<>(first.coveredG());
        second.coveredG().forEach((p, q) -> covered.merge(p, q, BigDecimal::add));
        var shortage = new TreeMap<>(first.shortageG());
        second.shortageG().forEach((p, q) -> shortage.merge(p, q, BigDecimal::add));
        return new Result(
                List.copyOf(allocations),
                Map.copyOf(covered),
                Map.copyOf(shortage),
                first.unknownExpiry() || second.unknownExpiry());
    }

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
