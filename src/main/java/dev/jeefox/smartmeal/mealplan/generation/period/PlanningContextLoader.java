package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.exception.DailyCalorieTargetNotSetException;
import dev.jeefox.smartmeal.mealplan.entity.*;
import dev.jeefox.smartmeal.mealplan.repository.*;
import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.repository.*;
import dev.jeefox.smartmeal.store.repository.RetailChainRepository;
import dev.jeefox.smartmeal.user.repository.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlanningContextLoader {
    @org.springframework.beans.factory.annotation.Autowired
    private dev.jeefox.smartmeal.mealplan.balanced.BalancedDataService balancedData;

    private final UserRepository users;

    @org.springframework.beans.factory.annotation.Autowired
    private dev.jeefox.smartmeal.household.HouseholdService household;

    @org.springframework.beans.factory.annotation.Autowired
    private dev.jeefox.smartmeal.pantry.PantryService pantry;

    private final UserProfileRepository profiles;
    private final RecipeRepository recipes;
    private final RecipeIngredientRepository ingredients;
    private final MealPlanRepository plans;
    private final MealPlanEntryRepository entries;
    private final RetailChainRepository chains;
    private final NamedParameterJdbcTemplate jdbc;
    private final Duration manualAge;
    private final Duration receiptAge;

    public PlanningContextLoader(
            UserRepository users,
            UserProfileRepository profiles,
            RecipeRepository recipes,
            RecipeIngredientRepository ingredients,
            MealPlanRepository plans,
            MealPlanEntryRepository entries,
            RetailChainRepository chains,
            NamedParameterJdbcTemplate jdbc,
            @Value("${smartmeal.planning.manual-price-max-age:P7D}") Duration manualAge,
            @Value("${smartmeal.planning.receipt-price-max-age:P14D}") Duration receiptAge) {
        this.users = users;
        this.profiles = profiles;
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.plans = plans;
        this.entries = entries;
        this.chains = chains;
        this.jdbc = jdbc;
        this.manualAge = manualAge;
        this.receiptAge = receiptAge;
    }

    @Transactional
    public PlanningContext load(String email, Options options) {
        var owner =
                users.findByEmailForUpdate(email)
                        .orElseThrow(() -> new UsernameNotFoundException(email));
        var stock = pantry.snapshot(email, Set.of());
        int target =
                profiles.findById(owner.getId())
                        .map(p -> p.getDailyCalorieTarget())
                        .filter(t -> t != null && t > 0)
                        .orElseThrow(DailyCalorieTargetNotSetException::new);
        var members = household.selected(owner.getId(), options.householdMemberIds());
        var participants =
                new ArrayList<dev.jeefox.smartmeal.household.HouseholdModels.Participant>();
        participants.add(
                new dev.jeefox.smartmeal.household.HouseholdModels.Participant(
                        owner.getId(), "Вы", target, true));
        for (var member : members)
            participants.add(
                    new dev.jeefox.smartmeal.household.HouseholdModels.Participant(
                            member.id(), member.name(), member.dailyCalorieTarget(), false));
        target =
                participants.stream()
                        .mapToInt(
                                dev.jeefox.smartmeal.household.HouseholdModels.Participant
                                        ::dailyCalorieTarget)
                        .sum();
        var extraAllergies =
                members.stream().flatMap(m -> m.allergenIds().stream()).collect(Collectors.toSet());
        var extraExclusions =
                members.stream()
                        .flatMap(m -> m.excludedProductIds().stream())
                        .collect(Collectors.toSet());
        Set<Long> unsafeProducts = new HashSet<>(extraExclusions);
        if (!extraAllergies.isEmpty())
            unsafeProducts.addAll(
                    jdbc.queryForList(
                            "select distinct product_id from product_allergens where allergen_id in"
                                    + " (:ids)",
                            Map.of("ids", extraAllergies),
                            Long.class));
        var selectedChains =
                chains.findAllById(options.allowedChainIds()).stream()
                        .sorted(Comparator.comparing(c -> c.getId()))
                        .toList();
        if (selectedChains.size() != options.allowedChainIds().size() || selectedChains.isEmpty())
            throw new PlanningException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_CHAINS",
                    "Выберите существующие торговые сети");
        Instant now = Instant.now();
        Map<String, Object> userParams = Map.of("user", owner.getId());
        List<Map<String, Object>> allergies =
                jdbc.queryForList(
                        "select allergen_id from user_allergies where user_id=:user order by"
                                + " allergen_id",
                        userParams);
        StringBuilder restriction =
                new StringBuilder(owner.getId() + ":" + target + ":" + allergies);
        restriction.append(members).append(participants);
        restriction.append(
                jdbc.queryForList(
                        "select product_id from user_excluded_products where user_id=:user order by"
                                + " product_id",
                        userParams));
        restriction.append(
                jdbc.queryForList(
                        "select equipment_id from user_equipment where user_id=:user order by"
                                + " equipment_id",
                        userParams));
        Map<MealType, List<Recipe>> eligible = new EnumMap<>(MealType.class);
        Map<UUID, Recipe> unique = new TreeMap<>();
        List<Issue> issues = new ArrayList<>();
        for (MealType meal : List.of(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)) {
            List<Recipe> found =
                    recipes.findPreviewCandidates(
                            owner.getId(),
                            meal.name(),
                            options.maxPreparationTimeByMeal().get(meal),
                            PageRequest.of(0, 501));
            if (found.size() > 500
                    && issues.stream().noneMatch(i -> i.code().equals("CATALOGUE_SEARCH_LIMIT")))
                issues.add(
                        new Issue(
                                "CATALOGUE_SEARCH_LIMIT",
                                "Часть каталога не вошла в поиск; результат не гарантирует"
                                        + " глобальный минимум."));
            if (found.isEmpty()) {
                long catalog =
                        jdbc.queryForObject(
                                "select count(*) from recipes r join recipe_meal_types mt on"
                                    + " mt.recipe_id=r.id where r.catalogue_visible and"
                                    + " mt.meal_type=:m and exists(select 1 from recipe_ingredients"
                                    + " i where i.recipe_id=r.id)",
                                Map.of("m", meal.name()),
                                Long.class);
                long timely =
                        jdbc.queryForObject(
                                "select count(*) from recipes r join recipe_meal_types mt on"
                                    + " mt.recipe_id=r.id where r.catalogue_visible and"
                                    + " mt.meal_type=:m and r.preparation_time_min<=:t and"
                                    + " exists(select 1 from recipe_ingredients i where"
                                    + " i.recipe_id=r.id)",
                                Map.of(
                                        "m",
                                        meal.name(),
                                        "t",
                                        options.maxPreparationTimeByMeal().get(meal)),
                                Long.class);
                String label =
                        switch (meal) {
                            case BREAKFAST -> "Завтрак";
                            case LUNCH -> "Обед";
                            default -> "Ужин";
                        };
                issues.add(
                        new Issue(
                                "CANDIDATES_" + meal,
                                label
                                        + ": "
                                        + (catalog == 0
                                                ? "в каталоге нет рецептов для этого приёма пищи."
                                                      + " Добавьте подходящие рецепты."
                                                : timely == 0
                                                        ? "ни один рецепт не укладывается в"
                                                              + " заданное время. Увеличьте время"
                                                              + " приготовления."
                                                        : "после проверки исключённых продуктов,"
                                                              + " аллергенов и оборудования не"
                                                              + " осталось рецептов. Проверьте"
                                                              + " оборудование или добавьте"
                                                              + " совместимые рецепты; ограничения"
                                                              + " безопасности сохраняются.")));
            }
            eligible.put(meal, found.stream().limit(500).toList());
            found.stream().limit(500).forEach(r -> unique.put(r.getId(), r));
        }
        Map<UUID, List<dev.jeefox.smartmeal.recipe.entity.RecipeIngredient>> content =
                unique.isEmpty()
                        ? Map.of()
                        : ingredients.findByRecipe_IdIn(new ArrayList<>(unique.keySet())).stream()
                                .collect(Collectors.groupingBy(i -> i.getRecipe().getId()));
        Set<Long> productIds =
                content.values().stream()
                        .flatMap(List::stream)
                        .map(i -> i.getProduct().getId())
                        .collect(Collectors.toSet());
        Set<Long> unknownAllergens = new HashSet<>();
        if ((!allergies.isEmpty() || !extraAllergies.isEmpty()) && !productIds.isEmpty())
            unknownAllergens.addAll(
                    jdbc.queryForList(
                            "select id from products where id in (:ids) and not"
                                    + " allergen_data_complete",
                            Map.of("ids", productIds),
                            Long.class));
        if (!unknownAllergens.isEmpty())
            issues.add(
                    new Issue(
                            "ALLERGEN_DATA_INCOMPLETE",
                            "Часть рецептов исключена: сведения об аллергенах продуктов не"
                                    + " проверены."));
        Map<UUID, LocalDate> lastUsed = new HashMap<>();
        var history =
                plans
                        .findByUser_IdAndPlanDateBetweenOrderByPlanDateAsc(
                                owner.getId(),
                                options.from().minusDays(21),
                                options.from().minusDays(1))
                        .stream()
                        .filter(
                                p ->
                                        p.getStatus() == MealPlanStatus.CONFIRMED
                                                || p.getStatus() == MealPlanStatus.CANCELLED)
                        .toList();
        var dates =
                history.stream().collect(Collectors.toMap(MealPlan::getId, MealPlan::getPlanDate));
        if (!history.isEmpty()) {
            var historyEntries = entries.findByMealPlan_IdIn(new ArrayList<>(dates.keySet()));
            var deducted =
                    pantry.deductedEntries(
                            email, historyEntries.stream().map(e -> e.getId()).toList());
            for (var entry : historyEntries) {
                if (entry.getMealPlan().getStatus() == MealPlanStatus.CANCELLED
                        && !deducted.contains(entry.getId())) continue;
                var snapshot = entry.getRecipeSnapshot();
                var ids = new HashSet<UUID>();
                ids.add(snapshot.recipeId());
                snapshot.components().forEach(component -> ids.add(component.recipe().recipeId()));
                for (var recipeId : ids)
                    lastUsed.merge(
                            recipeId,
                            dates.get(entry.getMealPlan().getId()),
                            (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        restriction.append(new TreeMap<>(lastUsed));
        Map<UUID, String> covers = new HashMap<>();
        if (!unique.isEmpty())
            jdbc.query(
                    "select id,recipe_id,object_key from recipe_images where recipe_id in (:ids)"
                            + " and position=1",
                    Map.of("ids", unique.keySet()),
                    rs -> {
                        UUID image = rs.getObject("id", UUID.class),
                                recipe = rs.getObject("recipe_id", UUID.class);
                        covers.put(
                                recipe,
                                "/api/recipes/"
                                        + recipe
                                        + "/images/"
                                        + image
                                        + "/content?v="
                                        + UUID.nameUUIDFromBytes(
                                                rs.getString("object_key")
                                                        .getBytes(StandardCharsets.UTF_8)));
                    });
        Map<MealType, List<Candidate>> candidates = new EnumMap<>(MealType.class);
        for (var slot : eligible.entrySet()) {
            List<Candidate> list = new ArrayList<>();
            for (Recipe recipe : slot.getValue()) {
                var recipeIngredients = content.getOrDefault(recipe.getId(), List.of());
                if (recipeIngredients.isEmpty()
                        || recipeIngredients.stream()
                                .anyMatch(
                                        i ->
                                                unknownAllergens.contains(i.getProduct().getId())
                                                        || unsafeProducts.contains(
                                                                i.getProduct().getId()))) continue;
                RecipeSnapshot snapshot = RecipeSnapshotFactory.create(recipe, recipeIngredients);
                if (snapshot.nutritionTotal().calories().signum() <= 0) continue;
                List<BigDecimal> portions = new ArrayList<>();
                for (BigDecimal p = recipe.getMinPortions();
                        p.compareTo(recipe.getMaxPortions()) <= 0 && portions.size() < 32;
                        p = p.add(recipe.getPortionStep()))
                    portions.add(p.multiply(BigDecimal.valueOf(participants.size())));
                restriction
                        .append(RecipeSnapshotFingerprint.calculate(snapshot))
                        .append(recipe.getMealTypes().stream().map(Enum::name).sorted().toList())
                        .append(
                                portions.stream()
                                        .map(p -> p.stripTrailingZeros().toPlainString())
                                        .toList())
                        .append(recipe.getDishFamily());
                list.add(
                        new Candidate(
                                snapshot,
                                recipe.getMealTypes(),
                                List.copyOf(portions),
                                recipe.getDishFamily(),
                                covers.get(recipe.getId()),
                                lastUsed.get(recipe.getId())));
            }
            candidates.put(slot.getKey(), List.copyOf(list));
        }
        List<Offer> offers = new ArrayList<>();
        if (!productIds.isEmpty()) {
            Map<String, Object> priceParams = new HashMap<>();
            priceParams.put("ids", productIds);
            priceParams.put("chains", options.allowedChainIds());
            priceParams.put("city", options.city());
            priceParams.put("now", java.sql.Timestamp.from(now));
            priceParams.put("manualFrom", java.sql.Timestamp.from(now.minus(manualAge)));
            priceParams.put("receiptFrom", java.sql.Timestamp.from(now.minus(receiptAge)));
            offers.addAll(
                    jdbc.query(
"""
select sp.id,sp.product_id,sp.name,sp.package_weight_g,s.chain_id,rc.name chain_name,s.address,
       p.id price_id,p.price,p.observed_at,p.price_source
from store_products sp join stores s on s.id=sp.store_id join retail_chains rc on rc.id=s.chain_id
join lateral (select * from price_observations po where po.store_product_id=sp.id
    and not po.promotional and po.invalidated_at is null and po.observed_at<=:now
    order by po.observed_at desc,po.id desc limit 1) p on true
where sp.active and s.active and sp.product_id in (:ids) and s.chain_id in (:chains)
    and lower(s.city)=lower(:city)
    and ((p.price_source='MANUAL' and p.observed_at>=:manualFrom)
      or (p.price_source='RECEIPT' and p.observed_at>=:receiptFrom))
""",
                            priceParams,
                            (rs, n) -> {
                                Instant observed = rs.getTimestamp("observed_at").toInstant();
                                return new Offer(
                                        "observed:" + rs.getLong("price_id"),
                                        rs.getLong("product_id"),
                                        rs.getLong("chain_id"),
                                        rs.getString("chain_name"),
                                        rs.getString("name"),
                                        rs.getBigDecimal("package_weight_g"),
                                        rs.getBigDecimal("price"),
                                        false,
                                        false,
                                        rs.getString("price_source"),
                                        options.city() + ", " + rs.getString("address"),
                                        null,
                                        observed,
                                        observed.plus(
                                                "RECEIPT".equals(rs.getString("price_source"))
                                                        ? receiptAge
                                                        : manualAge),
                                        "STORE_OBSERVATION");
                            }));
            if (options.allowEstimatedPrices())
                jdbc.query(
"""
select * from catalogue_price_references where active and product_id in (:ids)
and expires_at>:now and retrieved_at<=:now order by id
""",
                        priceParams,
                        rs -> {
                            String source = rs.getString("source_name");
                            for (var chain : selectedChains) {
                                boolean same =
                                        source.toLowerCase(Locale.ROOT)
                                                        .startsWith(
                                                                chain.getName()
                                                                        .toLowerCase(Locale.ROOT))
                                                || source.toLowerCase(Locale.ROOT)
                                                        .startsWith(
                                                                chain.getCode()
                                                                        .toLowerCase(Locale.ROOT));
                                offers.add(
                                        new Offer(
                                                "catalogue:"
                                                        + rs.getLong("id")
                                                        + ":for:"
                                                        + chain.getId(),
                                                rs.getLong("product_id"),
                                                chain.getId(),
                                                chain.getName(),
                                                rs.getString("name"),
                                                rs.getBigDecimal("package_weight_g"),
                                                rs.getBigDecimal("price"),
                                                rs.getBoolean("sold_by_weight"),
                                                true,
                                                source,
                                                rs.getString("source_scope"),
                                                rs.getString("source_url"),
                                                rs.getTimestamp("retrieved_at").toInstant(),
                                                rs.getTimestamp("expires_at").toInstant(),
                                                same
                                                        ? "CHAIN_CATALOGUE_ESTIMATE"
                                                        : "REFERENCE_ONLY"));
                            }
                        });
        }
        return balancedData.enrich(
                new PlanningContext(
                        owner.getId(),
                        target,
                        hash(restriction.toString()),
                        now,
                        Collections.unmodifiableMap(candidates),
                        List.copyOf(offers),
                        List.copyOf(issues),
                        stock,
                        List.copyOf(participants)),
                options);
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
