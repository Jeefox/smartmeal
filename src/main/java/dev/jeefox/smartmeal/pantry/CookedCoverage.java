package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Prepared components replace, rather than duplicate, raw demands. Planned ones reserve on cooking
 * day.
 */
@Component
public class CookedCoverage {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public CookedCoverage(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Demand> adjust(UUID user, List<Demand> original, LocalDate today) {
        if (original.isEmpty()) return original;
        var ids = original.stream().map(Demand::entryId).map(UUID::fromString).distinct().toList();
        var rows =
                jdbc.queryForList(
                        "select a.entry_id,a.raw_demands,b.status,b.cook_on,b.use_by from"
                            + " cooking_allocations a join cooking_batches b on b.id=a.batch_id"
                            + " where b.user_id=:u and a.entry_id in(:ids) and a.status='ACTIVE'"
                            + " and b.status in ('PLANNED','COOKED')",
                        Map.of("u", user, "ids", ids));
        if (rows.isEmpty()) return original;
        var subtract = new HashMap<String, BigDecimal>();
        var planned = new ArrayList<Demand>();
        for (var row : rows) {
            LocalDate useBy = ((java.sql.Date) row.get("use_by")).toLocalDate();
            if (row.get("status").equals("COOKED") && useBy.isBefore(today)) continue;
            for (var d : json.readValue(row.get("raw_demands").toString(), Demand[].class)) {
                subtract.merge(d.entryId() + ":" + d.productId(), d.quantityG(), BigDecimal::add);
                if (row.get("status").equals("PLANNED"))
                    planned.add(
                            new Demand(
                                    d.entryId(),
                                    d.productId(),
                                    d.name(),
                                    ((java.sql.Date) row.get("cook_on")).toLocalDate(),
                                    d.order(),
                                    d.quantityG()));
            }
        }
        var result = new ArrayList<Demand>();
        for (var d : original) {
            var key = d.entryId() + ":" + d.productId();
            var available = subtract.getOrDefault(key, BigDecimal.ZERO);
            var take = available.min(d.quantityG());
            subtract.put(key, available.subtract(take));
            var left = d.quantityG().subtract(take);
            if (left.signum() > 0)
                result.add(
                        new Demand(
                                d.entryId(), d.productId(), d.name(), d.date(), d.order(), left));
        }
        result.addAll(planned);
        return List.copyOf(result);
    }

    public List<Demand> forPeriod(
            UUID user, List<Demand> raw, LocalDate today, LocalDate from, LocalDate to) {
        var result = new ArrayList<>(adjust(user, raw, today));
        var seen = new HashSet<>(raw.stream().map(Demand::entryId).toList());
        var rows =
                jdbc.queryForList(
                        "select a.entry_id,a.raw_demands,b.cook_on from cooking_allocations a join"
                            + " cooking_batches b on b.id=a.batch_id where b.user_id=:u and"
                            + " a.status='ACTIVE' and b.status='PLANNED' and b.cook_on between :f"
                            + " and :t",
                        Map.of("u", user, "f", from, "t", to));
        for (var row : rows)
            if (!seen.contains(row.get("entry_id").toString()))
                for (var d : json.readValue(row.get("raw_demands").toString(), Demand[].class))
                    result.add(
                            new Demand(
                                    d.entryId(),
                                    d.productId(),
                                    d.name(),
                                    ((java.sql.Date) row.get("cook_on")).toLocalDate(),
                                    d.order(),
                                    d.quantityG()));
        return List.copyOf(result);
    }

    public void requireReady(UUID user, UUID entry, LocalDate today) {
        var rows =
                jdbc.queryForList(
                        "select b.status,b.use_by from cooking_allocations a join cooking_batches b"
                                + " on b.id=a.batch_id where b.user_id=:u and a.entry_id=:e and"
                                + " a.status='ACTIVE'",
                        Map.of("u", user, "e", entry));
        for (var r : rows) {
            if (!r.get("status").equals("COOKED"))
                throw PantryException.conflict(
                        "BATCH_NOT_PREPARED",
                        "Сначала приготовьте запланированную общую партию в разделе «Готовка»");
            if (((java.sql.Date) r.get("use_by")).toLocalDate().isBefore(today))
                throw PantryException.conflict(
                        "COOKED_FOOD_EXPIRED",
                        "У партии истекла указанная дата хранения. Спишите остаток и пересчитайте"
                                + " приготовление.");
        }
    }

    public void requireNotConsumed(UUID user, UUID entry) {
        if (jdbc.queryForObject(
                        "select count(*) from cooking_allocations a join cooking_batches b on"
                                + " b.id=a.batch_id where b.user_id=:u and a.entry_id=:e and"
                                + " a.status='CONSUMED'",
                        Map.of("u", user, "e", entry),
                        Integer.class)
                > 0)
            throw PantryException.conflict(
                    "FOOD_ALREADY_CONSUMED",
                    "Готовая еда уже отмечена съеденной; вернуть сырые ингредиенты нельзя");
    }

    public void release(UUID user, List<UUID> entries) {
        if (entries.isEmpty()) return;
        var p = Map.of("u", user, "ids", entries);
        jdbc.update(
                "update cooking_batches set status='CANCELLED' where user_id=:u and"
                    + " status='PLANNED' and id in(select batch_id from cooking_allocations where"
                    + " entry_id in(:ids) and status='ACTIVE')",
                p);
        jdbc.update(
                "update cooking_allocations a set status='RELEASED' from cooking_batches b where"
                        + " b.id=a.batch_id and b.user_id=:u and a.status='ACTIVE' and (a.entry_id"
                        + " in(:ids) or b.status='CANCELLED')",
                p);
    }
}
