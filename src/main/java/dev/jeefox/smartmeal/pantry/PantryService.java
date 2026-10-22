package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.*;
import static dev.jeefox.smartmeal.pantry.PantryModels.*;

import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** All balance and reservation writes serialize on the owner row, before locking any menu. */
@Service
@Transactional
public class PantryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final EntityManager em;
    private final ObjectMapper json;
    private final ZoneId zone;

    public PantryService(
            NamedParameterJdbcTemplate jdbc,
            UserRepository users,
            EntityManager em,
            ObjectMapper json,
            @Value("${smartmeal.pantry.time-zone:Europe/Moscow}") String zone) {
        this.jdbc = jdbc;
        this.users = users;
        this.em = em;
        this.json = json;
        this.zone = ZoneId.of(zone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    private Map<String, Object> params(UUID user) {
        return new HashMap<>(Map.of("u", user, "today", today()));
    }

    public UUID lockOwner(String email) {
        UUID id = users.findByEmailForUpdate(email).orElseThrow(PantryException::missing).getId();
        jdbc.update(
                "insert into pantry_accounts(user_id) values(:u) on conflict do nothing",
                Map.of("u", id));
        return id;
    }

    private long revision(UUID user) {
        return jdbc.queryForObject(
                "select revision from pantry_accounts where user_id=:u",
                Map.of("u", user),
                Long.class);
    }

    private void bump(UUID user) {
        jdbc.update(
                "update pantry_accounts set revision=revision+1 where user_id=:u",
                Map.of("u", user));
    }

    public View view(String email) {
        UUID user = lockOwner(email);
        rebuild(user);
        return viewOwned(user);
    }

    private View viewOwned(UUID user) {
        var p = params(user);
        var lots =
                jdbc.query(
                        """
select l.*,p.name,coalesce(r.amount,0) reserved from pantry_lots l join products p on p.id=l.product_id
left join (select r.lot_id,sum(r.quantity_g) amount from pantry_reservations r
  join meal_plan_entries e on e.id=r.meal_plan_entry_id join meal_plans m on m.id=e.meal_plan_id
  where r.user_id=:u and m.status='CONFIRMED' and m.plan_date>=:today group by r.lot_id) r on r.lot_id=l.id
where l.user_id=:u and l.quantity_on_hand_g>0.000000000000000000000001 order by p.name,l.expires_on nulls last,l.created_at,l.id
""",
                        p,
                        (rs, n) -> {
                            LocalDate expiry = rs.getObject("expires_on", LocalDate.class);
                            boolean expired = expiry != null && expiry.isBefore(today());
                            BigDecimal q = rs.getBigDecimal("quantity_on_hand_g"),
                                    r = rs.getBigDecimal("reserved");
                            return new Lot(
                                    rs.getObject("id", UUID.class),
                                    rs.getLong("product_id"),
                                    rs.getString("name"),
                                    q,
                                    r,
                                    expired ? BigDecimal.ZERO : q.subtract(r),
                                    rs.getObject("received_on", LocalDate.class),
                                    expiry,
                                    rs.getString("source"),
                                    expired,
                                    new ArrayList<>());
                        });
        Map<UUID, Lot> byId = new HashMap<>();
        lots.forEach(l -> byId.put(l.id(), l));
        jdbc.query(
                """
select r.lot_id,r.meal_plan_entry_id,r.quantity_g,m.plan_date,e.meal_type,e.recipe_snapshot->>'name' recipe_name
from pantry_reservations r join meal_plan_entries e on e.id=r.meal_plan_entry_id
join meal_plans m on m.id=e.meal_plan_id
where r.user_id=:u and m.status='CONFIRMED' and m.plan_date>=:today
order by m.plan_date,e.meal_type,e.position,e.id
""",
                p,
                rs -> {
                    var lot = byId.get(rs.getObject("lot_id", UUID.class));
                    if (lot != null)
                        lot.reservedFor()
                                .add(
                                        new ReservationTarget(
                                                rs.getObject("meal_plan_entry_id", UUID.class),
                                                rs.getObject("plan_date", LocalDate.class),
                                                rs.getString("meal_type"),
                                                rs.getString("recipe_name"),
                                                rs.getBigDecimal("quantity_g")));
                });
        int pending =
                jdbc.queryForObject(
                        """
select count(*) from meal_plan_entries e join meal_plans m on m.id=e.meal_plan_id
left join meal_entry_inventory_states s on s.meal_plan_entry_id=e.id
where m.user_id=:u and m.status='CONFIRMED' and m.plan_date<:today and coalesce(s.state,'OPEN')='OPEN'
""",
                        p,
                        Integer.class);
        return new View(revision(user), today(), lots, pending);
    }

    /** Excluded entries' own reservations are available to that same shopping calculation. */
    public Snapshot snapshot(String email, Set<UUID> ownEntries) {
        UUID user = lockOwner(email);
        rebuild(user);
        return stock(user, ownEntries);
    }

    private Snapshot stock(UUID user, Set<UUID> ownEntries) {
        var p = params(user);
        String exclusion = "";
        if (!ownEntries.isEmpty()) {
            p.put("own", ownEntries);
            exclusion = " and r.meal_plan_entry_id not in (:own)";
        }
        var lots =
                jdbc.query(
                        """
select l.*,l.quantity_on_hand_g-coalesce(r.amount,0) available from pantry_lots l
left join (select r.lot_id,sum(r.quantity_g) amount from pantry_reservations r
  join meal_plan_entries e on e.id=r.meal_plan_entry_id join meal_plans m on m.id=e.meal_plan_id
  where r.user_id=:u and m.status='CONFIRMED' and m.plan_date>=:today
"""
                                + exclusion
                                + """
                                  group by r.lot_id) r on r.lot_id=l.id
                                where l.user_id=:u and l.quantity_on_hand_g>0
                                """,
                        p,
                        (rs, n) ->
                                new StockLot(
                                        rs.getObject("id", UUID.class),
                                        rs.getLong("product_id"),
                                        rs.getBigDecimal("available"),
                                        rs.getObject("received_on", LocalDate.class),
                                        rs.getObject("expires_on", LocalDate.class),
                                        rs.getTimestamp("created_at").toInstant()));
        return new Snapshot(revision(user), today(), lots);
    }

    private List<Demand> demands(UUID user) {
        em.flush();
        return jdbc
                .query(
                        """
select e.id,e.recipe_snapshot,e.portions,e.meal_type,e.position,m.plan_date
from meal_plan_entries e join meal_plans m on m.id=e.meal_plan_id
left join meal_entry_inventory_states s on s.meal_plan_entry_id=e.id
where m.user_id=:u and m.status='CONFIRMED' and m.plan_date>=:today and coalesce(s.state,'OPEN')='OPEN'
order by m.plan_date,e.meal_type,e.position,e.id
""",
                        params(user),
                        (rs, n) ->
                                calculate(
                                        rs.getObject("id", UUID.class).toString(),
                                        rs.getObject("plan_date", LocalDate.class),
                                        mealOrder(rs.getString("meal_type")) * 100000
                                                + rs.getInt("position"),
                                        json.readValue(
                                                rs.getString("recipe_snapshot"),
                                                RecipeSnapshot.class),
                                        rs.getBigDecimal("portions")))
                .stream()
                .flatMap(List::stream)
                .toList();
    }

    private int mealOrder(String meal) {
        return switch (meal) {
            case "BREAKFAST" -> 0;
            case "LUNCH" -> 1;
            case "DINNER" -> 2;
            default -> 3;
        };
    }

    public void reconcile(String email) {
        UUID user = lockOwner(email);
        rebuild(user);
    }

    private void rebuild(UUID user) {
        em.flush();
        jdbc.update(
                """
insert into meal_entry_inventory_states(meal_plan_entry_id,state,updated_at)
select e.id,'OPEN',now() from meal_plan_entries e join meal_plans m on m.id=e.meal_plan_id
where m.user_id=:u and m.status='CONFIRMED' and m.plan_date>=:today on conflict do nothing
""",
                params(user));
        var demand = demands(user);
        // Rebuild against the physical balance, rather than subtracting the old reservations twice.
        var full =
                jdbc.query(
                        "select * from pantry_lots where user_id=:u and quantity_on_hand_g>0",
                        params(user),
                        (rs, n) ->
                                new StockLot(
                                        rs.getObject("id", UUID.class),
                                        rs.getLong("product_id"),
                                        rs.getBigDecimal("quantity_on_hand_g"),
                                        rs.getObject("received_on", LocalDate.class),
                                        rs.getObject("expires_on", LocalDate.class),
                                        rs.getTimestamp("created_at").toInstant()));
        var allocation =
                PantryAllocationCalculator.allocate(
                        demand, new Snapshot(revision(user), today(), full));
        Map<String, BigDecimal> before = new TreeMap<>(), after = new TreeMap<>();
        jdbc.query(
                "select * from pantry_reservations where user_id=:u",
                params(user),
                rs -> {
                    before.put(
                            rs.getString("meal_plan_entry_id") + ":" + rs.getString("lot_id"),
                            rs.getBigDecimal("quantity_g").stripTrailingZeros());
                });
        allocation
                .allocations()
                .forEach(
                        a ->
                                after.put(
                                        a.entryId() + ":" + a.lotId(),
                                        a.quantityG().stripTrailingZeros()));
        if (before.equals(after)) return;
        jdbc.update("delete from pantry_reservations where user_id=:u", params(user));
        for (var a : allocation.allocations())
            jdbc.update(
                    """
insert into pantry_reservations(user_id,lot_id,meal_plan_entry_id,quantity_g) values(:u,:l,:e,:q)
""",
                    Map.of(
                            "u",
                            user,
                            "l",
                            a.lotId(),
                            "e",
                            UUID.fromString(a.entryId()),
                            "q",
                            a.quantityG()));
        bump(user);
    }

    private String encode(Object value) {
        return json.writeValueAsString(value);
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Work(UUID user, UUID operation) {}

    private Action execute(
            String email,
            UUID key,
            String kind,
            Object payload,
            long expected,
            Function<Work, List<UUID>> task) {
        UUID user = lockOwner(email);
        String serialized = encode(payload), digest = hash(kind + ":" + serialized);
        var p = params(user);
        p.put("key", key);
        var old =
                jdbc.queryForList(
                        "select request_hash,result_payload from pantry_operations where user_id=:u"
                                + " and idempotency_key=:key",
                        p);
        if (!old.isEmpty()) {
            if (!old.getFirst().get("request_hash").equals(digest))
                throw PantryException.conflict(
                        "IDEMPOTENCY_CONFLICT", "Этот ключ использован для другого действия");
            return json.readValue(old.getFirst().get("result_payload").toString(), Action.class);
        }
        rebuild(user);
        if (revision(user) != expected)
            throw PantryException.conflict(
                    "PANTRY_STALE", "Запасы изменились. Обновите данные и повторите действие.");
        UUID op = UUID.randomUUID();
        p.put("op", op);
        p.put("hash", digest);
        p.put("kind", kind);
        p.put("payload", serialized);
        jdbc.update(
                """
insert into pantry_operations(id,user_id,idempotency_key,request_hash,kind,request_payload,result_payload,created_at)
values(:op,:u,:key,:hash,:kind,cast(:payload as jsonb),'{}'::jsonb,now())
""",
                p);
        List<UUID> ids = task.apply(new Work(user, op));
        bump(user);
        rebuild(user);
        Action action = new Action(op, revision(user), List.copyOf(ids));
        p.put("result", encode(action));
        jdbc.update(
                "update pantry_operations set result_payload=cast(:result as jsonb) where id=:op",
                p);
        return action;
    }

    private void validateDates(LocalDate received, LocalDate expires) {
        if (received == null
                || received.isAfter(today())
                || received.isBefore(LocalDate.of(1900, 1, 1)))
            throw PantryException.invalid(
                    "Дата поступления должна быть не позднее сегодняшней и не ранее 1900 года");
        if (expires != null
                && (expires.isBefore(received) || expires.isAfter(LocalDate.of(2200, 12, 31))))
            throw PantryException.invalid(
                    "Проверьте срок годности: он не может быть раньше поступления");
    }

    private UUID add(Work work, PurchaseLine line, String source) {
        validateDates(line.receivedOn(), line.expiresOn());
        if (jdbc.queryForObject(
                        "select count(*) from products where id=:p",
                        Map.of("p", line.productId()),
                        Integer.class)
                != 1) throw PantryException.missing();
        if (line.storeProductId() != null
                && jdbc.queryForObject(
                                "select count(*) from store_products where id=:s and product_id=:p",
                                Map.of("s", line.storeProductId(), "p", line.productId()),
                                Integer.class)
                        != 1)
            throw PantryException.invalid("Товар магазина не соответствует продукту");
        UUID id = UUID.randomUUID();
        var p = params(work.user());
        p.put("id", id);
        p.put("p", line.productId());
        p.put("q", line.quantityG());
        p.put("received", line.receivedOn());
        p.put("expiry", line.expiresOn());
        p.put("source", source);
        p.put("sku", line.storeProductId());
        jdbc.update(
                """
insert into pantry_lots(id,user_id,product_id,quantity_on_hand_g,received_on,expires_on,source,store_product_id,created_at,updated_at)
values(:id,:u,:p,0,:received,:expiry,:source,:sku,now(),now())
""",
                p);
        move(work, id, line.quantityG(), source, null, null);
        return id;
    }

    public Action addLot(String email, UUID key, AddLot r) {
        return execute(
                email,
                key,
                "INITIAL",
                r,
                r.expectedRevision(),
                w ->
                        List.of(
                                add(
                                        w,
                                        new PurchaseLine(
                                                r.productId(),
                                                r.quantityG(),
                                                r.receivedOn(),
                                                r.expiresOn(),
                                                null,
                                                null),
                                        "INITIAL")));
    }

    public Action purchase(String email, UUID key, Purchase r) {
        return execute(
                email,
                key,
                "PURCHASE",
                r,
                r.expectedRevision(),
                w -> r.items().stream().map(l -> add(w, l, "PURCHASE")).toList());
    }

    private Map<String, Object> ownedLot(UUID user, UUID id) {
        var rows =
                jdbc.queryForList(
                        "select * from pantry_lots where user_id=:u and id=:id",
                        Map.of("u", user, "id", id));
        if (rows.isEmpty()) throw PantryException.missing();
        return rows.getFirst();
    }

    private void move(
            Work work, UUID lot, BigDecimal delta, String reason, UUID entry, UUID reversal) {
        if (delta.signum() == 0) return;
        var p = params(work.user());
        p.put("l", lot);
        p.put("q", delta);
        p.put("op", work.operation());
        p.put("id", UUID.randomUUID());
        p.put("reason", reason);
        p.put("e", entry);
        p.put("reversal", reversal);
        int changed =
                jdbc.update(
                        """
update pantry_lots set quantity_on_hand_g=quantity_on_hand_g+:q,updated_at=now()
where user_id=:u and id=:l and quantity_on_hand_g+:q>=0
""",
                        p);
        if (changed != 1)
            throw PantryException.conflict("INSUFFICIENT_STOCK", "Недостаточно продукта в партии");
        jdbc.update(
                """
insert into pantry_movements(id,user_id,operation_id,lot_id,quantity_delta_g,reason,meal_plan_entry_id,reversal_of_id,created_at)
values(:id,:u,:op,:l,:q,:reason,:e,:reversal,now())
""",
                p);
    }

    public Action adjust(String email, UUID key, UUID id, Adjustment r, boolean discard) {
        return execute(
                email,
                key,
                (discard ? "DISCARD:" : "ADJUSTMENT:") + id,
                r,
                r.expectedRevision(),
                w -> {
                    var lot = ownedLot(w.user(), id);
                    BigDecimal old = (BigDecimal) lot.get("quantity_on_hand_g");
                    move(
                            w,
                            id,
                            discard ? r.quantityG().negate() : r.quantityG().subtract(old),
                            r.reason(),
                            null,
                            null);
                    return List.of(id);
                });
    }

    public Action metadata(String email, UUID key, UUID id, Metadata r) {
        return execute(
                email,
                key,
                "METADATA:" + id,
                r,
                r.expectedRevision(),
                w -> {
                    var lot = ownedLot(w.user(), id);
                    validateDates(
                            ((java.sql.Date) lot.get("received_on")).toLocalDate(), r.expiresOn());
                    var p = params(w.user());
                    p.put("id", id);
                    p.put("expiry", r.expiresOn());
                    jdbc.update(
                            "update pantry_lots set expires_on=:expiry,updated_at=now() where"
                                    + " user_id=:u and id=:id",
                            p);
                    return List.of(id);
                });
    }

    public EntryStates states(String email, UUID planId) {
        UUID user = lockOwner(email);
        rebuild(user);
        ownedPlan(user, planId);
        var p = params(user);
        p.put("plan", planId);
        var states =
                jdbc.query(
                        """
select e.id,coalesce(s.state,'OPEN') state from meal_plan_entries e
left join meal_entry_inventory_states s on s.meal_plan_entry_id=e.id where e.meal_plan_id=:plan order by e.position,e.id
""",
                        p,
                        (rs, n) ->
                                new EntryState(
                                        rs.getObject("id", UUID.class),
                                        State.valueOf(rs.getString("state")),
                                        new TreeMap<>()));
        for (var s : states) {
            p.put("entry", s.entryId());
            jdbc.query(
                    """
select l.product_id,sum(r.quantity_g) q from pantry_reservations r join pantry_lots l on l.id=r.lot_id
join meal_plan_entries e on e.id=r.meal_plan_entry_id join meal_plans m on m.id=e.meal_plan_id
where r.user_id=:u and r.meal_plan_entry_id=:entry and m.plan_date>=:today group by l.product_id
""",
                    p,
                    rs -> {
                        s.reservedG().put(rs.getLong("product_id"), rs.getBigDecimal("q"));
                    });
        }
        return new EntryStates(revision(user), today(), states);
    }

    private Map<String, Object> ownedPlan(UUID user, UUID plan) {
        var rows =
                jdbc.queryForList(
                        "select * from meal_plans where id=:id and user_id=:u",
                        Map.of("id", plan, "u", user));
        if (rows.isEmpty()) throw PantryException.missing();
        return rows.getFirst();
    }

    private Map<String, Object> ownedEntry(UUID user, UUID entry) {
        var rows =
                jdbc.queryForList(
                        """
select e.*,m.status plan_status,m.plan_date,coalesce(s.state,'OPEN') inventory_state,s.deduction_operation_id
from meal_plan_entries e join meal_plans m on m.id=e.meal_plan_id
left join meal_entry_inventory_states s on s.meal_plan_entry_id=e.id where e.id=:id and m.user_id=:u
""",
                        Map.of("id", entry, "u", user));
        if (rows.isEmpty()) throw PantryException.missing();
        return rows.getFirst();
    }

    private void setState(UUID entry, String state, UUID operation) {
        var p = new HashMap<String, Object>();
        p.put("e", entry);
        p.put("state", state);
        p.put("op", operation);
        jdbc.update(
                """
insert into meal_entry_inventory_states(meal_plan_entry_id,state,deduction_operation_id,updated_at)
values(:e,:state,:op,now()) on conflict(meal_plan_entry_id) do update
set state=:state,deduction_operation_id=:op,updated_at=now()
""",
                p);
    }

    public Action usage(String email, UUID key, UUID entry, Command r) {
        return execute(
                email,
                key,
                "COOKING:" + entry,
                r,
                r.expectedRevision(),
                w -> {
                    var e = ownedEntry(w.user(), entry);
                    if (!"CONFIRMED".equals(e.get("plan_status"))
                            || !"OPEN".equals(e.get("inventory_state")))
                        throw PantryException.conflict(
                                "ENTRY_STATE_CONFLICT",
                                "Списать ингредиенты можно только у открытого блюда подтверждённого"
                                        + " меню");
                    LocalDate date = ((java.sql.Date) e.get("plan_date")).toLocalDate();
                    if (date.isAfter(today()))
                        throw PantryException.invalid(
                                "Будущее приготовление пока не поддерживается");
                    var recipe =
                            json.readValue(
                                    e.get("recipe_snapshot").toString(), RecipeSnapshot.class);
                    // Actual preparation uses today's available lots, not expired historical
                    // reservations.
                    var demand =
                            calculate(
                                    entry.toString(),
                                    today(),
                                    0,
                                    recipe,
                                    (BigDecimal) e.get("portions"));
                    var result =
                            PantryAllocationCalculator.allocate(
                                    demand, stock(w.user(), Set.of(entry)));
                    if (!result.shortageG().isEmpty()) {
                        var deficits =
                                demand.stream()
                                        .filter(d -> result.shortageG().containsKey(d.productId()))
                                        .map(
                                                d ->
                                                        Map.of(
                                                                "productId",
                                                                d.productId(),
                                                                "requiredG",
                                                                d.quantityG(),
                                                                "availableG",
                                                                result.coveredG()
                                                                        .getOrDefault(
                                                                                d.productId(),
                                                                                BigDecimal.ZERO),
                                                                "shortageG",
                                                                result.shortageG()
                                                                        .get(d.productId())))
                                        .toList();
                        throw new PantryException(
                                HttpStatus.CONFLICT,
                                "INSUFFICIENT_STOCK",
                                "Не хватает ингредиентов. Добавьте покупку или сверьте остаток.",
                                Map.of("deficits", deficits));
                    }
                    for (var a : result.allocations())
                        move(w, a.lotId(), a.quantityG().negate(), "COOKING", entry, null);
                    setState(entry, "DEDUCTED", w.operation());
                    return result.allocations().stream()
                            .map(PantryAllocationCalculator.Allocation::lotId)
                            .distinct()
                            .toList();
                });
    }

    public Action reverse(String email, UUID key, UUID entry, Command r) {
        return execute(
                email,
                key,
                "REVERSAL:" + entry,
                r,
                r.expectedRevision(),
                w -> {
                    var e = ownedEntry(w.user(), entry);
                    if (!"DEDUCTED".equals(e.get("inventory_state")))
                        throw PantryException.conflict(
                                "ENTRY_STATE_CONFLICT", "У блюда нет действующего списания");
                    var rows =
                            jdbc.queryForList(
                                    "select * from pantry_movements where user_id=:u and"
                                            + " operation_id=:op and meal_plan_entry_id=:e",
                                    Map.of(
                                            "u",
                                            w.user(),
                                            "op",
                                            e.get("deduction_operation_id"),
                                            "e",
                                            entry));
                    List<UUID> ids = new ArrayList<>();
                    for (var row : rows) {
                        UUID lot = (UUID) row.get("lot_id");
                        move(
                                w,
                                lot,
                                ((BigDecimal) row.get("quantity_delta_g")).negate(),
                                "REVERSAL",
                                entry,
                                (UUID) row.get("id"));
                        ids.add(lot);
                    }
                    setState(entry, "OPEN", null);
                    return ids.stream().distinct().toList();
                });
    }

    public Action state(String email, UUID key, UUID entry, StateCommand r) {
        return execute(
                email,
                key,
                "STATE:" + entry,
                r,
                r.expectedRevision(),
                w -> {
                    var e = ownedEntry(w.user(), entry);
                    if (!"CONFIRMED".equals(e.get("plan_status"))
                            || "DEDUCTED".equals(e.get("inventory_state"))
                            || r.state() == State.DEDUCTED)
                        throw PantryException.conflict(
                                "ENTRY_STATE_CONFLICT",
                                "Сначала отмените списание или подтвердите меню");
                    setState(entry, r.state().name(), null);
                    return List.of();
                });
    }

    public Action plan(String email, UUID key, UUID plan, Command r, boolean reopen) {
        return execute(
                email,
                key,
                (reopen ? "REOPEN:" : "CANCEL:") + plan,
                r,
                r.expectedRevision(),
                w -> {
                    var m = ownedPlan(w.user(), plan);
                    String old = m.get("status").toString();
                    if (reopen) {
                        if (!old.equals("CANCELLED"))
                            throw PantryException.conflict(
                                    "PLAN_STATE_CONFLICT",
                                    "Можно восстановить только отменённый день");
                        int movements =
                                jdbc.queryForObject(
                                        """
select count(*) from pantry_movements v join meal_plan_entries e on e.id=v.meal_plan_entry_id
where e.meal_plan_id=:p
""",
                                        Map.of("p", plan),
                                        Integer.class);
                        if (movements > 0)
                            throw PantryException.conflict(
                                    "PLAN_HAS_HISTORY",
                                    "У дня есть история списаний. Её нельзя переписать"
                                            + " восстановлением черновика.");
                    } else if (old.equals("CANCELLED"))
                        throw PantryException.conflict("PLAN_STATE_CONFLICT", "День уже отменён");
                    var p = params(w.user());
                    p.put("id", plan);
                    p.put("status", reopen ? "DRAFT" : "CANCELLED");
                    p.put("old", old);
                    p.put("op", w.operation());
                    jdbc.update(
                            """
update meal_plans set status=:status,cancelled_at=case when :status='CANCELLED' then now() else null end,
daily_calorie_target_snapshot=case when :status='DRAFT' then null else daily_calorie_target_snapshot end,
confirmed_at=case when :status='DRAFT' then null else confirmed_at end,updated_at=now(),
cancellation_history=cancellation_history||jsonb_build_array(jsonb_build_object('from',cast(:old as text),'to',cast(:status as text),'at',now(),'operationId',cast(:op as text)))
where id=:id and user_id=:u
""",
                            p);
                    if (reopen)
                        jdbc.update(
                                "delete from meal_entry_inventory_states where meal_plan_entry_id"
                                        + " in (select id from meal_plan_entries where"
                                        + " meal_plan_id=:id)",
                                Map.of("id", plan));
                    // JDBC changed the plan; detach only that entity, preserving unrelated managed
                    // changes.
                    var managed =
                            em.find(dev.jeefox.smartmeal.mealplan.entity.MealPlan.class, plan);
                    if (managed != null) em.refresh(managed);
                    return List.of();
                });
    }

    public History history(String email, int page) {
        if (page < 0 || page > 10000)
            throw PantryException.invalid("Некорректная страница истории");
        UUID user = lockOwner(email);
        var p = params(user);
        p.put("offset", page * 30);
        var ops =
                jdbc.query(
                        "select id,kind,created_at from pantry_operations where user_id=:u order by"
                                + " created_at desc,id desc limit 31 offset :offset",
                        p,
                        (rs, n) ->
                                new Operation(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("kind"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        new ArrayList<>()));
        for (var op : ops.stream().limit(30).toList()) {
            p.put("op", op.id());
            op.movements()
                    .addAll(
                            jdbc.query(
                                    """
select m.*,l.product_id,p.name from pantry_movements m join pantry_lots l on l.id=m.lot_id join products p on p.id=l.product_id
where m.user_id=:u and m.operation_id=:op order by p.name,m.id
""",
                                    p,
                                    (rs, n) ->
                                            new Movement(
                                                    rs.getObject("lot_id", UUID.class),
                                                    rs.getLong("product_id"),
                                                    rs.getString("name"),
                                                    rs.getBigDecimal("quantity_delta_g"),
                                                    rs.getString("reason"))));
        }
        return new History(page, ops.size() > 30, ops.stream().limit(30).toList());
    }

    public Set<UUID> deductedEntries(String email, Collection<UUID> ids) {
        if (ids.isEmpty()) return Set.of();
        UUID user = lockOwner(email);
        return new HashSet<>(
                jdbc.queryForList(
                        """
select e.id from meal_plan_entries e join meal_plans m on m.id=e.meal_plan_id
join meal_entry_inventory_states s on s.meal_plan_entry_id=e.id
where m.user_id=:u and e.id in (:ids) and s.state='DEDUCTED'
""",
                        Map.of("u", user, "ids", ids),
                        UUID.class));
    }

    public Set<UUID> inactiveEntries(String email, Collection<UUID> ids) {
        if (ids.isEmpty()) return Set.of();
        UUID user = lockOwner(email);
        rebuild(user);
        return new HashSet<>(
                jdbc.queryForList(
                        """
select e.id from meal_plan_entries e join meal_plans m on m.id=e.meal_plan_id
left join meal_entry_inventory_states s on s.meal_plan_entry_id=e.id
where m.user_id=:u and e.id in (:ids) and (m.status='CANCELLED' or coalesce(s.state,'OPEN')<>'OPEN')
""",
                        Map.of("u", user, "ids", ids),
                        UUID.class));
    }
}
