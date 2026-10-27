package dev.jeefox.smartmeal.pantry;

import static dev.jeefox.smartmeal.pantry.CookingModels.*;
import static dev.jeefox.smartmeal.pantry.IngredientDemandCalculator.*;

import dev.jeefox.smartmeal.mealplan.balanced.BalancedDataService;
import dev.jeefox.smartmeal.mealplan.snapshot.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional
public class CookingService {
    @org.springframework.beans.factory.annotation.Autowired
    private dev.jeefox.smartmeal.mealplan.service.MealPlanService plans;

    private static final MathContext MC = MathContext.DECIMAL128;
    private final NamedParameterJdbcTemplate jdbc;
    private final PantryService pantry;
    private final ObjectMapper json;
    private final BalancedDataService metadata;

    public CookingService(
            NamedParameterJdbcTemplate jdbc,
            PantryService pantry,
            ObjectMapper json,
            BalancedDataService metadata) {
        this.jdbc = jdbc;
        this.pantry = pantry;
        this.json = json;
        this.metadata = metadata;
    }

    private record Source(
            Ref ref,
            RecipeSnapshot recipe,
            BigDecimal multiplier,
            BigDecimal yield,
            LocalDate date,
            String meal,
            List<Demand> raw) {}

    private Ref normalized(Ref ref) {
        return new Ref(ref.entryId(), ref.componentId() == null ? "meal" : ref.componentId());
    }

    private Source source(UUID user, Ref request) {
        var ref = normalized(request);
        var rows =
                jdbc.queryForList(
                        "select"
                            + " e.recipe_snapshot,e.portions,e.meal_type,m.plan_date,m.status,coalesce(s.state,'OPEN')"
                            + " inventory_state from meal_plan_entries e join meal_plans m on"
                            + " m.id=e.meal_plan_id left join meal_entry_inventory_states s on"
                            + " s.meal_plan_entry_id=e.id where e.id=:e and m.user_id=:u",
                        Map.of("e", ref.entryId(), "u", user));
        if (rows.isEmpty()) throw PantryException.missing();
        var row = rows.getFirst();
        if (!row.get("status").equals("CONFIRMED") || !row.get("inventory_state").equals("OPEN"))
            throw PantryException.conflict(
                    "ENTRY_STATE_CONFLICT",
                    "Для партии выберите ещё не приготовленное блюдо подтверждённого меню");
        var snapshot = json.readValue(row.get("recipe_snapshot").toString(), RecipeSnapshot.class);
        RecipeSnapshot recipe;
        BigDecimal multiplier, yield;
        if (snapshot.components().isEmpty()) {
            if (!ref.componentId().equals("meal")) throw PantryException.missing();
            recipe = snapshot;
            multiplier =
                    ((BigDecimal) row.get("portions"))
                            .divide(BigDecimal.valueOf(recipe.servings()), MC);
            var p = metadata.profile(recipe.recipeId());
            yield = p.cookedYieldG();
        } else {
            var c =
                    snapshot.components().stream()
                            .filter(x -> x.id().equals(ref.componentId()))
                            .findFirst()
                            .orElseThrow(PantryException::missing);
            recipe = c.recipe();
            multiplier =
                    c.amounts().stream()
                            .map(x -> x.multiplier())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            yield = c.profile().cookedYieldG();
        }
        multiplier = multiplier.setScale(30, RoundingMode.HALF_UP);
        if (yield == null)
            throw PantryException.invalid(
                    "Сначала укажите выход готового блюда в настройках рецепта");
        var date = ((java.sql.Date) row.get("plan_date")).toLocalDate();
        return new Source(
                ref,
                recipe,
                multiplier,
                yield,
                date,
                row.get("meal_type").toString(),
                calculate(
                        ref.entryId().toString(),
                        date,
                        0,
                        recipe,
                        multiplier.multiply(BigDecimal.valueOf(recipe.servings()))));
    }

    private Map<String, Object> owned(UUID user, UUID id) {
        var rows =
                jdbc.queryForList(
                        "select * from cooking_batches where user_id=:u and id=:id",
                        Map.of("u", user, "id", id));
        if (rows.isEmpty()) throw PantryException.missing();
        return rows.getFirst();
    }

    private String fingerprint(Source source) {
        return RecipeSnapshotFingerprint.calculate(source.recipe());
    }

