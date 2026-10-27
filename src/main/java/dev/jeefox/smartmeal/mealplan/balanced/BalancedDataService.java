package dev.jeefox.smartmeal.mealplan.balanced;

import static dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.*;

import dev.jeefox.smartmeal.mealplan.generation.period.*;
import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.pantry.PantryService;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@Transactional
public class BalancedDataService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PantryService pantry;
    private final ObjectMapper json;

    public BalancedDataService(
            NamedParameterJdbcTemplate jdbc, PantryService pantry, ObjectMapper json) {
        this.jdbc = jdbc;
        this.pantry = pantry;
        this.json = json;
    }

    private PlanningException invalid(String s) {
        return new PlanningException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_NUTRITION_SETTINGS", s);
    }

    private void exists(String table, Object id) {
        if (jdbc.queryForObject(
                        "select count(*) from " + table + " where id=:id",
                        Map.of("id", id),
                        Integer.class)
                != 1)
            throw new PlanningException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "NUTRITION_DATA_NOT_FOUND",
                    "Запись не найдена");
    }

    public Profile profile(UUID id) {
        exists("recipes", id);
        var found = profiles(List.of(id));
        return found.getOrDefault(
                id,
                new Profile(
                        Role.COMPLETE_MAIN,
                        null,
                        YieldQuality.UNKNOWN,
                        null,
                        new BigDecimal("250"),
                        new BigDecimal("450"),
                        new BigDecimal("25"),
                        false,
                        false));
    }

    public Profile saveProfile(UUID id, Profile p) {
        exists("recipes", id);
        if (p.minG().compareTo(p.maxG()) > 0
                || p.maxG().compareTo(new BigDecimal("1200")) > 0
                || p.stepG().compareTo(new BigDecimal("10")) < 0)
            throw invalid("Проверьте диапазон и шаг массы: шаг от 10 г, максимум до 1200 г");
        if (p.yieldQuality() == YieldQuality.UNKNOWN
                ? p.cookedYieldG() != null
                : p.cookedYieldG() == null || p.source() == null || p.source().isBlank())
            throw invalid(
                    "Для известного выхода укажите вес и источник; неизвестный выход оставьте"
                        + " пустым");
        var q = new HashMap<String, Object>();
        q.put("id", id);
        q.put("role", p.role().name());
        q.put("yield", p.cookedYieldG());
        q.put("quality", p.yieldQuality().name());
        q.put("source", p.source());
        q.put("min", p.minG());
        q.put("max", p.maxG());
        q.put("step", p.stepG());
        q.put("starch", p.containsStarch());
        q.put("veg", p.containsVegetables());
        jdbc.update(
                """
                insert into recipe_portion_profiles(recipe_id,role,cooked_yield_g,yield_quality,source,min_g,max_g,step_g,contains_starch,contains_vegetables)
                values(:id,:role,:yield,:quality,:source,:min,:max,:step,:starch,:veg) on conflict(recipe_id) do update set role=:role,cooked_yield_g=:yield,yield_quality=:quality,source=:source,min_g=:min,max_g=:max,step_g=:step,contains_starch=:starch,contains_vegetables=:veg,updated_at=now()
                """,
                q);
        return p;
    }

    public Fiber fiber(Long id) {
        exists("products", id);
        return fibers(List.of(id)).getOrDefault(id, new Fiber(null, null, "UNKNOWN"));
    }

    public Fiber saveFiber(Long id, Fiber f) {
        exists("products", id);
        if (!Set.of("REFERENCE", "LABEL", "UNKNOWN").contains(f.quality())
                || (f.fiberPer100g() != null && (f.source() == null || f.source().isBlank()))
                || (f.quality().equals("UNKNOWN") && f.fiberPer100g() != null))
            throw invalid("Проверьте источник и качество данных клетчатки");
        var p = new HashMap<String, Object>();
        p.put("id", id);
        p.put("f", f.fiberPer100g());
        p.put("s", f.source());
        p.put("q", f.quality());
        jdbc.update(
                "insert into product_fiber_metadata(product_id,fiber_per_100g,source,quality)"
                    + " values(:id,:f,:s,:q) on conflict(product_id) do update set"
                    + " fiber_per_100g=:f,source=:s,quality=:q,updated_at=now()",
                p);
        return f;
    }

    public Map<UUID, Profile> profiles(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var map = new TreeMap<UUID, Profile>();
        jdbc.query(
                "select * from recipe_portion_profiles where recipe_id in(:ids) order by recipe_id",
                Map.of("ids", ids),
                rs -> {
                    map.put(
                            rs.getObject("recipe_id", UUID.class),
                            new Profile(
                                    Role.valueOf(rs.getString("role")),
                                    rs.getBigDecimal("cooked_yield_g"),
                                    YieldQuality.valueOf(rs.getString("yield_quality")),
                                    rs.getString("source"),
                                    rs.getBigDecimal("min_g"),
                                    rs.getBigDecimal("max_g"),
                                    rs.getBigDecimal("step_g"),
                                    rs.getBoolean("contains_starch"),
                                    rs.getBoolean("contains_vegetables")));
                });
        return map;
    }

    private Map<Long, Fiber> fibers(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var map = new TreeMap<Long, Fiber>();
        jdbc.query(
                "select * from product_fiber_metadata where product_id in(:ids) order by"
                    + " product_id",
                Map.of("ids", ids),
                rs -> {
                    map.put(
                            rs.getLong("product_id"),
                            new Fiber(
                                    rs.getBigDecimal("fiber_per_100g"),
                                    rs.getString("source"),
                                    rs.getString("quality")));
                });
        return map;
    }

    private Map<UUID, Targets> targets(UUID owner) {
        var map = new TreeMap<UUID, Targets>();
        jdbc.query(
                "select * from participant_nutrient_targets where user_id=:u order by"
                    + " participant_id",
                Map.of("u", owner),
                rs -> {
                    map.put(
                            rs.getObject("participant_id", UUID.class),
                            new Targets(
                                    rs.getLong("revision"),
                                    rs.getBoolean("adult_confirmed"),
                                    rs.getBigDecimal("weight_kg"),
                                    rs.getBigDecimal("protein_target_g"),
                                    rs.getBigDecimal("fiber_target_g"),
                                    rs.getBigDecimal("max_meal_g"),
                                    rs.getString("target_source")));
                });
        return map;
    }

    public List<TargetView> targets(String email) {
        UUID owner = pantry.lockOwner(email);
        var all = targets(owner);
        var names = new LinkedHashMap<UUID, String>();
        names.put(owner, "Вы");
        jdbc.query(
                "select id,name from household_members where user_id=:u and active order by"
                    + " created_at,id",
                Map.of("u", owner),
                rs -> {
                    names.put(rs.getObject("id", UUID.class), rs.getString("name"));
                });
        return names.entrySet().stream()
                .map(
                        e -> {
                            var p =
                                    all.getOrDefault(
                                            e.getKey(),
                                            new Targets(
                                                    0,
                                                    false,
                                                    null,
                                                    null,
                                                    null,
                                                    new BigDecimal("650"),
                                                    "UNSET"));
                            return new TargetView(
                                    e.getKey(), e.getValue(), p.expectedRevision(), p);
                        })
                .toList();
    }

    public Targets suggest(Targets r) {
        if (!r.adultConfirmed() || r.weightKg() == null)
            throw invalid(
                    "Для взрослого справочного ориентира подтвердите возраст и укажите массу тела");
        return new Targets(
                r.expectedRevision(),
                true,
                r.weightKg(),
                r.weightKg().multiply(new BigDecimal("0.83")).setScale(2, RoundingMode.HALF_UP),
                new BigDecimal("25"),
                r.maxMealG(),
                "REFERENCE");
    }

    public TargetView saveTargets(String email, UUID person, Targets r) {
        UUID owner = pantry.lockOwner(email);
        var current =
                targets(email).stream()
                        .filter(p -> p.participantId().equals(person))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new PlanningException(
                                                org.springframework.http.HttpStatus.NOT_FOUND,
                                                "PARTICIPANT_NOT_FOUND",
                                                "Участник не найден"));
        if (current.revision() != r.expectedRevision())
            throw PlanningException.conflict(
                    "NUTRIENT_TARGETS_STALE", "Цели изменились. Обновите анкету.");
        if (!Set.of("UNSET", "REFERENCE", "USER_SET").contains(r.targetSource())
                || (!r.adultConfirmed()
                        && (r.proteinTargetG() != null || r.fiberTargetG() != null)))
            throw invalid(
                    "Обычные цели этого подбора предназначены для подтверждённого взрослого"
                        + " профиля");
        if (r.targetSource().equals("REFERENCE")) {
            var expected = suggest(r);
            if ((r.proteinTargetG() == null
                            || expected.proteinTargetG().compareTo(r.proteinTargetG()) != 0)
                    || r.fiberTargetG() == null
                    || r.fiberTargetG().compareTo(expected.fiberTargetG()) != 0)
                throw invalid("Сначала рассчитайте и подтвердите справочные ориентиры");
        }
        var p = new HashMap<String, Object>();
        p.put("u", owner);
        p.put("person", person);
        p.put("adult", r.adultConfirmed());
        p.put("weight", r.weightKg());
        p.put("protein", r.proteinTargetG());
        p.put("fiber", r.fiberTargetG());
        p.put("max", r.maxMealG());
        p.put("source", r.targetSource());
        jdbc.update(
                """
                insert into participant_nutrient_targets(user_id,participant_id,adult_confirmed,weight_kg,protein_target_g,fiber_target_g,max_meal_g,target_source,revision)
                values(:u,:person,:adult,:weight,:protein,:fiber,:max,:source,1) on conflict(user_id,participant_id) do update set adult_confirmed=:adult,weight_kg=:weight,protein_target_g=:protein,fiber_target_g=:fiber,max_meal_g=:max,target_source=:source,revision=participant_nutrient_targets.revision+1,updated_at=now()
                """,
                p);
        return targets(email).stream()
                .filter(x -> x.participantId().equals(person))
                .findFirst()
                .orElseThrow();
    }

    public PlanningContext enrich(PlanningContext c, PlanningModels.Options options) {
        if (!options.balancedMeals()) return c;
        var ids = c.allCandidates().stream().map(x -> x.snapshot().recipeId()).distinct().toList();
        var productIds =
                c.allCandidates().stream()
                        .flatMap(x -> x.snapshot().ingredients().stream())
                        .map(RecipeIngredientSnapshot::productId)
                        .distinct()
                        .toList();
        var profiles = profiles(ids);
        var fiber = fibers(productIds);
        var targets = targets(c.ownerId());
        var extra = new Context(profiles, fiber, targets);
        var candidates =
                new EnumMap<
                        dev.jeefox.smartmeal.mealplan.entity.MealType,
                        List<PlanningModels.Candidate>>(
                        dev.jeefox.smartmeal.mealplan.entity.MealType.class);
        c.candidates()
                .forEach(
                        (m, list) ->
                                candidates.put(
                                        m,
                                        list.stream()
                                                .map(
                                                        x ->
                                                                new PlanningModels.Candidate(
                                                                        enrich(x.snapshot(), fiber),
                                                                        x.mealTypes(),
                                                                        x.portions(),
                                                                        x.family(),
                                                                        x.coverImageUrl(),
                                                                        x.lastPlannedDate()))
                                                .toList()));
        String hash =
                hash(
                        c.fingerprint()
                                + json.writeValueAsString(
                                        List.of(
                                                new TreeMap<>(profiles),
                                                new TreeMap<>(fiber),
                                                new TreeMap<>(targets))));
        return new PlanningContext(
                c.ownerId(),
                c.target(),
                hash,
                c.asOf(),
                candidates,
                c.offers(),
                c.issues(),
                c.pantry(),
                c.participants().stream()
                        .map(
                                p -> {
                                    var t = targets.get(p.id());
                                    return new dev.jeefox.smartmeal.household.HouseholdModels
                                            .Participant(
                                            p.id(),
                                            p.name(),
                                            p.dailyCalorieTarget(),
                                            p.accountOwner(),
                                            t == null ? null : t.proteinTargetG(),
                                            t == null ? null : t.fiberTargetG());
                                })
                        .toList(),
                extra);
    }

    private RecipeSnapshot enrich(RecipeSnapshot s, Map<Long, Fiber> fiber) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean complete = true;
        for (var i : s.ingredients()) {
            var f = fiber.get(i.productId());
            if (f == null || f.fiberPer100g() == null) complete = false;
            else
                sum =
                        sum.add(
                                f.fiberPer100g()
                                        .multiply(i.quantityG())
                                        .divide(new BigDecimal("100")));
        }
        var n = s.nutritionTotal();
        var total =
                new NutritionValues(n.calories(), n.protein(), n.fat(), n.carbs(), sum, complete);
        return new RecipeSnapshot(
                s.recipeId(),
                s.name(),
                s.description(),
                s.instructions(),
                s.preparationTimeMin(),
                s.servings(),
                s.ingredients(),
                total,
                total.scaled(
                                BigDecimal.ONE.divide(
                                        BigDecimal.valueOf(s.servings()), MathContext.DECIMAL128))
                        .rounded());
    }

    public static String hash(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
