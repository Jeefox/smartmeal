package dev.jeefox.smartmeal.pantry;

import jakarta.validation.constraints.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

@Service
@Transactional
public class CookingCorrections {
    public record Weight(
            @Min(0) long expectedRevision,
            @NotNull @Positive @Digits(integer = 6, fraction = 2) BigDecimal actualYieldG,
            @NotBlank @Size(max = 300) String reason) {}

    public record Reversal(
            @Min(0) long expectedRevision, @NotBlank @Size(max = 300) String reason) {}

    public record Change(
            UUID id,
            UUID batchId,
            UUID entryId,
            String kind,
            String reason,
            java.time.Instant createdAt,
            Map<String, Object> before,
            Map<String, Object> after) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final PantryService pantry;
    private final ObjectMapper json;

    public CookingCorrections(
            NamedParameterJdbcTemplate jdbc, PantryService pantry, ObjectMapper json) {
        this.jdbc = jdbc;
        this.pantry = pantry;
        this.json = json;
    }

    private Map<String, Object> owned(UUID user, UUID batch) {
        var rows =
                jdbc.queryForList(
                        "select * from cooking_batches where id=:b and user_id=:u",
                        Map.of("b", batch, "u", user));
        if (rows.isEmpty()) throw PantryException.missing();
        return rows.getFirst();
    }

    private String reason(String value) {
        if (value == null || value.isBlank() || value.length() > 300)
            throw PantryException.conflict(
                    "CORRECTION_REASON_REQUIRED", "Укажите причину исправления (до 300 символов)");
        return value.strip();
    }

    private void audit(
            PantryService.Work w,
            UUID batch,
            UUID entry,
            String kind,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after) {
        var p = new HashMap<String, Object>();
        p.put("id", UUID.randomUUID());
        p.put("u", w.user());
        p.put("op", w.operation());
        p.put("b", batch);
        p.put("e", entry);
        p.put("k", kind);
        p.put("r", reason);
        p.put("before", json.writeValueAsString(before));
        p.put("after", json.writeValueAsString(after));
        jdbc.update(
                "insert into"
                    + " cooking_corrections(id,user_id,operation_id,batch_id,entry_id,kind,reason,before_payload,after_payload,created_at)"
                    + " values(:id,:u,:op,:b,:e,:k,:r,cast(:before as jsonb),cast(:after as"
                    + " jsonb),clock_timestamp())",
                p);
    }

    public PantryModels.Action weight(String email, UUID key, UUID batch, Weight r) {
        return pantry.execute(
                email,
                key,
                "BATCH_YIELD_CORRECTION:" + batch,
                r,
                r.expectedRevision(),
                w -> {
                    var b = owned(w.user(), batch);
                    if (!"COOKED".equals(b.get("status")))
                        throw PantryException.conflict(
                                "BATCH_STATE_CONFLICT",
                                "Исправить вес можно только у приготовленной, не списанной партии");
                    var weight = r.actualYieldG();
                    if (weight == null
                            || weight.signum() <= 0
                            || weight.compareTo(new BigDecimal("999999.99")) > 0
                            || weight.stripTrailingZeros().scale() > 2)
                        throw PantryException.conflict(
                                "INVALID_COOKED_WEIGHT",
                                "Укажите положительный вес с точностью до сотых грамма");
                    var old = (BigDecimal) b.get("actual_yield_g");
                    if (old.compareTo(weight) == 0)
                        throw PantryException.conflict("WEIGHT_UNCHANGED", "Вес не изменился");
                    var why = reason(r.reason());
                    jdbc.update(
                            "update cooking_batches set actual_yield_g=:g where id=:b",
                            Map.of("g", weight, "b", batch));
                    audit(
                            w,
                            batch,
                            null,
                            "YIELD",
                            why,
                            Map.of("actualYieldG", old),
                            Map.of("actualYieldG", weight));
                    return List.of();
                });
    }

    public PantryModels.Action undoConsumption(String email, UUID key, UUID entry, Reversal r) {
        return pantry.execute(
                email,
                key,
                "COOKED_CONSUMPTION_REVERSAL:" + entry,
                r,
                r.expectedRevision(),
                w -> {
                    var plans =
                            jdbc.queryForList(
                                    "select m.status from meal_plan_entries e join meal_plans m on"
                                        + " m.id=e.meal_plan_id where e.id=:e and m.user_id=:u",
                                    Map.of("e", entry, "u", w.user()));
                    if (plans.isEmpty()) throw PantryException.missing();
                    var links =
                            jdbc.queryForList(
                                    "select a.id,a.batch_id,a.consumed_at,b.status from"
                                        + " cooking_allocations a join cooking_batches b on"
                                        + " b.id=a.batch_id where a.entry_id=:e and b.user_id=:u"
                                        + " and a.status='CONSUMED'",
                                    Map.of("e", entry, "u", w.user()));
                    if (links.isEmpty())
                        throw PantryException.conflict(
                                "NOT_CONSUMED", "Отметка «съедено» уже отсутствует");
                    if (links.stream().anyMatch(l -> !"COOKED".equals(l.get("status"))))
                        throw PantryException.conflict(
                                "BATCH_STATE_CONFLICT",
                                "Партия уже списана. Автоматически вернуть еду нельзя");
                    var why = reason(r.reason());
                    var next =
                            "CONFIRMED".equals(plans.getFirst().get("status"))
                                    ? "ACTIVE"
                                    : "RELEASED";
                    for (var link : links) {
                        jdbc.update(
                                "update cooking_allocations set status=:s,consumed_at=null where"
                                    + " id=:a",
                                Map.of("s", next, "a", link.get("id")));
                        audit(
                                w,
                                (UUID) link.get("batch_id"),
                                entry,
                                "CONSUMPTION_REVERSAL",
                                why,
                                Map.of(
                                        "allocationId",
                                        link.get("id"),
                                        "status",
                                        "CONSUMED",
                                        "consumedAt",
                                        String.valueOf(link.get("consumed_at"))),
                                Map.of("allocationId", link.get("id"), "status", next));
                    }
                    return List.of();
                });
    }

    @SuppressWarnings("unchecked")
    public List<Change> history(String email, UUID batch) {
        var user = pantry.lockOwner(email);
        owned(user, batch);
        return jdbc.query(
                "select * from cooking_corrections where user_id=:u and batch_id=:b order by"
                    + " created_at desc,id limit 50",
                Map.of("u", user, "b", batch),
                (rs, n) ->
                        new Change(
                                rs.getObject("id", UUID.class),
                                batch,
                                rs.getObject("entry_id", UUID.class),
                                rs.getString("kind"),
                                rs.getString("reason"),
                                rs.getTimestamp("created_at").toInstant(),
                                json.readValue(rs.getString("before_payload"), Map.class),
                                json.readValue(rs.getString("after_payload"), Map.class)));
    }
}