    private List<Source> sources(UUID user, List<Ref> refs) {
        if (refs.stream().map(this::normalized).distinct().count() != refs.size())
            throw PantryException.invalid("Один компонент указан несколько раз");
        var result = refs.stream().map(r -> source(user, r)).toList();
        String fp = fingerprint(result.getFirst());
        if (result.stream().anyMatch(s -> !fingerprint(s).equals(fp)))
            throw PantryException.invalid(
                    "В одной партии можно объединять только один и тот же рецепт из сохранённых"
                            + " снимков");
        for (var s : result) {
            if (s.date().isBefore(pantry.today()))
                throw PantryException.invalid(
                        "Для новой партии выберите текущие или будущие приёмы пищи");
            if (jdbc.queryForObject(
                            "select count(*) from cooking_allocations where entry_id=:e and"
                                    + " component_id=:c and status in('ACTIVE','CONSUMED')",
                            Map.of("e", s.ref().entryId(), "c", s.ref().componentId()),
                            Integer.class)
                    > 0)
                throw PantryException.conflict(
                        "COMPONENT_ALREADY_ASSIGNED", "Компонент уже включён в другую партию");
        }
        return result;
    }

    private BigDecimal total(List<Source> sources) {
        return sources.stream().map(Source::multiplier).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void allocate(UUID batch, List<Source> sources) {
        for (var s : sources)
            jdbc.update(
                    "insert into"
                        + " cooking_allocations(id,batch_id,entry_id,component_id,multiplier,raw_demands,status)"
                        + " values(:id,:b,:e,:c,:m,cast(:raw as jsonb),'ACTIVE')",
                    Map.of(
                            "id",
                            UUID.randomUUID(),
                            "b",
                            batch,
                            "e",
                            s.ref().entryId(),
                            "c",
                            s.ref().componentId(),
                            "m",
                            s.multiplier(),
                            "raw",
                            json.writeValueAsString(s.raw())));
    }

    public PantryModels.Action create(String email, UUID key, Create r) {
        return pantry.execute(
                email,
                key,
                "BATCH_CREATE",
                r,
                r.expectedRevision(),
                w -> {
                    if (r.cookOn().isBefore(pantry.today())
                            || r.useBy().isBefore(r.cookOn())
                            || ChronoUnit.DAYS.between(r.cookOn(), r.useBy()) > 14)
                        throw PantryException.invalid(
                                "Проверьте даты приготовления и хранения; период планирования — до"
                                        + " 14 дней");
                    var sources = sources(w.user(), r.entries());
                    if (sources.stream()
                            .anyMatch(
                                    s ->
                                            s.date().isBefore(r.cookOn())
                                                    || s.date().isAfter(r.useBy())))
                        throw PantryException.invalid(
                                "Все приёмы пищи должны попадать между приготовлением и указанной"
                                        + " датой хранения");
                    var first = sources.getFirst();
                    var id = UUID.randomUUID();
                    var multiplier = total(sources);
                    jdbc.update(
                            "insert into"
                                + " cooking_batches(id,user_id,source_snapshot,source_fingerprint,name,planned_multiplier,estimated_yield_g,cook_on,use_by,status)"
                                + " values(:id,:u,cast(:s as jsonb),:f,:n,:m,:g,:d,:x,'PLANNED')",
                            Map.of(
                                    "id",
                                    id,
                                    "u",
                                    w.user(),
                                    "s",
                                    json.writeValueAsString(first.recipe()),
                                    "f",
                                    fingerprint(first),
                                    "n",
                                    first.recipe().name(),
                                    "m",
                                    multiplier,
                                    "g",
                                    first.yield().multiply(multiplier),
                                    "d",
                                    r.cookOn(),
                                    "x",
                                    r.useBy()));
                    allocate(id, sources);
                    return List.of();
                });
    }

    public PantryModels.Action prepare(String email, UUID key, UUID id, Prepare r) {
        return pantry.execute(
                email,
                key,
                "BATCH_PREPARE:" + id,
                r,
                r.expectedRevision(),
                w -> {
                    var b = owned(w.user(), id);
                    var useBy = ((java.sql.Date) b.get("use_by")).toLocalDate();
                    if (!b.get("status").equals("PLANNED")
                            || ((java.sql.Date) b.get("cook_on"))
                                    .toLocalDate()
                                    .isAfter(pantry.today())
                            || useBy.isBefore(pantry.today()))
                        throw PantryException.conflict(
                                "BATCH_STATE_CONFLICT",
                                "Приготовить можно запланированную на сегодня или ранее партию с"
                                        + " действующей датой хранения");
                    var refs =
                            jdbc.queryForList(
                                    "select entry_id,component_id from cooking_allocations where"
                                            + " batch_id=:b and status='ACTIVE'",
                                    Map.of("b", id));
                    for (var ref : refs)
                        source(
                                w.user(),
                                new Ref(
                                        (UUID) ref.get("entry_id"),
                                        ref.get("component_id").toString()));
                    var recipe =
                            json.readValue(
                                    b.get("source_snapshot").toString(), RecipeSnapshot.class);
                    var demand =
                            calculate(
                                    id.toString(),
                                    pantry.today(),
                                    0,
                                    recipe,
                                    ((BigDecimal) b.get("planned_multiplier"))
                                            .multiply(BigDecimal.valueOf(recipe.servings())));
                    var own = new HashSet<UUID>();
                    refs.forEach(row -> own.add((UUID) row.get("entry_id")));
                    var allocation =
                            PantryAllocationCalculator.allocate(
                                    demand, pantry.stock(w.user(), own));
                    if (!allocation.shortageG().isEmpty())
                        throw PantryException.conflict(
                                "INSUFFICIENT_STOCK",
                                "Для всей партии не хватает сырых продуктов. Добавьте покупки в"
                                        + " запасы и повторите.");
                    for (var a : allocation.allocations())
                        pantry.move(
                                w, a.lotId(), a.quantityG().negate(), "BATCH_COOKING", null, null);
                    jdbc.update(
                            "update cooking_batches set"
                                + " status='COOKED',actual_yield_g=:g,cooked_at=now() where id=:b",
                            Map.of("b", id, "g", r.actualYieldG()));
                    return allocation.allocations().stream()
                            .map(PantryAllocationCalculator.Allocation::lotId)
                            .distinct()
                            .toList();
                });
    }

    public PantryModels.Action assign(String email, UUID key, UUID id, Assign r) {
        return pantry.execute(
                email,
                key,
                "BATCH_ASSIGN:" + id,
                r,
                r.expectedRevision(),
                w -> {
                    var b = owned(w.user(), id);
                    if (!b.get("status").equals("COOKED")
                            || ((java.sql.Date) b.get("use_by"))
                                    .toLocalDate()
                                    .isBefore(pantry.today()))
                        throw PantryException.conflict(
                                "BATCH_STATE_CONFLICT",
                                "Для переноса выберите готовую партию с действующей датой"
                                        + " хранения");
                    var selected = sources(w.user(), r.entries());
                    if (!fingerprint(selected.getFirst()).equals(b.get("source_fingerprint"))
                            || selected.stream()
                                    .anyMatch(
                                            s ->
                                                    s.date()
                                                            .isAfter(
                                                                    ((java.sql.Date)
                                                                                    b.get("use_by"))
                                                                            .toLocalDate())))
                        throw PantryException.invalid(
                                "Остаток подходит только к тому же рецепту и приёмам пищи до"
                                        + " указанной даты хранения");
                    BigDecimal held =
                            jdbc.queryForObject(
                                    "select coalesce(sum(multiplier),0) from cooking_allocations"
                                        + " where batch_id=:b and status in('ACTIVE','CONSUMED')",
                                    Map.of("b", id),
                                    BigDecimal.class);
                    if (total(selected)
                                    .compareTo(
                                            ((BigDecimal) b.get("planned_multiplier"))
                                                    .subtract(held))
                            > 0)
                        throw PantryException.conflict(
                                "INSUFFICIENT_COOKED_FOOD",
                                "Свободного готового остатка недостаточно для выбранных приёмов"
                                        + " пищи");
                    allocate(id, selected);
                    return List.of();
                });
    }

    public PantryModels.Action consume(String email, UUID key, UUID entry, PantryModels.Command r) {
        return pantry.execute(
                email,
                key,
                "COOKED_CONSUME:" + entry,
                r,
                r.expectedRevision(),
                w -> {
                    var rows =
                            jdbc.queryForList(
                                    "select m.plan_date,coalesce(s.state,'OPEN') state,m.status"
                                            + " from meal_plan_entries e join meal_plans m on"
                                            + " m.id=e.meal_plan_id left join"
                                            + " meal_entry_inventory_states s on"
                                            + " s.meal_plan_entry_id=e.id where e.id=:e and"
                                            + " m.user_id=:u",
                                    Map.of("u", w.user(), "e", entry));
                    if (rows.isEmpty()) throw PantryException.missing();
                    var e = rows.getFirst();
                    if (!e.get("state").equals("DEDUCTED")
                            || !e.get("status").equals("CONFIRMED")
                            || ((java.sql.Date) e.get("plan_date"))
                                    .toLocalDate()
                                    .isAfter(pantry.today()))
                        throw PantryException.conflict(
                                "MEAL_NOT_READY",
                                "Сначала отметьте приготовление остальных компонентов этого приёма"
                                        + " пищи");
                    var links =
                            jdbc.queryForList(
                                    "select b.status,b.use_by from cooking_allocations a join"
                                        + " cooking_batches b on b.id=a.batch_id where"
                                        + " a.entry_id=:e and b.user_id=:u and a.status='ACTIVE'",
                                    Map.of("e", entry, "u", w.user()));
                    if (links.isEmpty())
                        throw PantryException.conflict(
                                "NO_COOKED_ALLOCATIONS", "Нет готовых компонентов для отметки");
                    for (var link : links)
                        if (!link.get("status").equals("COOKED")
                                || ((java.sql.Date) link.get("use_by"))
                                        .toLocalDate()
                                        .isBefore(pantry.today()))
                            throw PantryException.conflict(
                                    "COOKED_FOOD_EXPIRED",
                                    "Компонент не приготовлен или истекла указанная дата хранения");
                    jdbc.update(
                            "update cooking_allocations set status='CONSUMED',consumed_at=now()"
                                    + " where entry_id=:e and status='ACTIVE'",
                            Map.of("e", entry));
                    return List.of();
                });
    }

    public PantryModels.Action discard(String email, UUID key, UUID id, PantryModels.Command r) {
        return pantry.execute(
                email,
                key,
                "BATCH_DISCARD:" + id,
                r,
                r.expectedRevision(),
                w -> {
                    var b = owned(w.user(), id);
                    if (!Set.of("PLANNED", "COOKED").contains(b.get("status")))
                        throw PantryException.conflict(
                                "BATCH_STATE_CONFLICT", "Партия уже закрыта");
                    jdbc.update(
                            "update cooking_batches set status=:s where id=:b",
                            Map.of(
                                    "b",
                                    id,
                                    "s",
                                    b.get("status").equals("COOKED") ? "DISCARDED" : "CANCELLED"));
                    jdbc.update(
                            "update cooking_allocations set status='RELEASED' where batch_id=:b and"
                                    + " status='ACTIVE'",
                            Map.of("b", id));
                    return List.of();
                });
    }

    public PantryModels.Action confirm(String email, UUID key, Confirm r) {
        return pantry.execute(
                email,
                key,
                "COOKING_CONFIRM_PERIOD",
                r,
                r.expectedRevision(),
                w -> {
                    if (r.to().isBefore(r.from())
                            || ChronoUnit.DAYS.between(r.from(), r.to()) > 13
                            || r.from().isBefore(pantry.today()))
                        throw PantryException.invalid(
                                "Выберите текущий или будущий период до 14 дней");
                    var ids =
                            jdbc.queryForList(
                                    "select m.id from meal_plans m where m.user_id=:u and"
                                        + " m.status='DRAFT' and m.plan_date between :f and :t and"
                                        + " exists(select 1 from meal_plan_entries e where"
                                        + " e.meal_plan_id=m.id) order by m.plan_date",
                                    Map.of("u", w.user(), "f", r.from(), "t", r.to()),
                                    UUID.class);
                    for (var id : ids) plans.confirmMenu(email, id);
                    return List.of();
                });
    }

    public View view(String email, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 13)
            throw PantryException.invalid("Выберите период до 14 дней");
        var inventory = pantry.view(email);
        UUID user = pantry.lockOwner(email);
        var entryIds =
                jdbc.queryForList(
                        "select e.id from meal_plan_entries e join meal_plans m on"
                                + " m.id=e.meal_plan_id left join meal_entry_inventory_states s on"
                                + " s.meal_plan_entry_id=e.id where m.user_id=:u and"
                                + " m.status='CONFIRMED' and m.plan_date between :f and :t and"
                                + " coalesce(s.state,'OPEN')='OPEN' order by m.plan_date,e.id",
                        Map.of(
                                "u",
                                user,
                                "f",
                                from.isBefore(pantry.today()) ? pantry.today() : from,
                                "t",
                                to),
                        UUID.class);
        var candidates = new ArrayList<Candidate>();
        for (var entry : entryIds) {
            var snap =
                    json.readValue(
                            jdbc.queryForObject(
                                    "select recipe_snapshot from meal_plan_entries where id=:e",
                                    Map.of("e", entry),
                                    String.class),
                            RecipeSnapshot.class);
            if (snap.components().isEmpty()
                    && metadata.profile(snap.recipeId()).cookedYieldG() == null) continue;
            var refs =
                    snap.components().isEmpty()
                            ? List.of(new Ref(entry, "meal"))
                            : snap.components().stream().map(c -> new Ref(entry, c.id())).toList();
            for (var ref : refs) {
                if (jdbc.queryForObject(
                                "select count(*) from cooking_allocations where entry_id=:e and"
                                        + " component_id=:c and status in('ACTIVE','CONSUMED')",
                                Map.of("e", entry, "c", ref.componentId()),
                                Integer.class)
                        > 0) continue;
                var source = source(user, ref);
                candidates.add(
                        new Candidate(
                                ref,
                                source.date(),
                                source.meal(),
                                source.recipe().name(),
                                fingerprint(source),
                                source.yield().multiply(source.multiplier())));
            }
        }
        var batches = new ArrayList<Batch>();
        for (var row :
                jdbc.queryForList(
                        "select * from cooking_batches where user_id=:u order by created_at desc"
                                + " limit 100",
                        Map.of("u", user))) {
            UUID id = (UUID) row.get("id");
            var total = (BigDecimal) row.get("planned_multiplier");
            var actual = (BigDecimal) row.get("actual_yield_g");
            var estimated = (BigDecimal) row.get("estimated_yield_g");
            var yield = actual == null ? estimated : actual;
            var consumed = BigDecimal.ZERO;
            var held = BigDecimal.ZERO;
            var allocations = new ArrayList<Allocation>();
            for (var a :
                    jdbc.queryForList(
                            "select a.*,m.plan_date,e.meal_type,coalesce(s.state,'OPEN')"
                                + " inventory_state from cooking_allocations a join"
                                + " meal_plan_entries e on e.id=a.entry_id join meal_plans m on"
                                + " m.id=e.meal_plan_id left join meal_entry_inventory_states s on"
                                + " s.meal_plan_entry_id=e.id where a.batch_id=:b order by"
                                + " m.plan_date,e.meal_type,a.id",
                            Map.of("b", id))) {
                var multiplier = (BigDecimal) a.get("multiplier");
                String status = a.get("status").toString();
                if (status.equals("CONSUMED")) consumed = consumed.add(multiplier);
                if (status.equals("ACTIVE")) held = held.add(multiplier);
                allocations.add(
                        new Allocation(
                                (UUID) a.get("id"),
                                (UUID) a.get("entry_id"),
                                a.get("component_id").toString(),
                                ((java.sql.Date) a.get("plan_date")).toLocalDate(),
                                a.get("meal_type").toString(),
                                yield.multiply(multiplier).divide(total, MC),
                                status,
                                "DEDUCTED".equals(a.get("inventory_state"))));
            }
            String state = row.get("status").toString();
            var remaining =
                    state.equals("COOKED")
                            ? yield.multiply(total.subtract(consumed)).divide(total, MC)
                            : BigDecimal.ZERO;
            var free =
                    state.equals("COOKED")
                            ? yield.multiply(total.subtract(consumed).subtract(held))
                                    .divide(total, MC)
                                    .max(BigDecimal.ZERO)
                            : BigDecimal.ZERO;
            LocalDate useBy = ((java.sql.Date) row.get("use_by")).toLocalDate();
            var recipe =
                    json.readValue(row.get("source_snapshot").toString(), RecipeSnapshot.class);
            var ingredients =
                    recipe.ingredients().stream()
                            .map(
                                    i ->
                                            new Ingredient(
                                                    i.productName(), i.quantityG().multiply(total)))
                            .toList();
            batches.add(
                    new Batch(
                            id,
                            row.get("name").toString(),
                            ((java.sql.Date) row.get("cook_on")).toLocalDate(),
                            useBy,
                            state,
                            estimated,
                            actual,
                            remaining,
                            free,
                            useBy.isBefore(pantry.today()),
                            row.get("source_fingerprint").toString(),
                            List.copyOf(allocations),
                            recipe.recipeId(),
                            recipe.instructions(),
                            ingredients));
        }
        var drafts =
                jdbc.query(
                        "select m.id,m.plan_date from meal_plans m where m.user_id=:u and"
                            + " m.status='DRAFT' and m.plan_date between :f and :t and"
                            + " exists(select 1 from meal_plan_entries e where e.meal_plan_id=m.id)"
                            + " order by m.plan_date",
                        Map.of("u", user, "f", from, "t", to),
                        (rs, n) ->
                                new Draft(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, LocalDate.class)));
        return new View(
                inventory.revision(),
                pantry.today(),
                List.copyOf(candidates),
                List.copyOf(batches),
                drafts);
    }
}
