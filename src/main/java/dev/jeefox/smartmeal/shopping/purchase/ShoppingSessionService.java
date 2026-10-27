package dev.jeefox.smartmeal.shopping.purchase;

import static dev.jeefox.smartmeal.shopping.calculation.WeightPrecision.*;
import static dev.jeefox.smartmeal.shopping.purchase.ShoppingPurchaseModels.*;

import dev.jeefox.smartmeal.mealplan.generation.period.GenerationStore;
import dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels;
import dev.jeefox.smartmeal.pantry.PantryService;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class ShoppingSessionService {
    @org.springframework.beans.factory.annotation.Autowired
    private dev.jeefox.smartmeal.pantry.SavedShoppingReconciler reconciler;

    final NamedParameterJdbcTemplate jdbc;
    final PantryService pantry;
    final ObjectMapper json;
    final GenerationStore generations;

    public ShoppingSessionService(
            NamedParameterJdbcTemplate jdbc,
            PantryService pantry,
            ObjectMapper json,
            GenerationStore generations) {
        this.jdbc = jdbc;
        this.pantry = pantry;
        this.json = json;
        this.generations = generations;
    }

    String encode(Object value) {
        return json.writeValueAsString(value);
    }

    Map<String, Object> p(UUID owner, UUID id) {
        var p = new HashMap<String, Object>();
        p.put("u", owner);
        p.put("id", id);
        return p;
    }

    Map<String, Object> owned(UUID owner, UUID id) {
        reconciler.reconcile(owner, id);
        var rows =
                jdbc.queryForList(
                        "select * from shopping_sessions where user_id=:u and id=:id for update",
                        p(owner, id));
        if (rows.isEmpty()) throw ShoppingPurchaseException.missing();
        return rows.getFirst();
    }

    void revision(Map<String, Object> row, long expected) {
        if (((Number) row.get("revision")).longValue() != expected)
            throw ShoppingPurchaseException.conflict(
                    "SHOPPING_SESSION_STALE",
                    "Список изменился. Обновите его и проверьте выбранные продукты.");
    }

    void bump(UUID owner, UUID id) {
        jdbc.update(
                "update shopping_sessions set revision=revision+1,updated_at=now() where user_id=:u"
                        + " and id=:id",
                p(owner, id));
    }

    String hash(String kind, Object request) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (kind + ":" + encode(request))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    View repeat(UUID owner, UUID key, String hash) {
        var rows =
                jdbc.queryForList(
                        "select request_hash,result_payload from shopping_session_commands where"
                                + " user_id=:u and idempotency_key=:k",
                        Map.of("u", owner, "k", key));
        if (rows.isEmpty()) return null;
        var r = rows.getFirst();
        if (!hash.equals(r.get("request_hash")))
            throw ShoppingPurchaseException.conflict(
                    "IDEMPOTENCY_CONFLICT",
                    "Ключ уже использован для другого действия. Проверьте состояние списка.");
        return json.readValue(r.get("result_payload").toString(), View.class);
    }

    UUID record(UUID owner, UUID id, UUID key, String hash, View view) {
        UUID command = UUID.randomUUID();
        var p = p(owner, id);
        p.put("command", command);
        p.put("k", key);
        p.put("hash", hash);
        p.put("result", encode(view));
        jdbc.update(
                "insert into"
                    + " shopping_session_commands(id,user_id,session_id,idempotency_key,request_hash,result_payload)"
                    + " values(:command,:u,:id,:k,:hash,cast(:result as jsonb))",
                p);
        return command;
    }

    public View open(String email, UUID key, Open request) {
        UUID owner = pantry.lockOwner(email);
        String hash = hash("open", request);
        var prior = repeat(owner, key, hash);
        if (prior != null) return prior;
        var source = generations.get(email, request.generationId());
        if (source.savedPlanIds().isEmpty())
            throw ShoppingPurchaseException.conflict(
                    "SHOPPING_SOURCE_NOT_SAVED", "Сначала сохраните меню на весь период.");
        var found =
                jdbc.queryForList(
                        "select id from shopping_sessions where user_id=:u and"
                                + " source_generation_id=:g",
                        Map.of("u", owner, "g", source.id()));
        UUID id;
        if (found.isEmpty()) {
            id = UUID.randomUUID();
            var d = source.result();
            var p = p(owner, id);
            p.put("g", source.id());
            p.put("from", d.options().from());
            p.put("to", d.options().to());
            p.put("snapshot", encode(d));
            jdbc.update(
                    "insert into"
                        + " shopping_sessions(id,user_id,source_generation_id,from_date,to_date,source_snapshot)"
                        + " values(:id,:u,:g,:from,:to,cast(:snapshot as jsonb))",
                    p);
            int pos = 0;
            for (var item : d.shopping().items()) {
                BigDecimal need = item.needToBuyG() == null ? item.requiredG() : item.needToBuyG();
                if (need.signum() <= 0) continue;
                var q = new HashMap<String, Object>();
                q.put("id", UUID.randomUUID());
                q.put("s", id);
                q.put("p", item.productId());
                q.put("position", pos++);
                q.put("snapshot", encode(item));
                q.put("need", need);
                jdbc.update(
                        "insert into"
                            + " shopping_session_items(id,session_id,product_id,position,snapshot,required_to_buy_g)"
                            + " values(:id,:s,:p,:position,cast(:snapshot as jsonb),:need)",
                        q);
            }
        } else id = (UUID) found.getFirst().get("id");
        var view = read(email, owner, id);
        record(owner, id, key, hash, view);
        return view;
    }

    public View get(String email, UUID id) {
        return read(email, pantry.lockOwner(email), id);
    }

    View read(String email, UUID owner, UUID id) {
        var row = owned(owner, id);
        var stock = pantry.view(email);
        var doc =
                json.readValue(
                        row.get("source_snapshot").toString(), PlanningModels.Document.class);
        var items =
                jdbc.query(
                        """
                        select i.*,coalesce((select sum(l.quantity_g) from shopping_purchase_lines l where l.item_id=i.id),0) acquired
                        from shopping_session_items i where i.session_id=:id order by i.position,i.id
                        """,
                        Map.of("id", id),
                        (rs, n) -> {
                            var s =
                                    json.readValue(
                                            rs.getString("snapshot"),
                                            PlanningModels.BasketItem.class);
                            var need = rs.getBigDecimal("required_to_buy_g");
                            var acquired = rs.getBigDecimal("acquired");
                            var left = shortage(need, acquired);
                            Integer count = null;
                            BigDecimal suggest = left.setScale(4, RoundingMode.CEILING);
                            if (left.signum() > 0
                                    && s.packageWeightG() != null
                                    && s.packageWeightG().signum() > 0) {
                                count = packages(left, s.packageWeightG());
                                suggest = s.packageWeightG().multiply(BigDecimal.valueOf(count));
                            }
                            return new Item(
                                    rs.getObject("id", UUID.class),
                                    s,
                                    need,
                                    acquired,
                                    left,
                                    suggest,
                                    count,
                                    rs.getBoolean("picked"),
                                    rs.getBoolean("skipped"));
                        });
        var totals =
                jdbc.queryForMap(
                        "select coalesce(sum(l.actual_total_rub),0) total,count(*) filter(where"
                            + " l.actual_total_rub is null) unknown from shopping_purchase_lines l"
                            + " join shopping_session_items i on i.id=l.item_id where"
                            + " i.session_id=:id",
                        Map.of("id", id));
        boolean overlap =
                jdbc.queryForObject(
                                """
                                select count(*) from shopping_sessions s where s.user_id=:u and s.id<>:id and s.from_date<=:to and s.to_date>=:from
                                and exists(select 1 from shopping_session_items i where i.session_id=s.id and not i.skipped and i.required_to_buy_g>coalesce((select sum(l.quantity_g) from shopping_purchase_lines l where l.item_id=i.id),0))
                                """,
                                overlapParams(owner, id, row),
                                Integer.class)
                        > 0;
        var keys =
                jdbc.queryForList(
                        "select c.idempotency_key from shopping_session_commands c join"
                                + " shopping_purchase_batches b on b.id=c.id where c.user_id=:u and"
                                + " c.session_id=:id order by c.created_at,c.id",
                        p(owner, id),
                        UUID.class);
        return new View(
                id,
                (UUID) row.get("source_generation_id"),
                doc.options().from(),
                doc.options().to(),
                ((Number) row.get("revision")).longValue(),
                stock.revision(),
                stock.today(),
                items.stream().allMatch(i -> i.skipped() || i.remainingG().signum() == 0)
                        ? "COMPLETED"
                        : "OPEN",
                doc.options().budgetRub(),
                doc.participants() == null || doc.participants().isEmpty()
                        ? 1
                        : doc.participants().size(),
                items,
                items.stream()
                        .map(i -> i.snapshot().purchaseCost())
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                (int) items.stream().filter(i -> i.snapshot().purchaseCost() == null).count(),
                (BigDecimal) totals.get("total"),
                ((Number) totals.get("unknown")).intValue(),
                overlap,
                keys);
    }

    private Map<String, Object> overlapParams(UUID owner, UUID id, Map<String, Object> row) {
        var p = p(owner, id);
        p.put("from", row.get("from_date"));
        p.put("to", row.get("to_date"));
        return p;
    }

    public Page list(String email, String status, int page) {
        if (page < 0 || page > 10000 || !Set.of("OPEN", "COMPLETED", "ALL").contains(status))
            throw ShoppingPurchaseException.invalid("Проверьте страницу и статус списка");
        UUID owner = pantry.lockOwner(email);
        var p = new HashMap<String, Object>();
        p.put("u", owner);
        p.put("offset", page * 20);
        p.put("status", status);
        var rows =
                jdbc.query(
                        """
                        select * from (select s.*,case when exists(select 1 from shopping_session_items i where i.session_id=s.id and not i.skipped and i.required_to_buy_g>coalesce((select sum(l.quantity_g) from shopping_purchase_lines l where l.item_id=i.id),0)) then 'OPEN' else 'COMPLETED' end status from shopping_sessions s where s.user_id=:u) q
                        where (:status='ALL' or status=:status) order by created_at desc,id limit 21 offset :offset
                        """,
                        p,
                        (rs, n) ->
                                new Summary(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("from_date", LocalDate.class),
                                        rs.getObject("to_date", LocalDate.class),
                                        rs.getString("status"),
                                        rs.getLong("revision"),
                                        rs.getTimestamp("created_at").toInstant()));
        return new Page(rows.stream().limit(20).toList(), page, rows.size() > 20);
    }

    public View change(String email, UUID id, UUID item, UUID key, Change request) {
        UUID owner = pantry.lockOwner(email);
        String hash = hash("change:" + id + ":" + item, request);
        var prior = repeat(owner, key, hash);
        if (prior != null) return prior;
        var row = owned(owner, id);
        revision(row, request.expectedRevision());
        var p = p(owner, id);
        p.put("item", item);
        p.put("picked", request.picked() && !request.skipped());
        p.put("skipped", request.skipped());
        if (jdbc.update(
                        "update shopping_session_items set picked=:picked,skipped=:skipped where"
                                + " session_id=:id and id=:item",
                        p)
                != 1) throw ShoppingPurchaseException.missing();
        bump(owner, id);
        var view = read(email, owner, id);
        record(owner, id, key, hash, view);
        return view;
    }
}
