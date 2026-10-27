package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.shopping.dto.ShoppingListItemResponse;
import dev.jeefox.smartmeal.shopping.service.ShoppingListService;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class SavedShoppingReconciler {
    private final NamedParameterJdbcTemplate jdbc;
    private final ShoppingListService shopping;
    private final ObjectMapper json;
    private final PantryService pantry;

    public SavedShoppingReconciler(
            NamedParameterJdbcTemplate jdbc,
            ShoppingListService shopping,
            ObjectMapper json,
            PantryService pantry) {
        this.jdbc = jdbc;
        this.shopping = shopping;
        this.json = json;
        this.pantry = pantry;
    }

    public void overlapping(UUID owner, LocalDate date) {
        var ids =
                jdbc.queryForList(
                        "select id from shopping_sessions where user_id=:u and :d between from_date"
                            + " and to_date order by id",
                        Map.of("u", owner, "d", date),
                        UUID.class);
        ids.forEach(id -> reconcile(owner, id));
    }

    public void reconcile(UUID owner, UUID session) {
        var rows =
                jdbc.queryForList(
                        "select * from shopping_sessions where id=:s and user_id=:u for update",
                        Map.of("s", session, "u", owner));
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        var period = Map.of("u", owner, "f", row.get("from_date"), "t", row.get("to_date"));
        var latest =
                jdbc.queryForObject(
                        "select max(greatest(r.applied_at,r.undone_at)) from"
                            + " meal_entry_replacements r join meal_plan_entries e on"
                            + " e.id=r.entry_id join meal_plans m on m.id=e.meal_plan_id where"
                            + " r.user_id=:u and m.plan_date between :f and :t",
                        period,
                        java.sql.Timestamp.class);
        if (latest == null) return;
        if (row.get("menu_reconciled_at") != null
                && !latest.toInstant()
                        .isAfter(((java.sql.Timestamp) row.get("menu_reconciled_at")).toInstant()))
            return;
        var events =
                jdbc.queryForList(
                        "select r.*,greatest(r.applied_at,r.undone_at) event_at from"
                            + " meal_entry_replacements r join meal_plan_entries e on"
                            + " e.id=r.entry_id join meal_plans m on m.id=e.meal_plan_id where"
                            + " r.user_id=:u and m.plan_date between :f and :t order by event_at"
                            + " desc,r.id",
                        period);
        var email =
                jdbc.queryForObject(
                        "select email from users where id=:u", Map.of("u", owner), String.class);
        var from = ((java.sql.Date) row.get("from_date")).toLocalDate();
        var to = ((java.sql.Date) row.get("to_date")).toLocalDate();
        var needs =
                to.isBefore(pantry.today())
                        ? List.<ShoppingListItemResponse>of()
                        : shopping.calculate(
                                        email,
                                        from.isBefore(pantry.today()) ? pantry.today() : from,
                                        to)
                                .items();
        var quotes = new HashMap<Long, BasketItem>();
        for (var event : events) {
            var basket =
                    json.readValue(
                            event.get(
                                            event.get("undone_at") == null
                                                    ? "after_basket"
                                                    : "before_basket")
                                    .toString(),
                            Basket.class);
            basket.items().forEach(i -> quotes.putIfAbsent(i.productId(), i));
        }
        var items =
                jdbc.queryForList(
                        "select i.*,coalesce((select sum(l.quantity_g) from shopping_purchase_lines"
                            + " l where l.item_id=i.id),0) acquired from shopping_session_items i"
                            + " where session_id=:s order by position,id",
                        Map.of("s", session));
        var byProduct = new HashMap<Long, Map<String, Object>>();
        items.forEach(i -> byProduct.put(((Number) i.get("product_id")).longValue(), i));
        var demanded = new HashMap<Long, ShoppingListItemResponse>();
        needs.forEach(i -> demanded.put(i.productId(), i));
        var ids = new TreeSet<Long>(byProduct.keySet());
        ids.addAll(demanded.keySet());
        int position =
                items.stream()
                                .mapToInt(i -> ((Number) i.get("position")).intValue())
                                .max()
                                .orElse(-1)
                        + 1;
        for (var product : ids) {
            var old = byProduct.get(product);
            var need = demanded.get(product);
            var remaining = need == null ? BigDecimal.ZERO : need.quantityG();
            var acquired = old == null ? BigDecimal.ZERO : (BigDecimal) old.get("acquired");
            var target = acquired.add(remaining);
            var quote = quotes.get(product);
            if (quote == null && old != null)
                quote = json.readValue(old.get("snapshot").toString(), BasketItem.class);
            String name = need == null ? quote.productName() : need.productName();
            BigDecimal weight = quote == null ? null : quote.packageWeightG();
            Integer packages =
                    weight == null
                            ? null
                            : remaining.divide(weight, 0, RoundingMode.CEILING).intValueExact();
            var bought = weight == null ? remaining : weight.multiply(BigDecimal.valueOf(packages));
            BigDecimal price = quote == null ? null : quote.packagePrice();
            BigDecimal cost =
                    remaining.signum() == 0
                            ? BigDecimal.ZERO
                            : price == null
                                    ? null
                                    : weight == null
                                            ? remaining
                                                    .multiply(price)
                                                    .divide(
                                                            new BigDecimal("1000"),
                                                            2,
                                                            RoundingMode.HALF_UP)
                                            : price.multiply(BigDecimal.valueOf(packages));
            var snapshot =
                    new BasketItem(
                            product,
                            name,
                            need == null ? BigDecimal.ZERO : need.requiredG(),
                            bought,
                            bought.subtract(remaining),
                            packages,
                            weight,
                            price,
                            cost,
                            quote == null ? null : quote.chainId(),
                            quote == null ? null : quote.chainName(),
                            quote == null ? null : quote.offerKey(),
                            quote == null ? null : quote.offerName(),
                            quote == null || quote.estimated(),
                            quote == null ? null : quote.sourceName(),
                            quote == null ? null : quote.sourceScope(),
                            quote == null ? null : quote.sourceUrl(),
                            quote == null ? null : quote.observedAt(),
                            quote == null ? null : quote.expiresAt(),
                            quote == null ? "UNKNOWN" : quote.applicability(),
                            need == null ? BigDecimal.ZERO : need.fromPantryG(),
                            remaining);
            var p = new HashMap<String, Object>();
            p.put("s", session);
            p.put("id", old == null ? UUID.randomUUID() : old.get("id"));
            p.put("product", product);
            p.put("snapshot", json.writeValueAsString(snapshot));
            p.put("need", target);
            if (old == null) {
                if (remaining.signum() <= 0) continue;
                p.put("position", position++);
                jdbc.update(
                        "insert into"
                            + " shopping_session_items(id,session_id,product_id,position,snapshot,required_to_buy_g)"
                            + " values(:id,:s,:product,:position,cast(:snapshot as jsonb),:need)",
                        p);
            } else {
                p.put("changed", target.compareTo((BigDecimal) old.get("required_to_buy_g")) != 0);
                jdbc.update(
                        "update shopping_session_items set snapshot=cast(:snapshot as"
                            + " jsonb),required_to_buy_g=:need,picked=case when :changed then false"
                            + " else picked end,skipped=case when :changed then false else skipped"
                            + " end where id=:id and session_id=:s",
                        p);
            }
        }
        jdbc.update(
                "update shopping_sessions set"
                    + " revision=revision+1,updated_at=clock_timestamp(),menu_reconciled_at=:latest"
                    + " where id=:s and user_id=:u",
                Map.of("s", session, "u", owner, "latest", latest));
    }
}
