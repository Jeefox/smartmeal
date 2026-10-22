package dev.jeefox.smartmeal.mealplan.generation.period;

import dev.jeefox.smartmeal.user.repository.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class PlanningPreferencesController {
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;

    public PlanningPreferencesController(UserRepository users, NamedParameterJdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    public record Choice(Long id, String name) {}

    public record Catalogues(List<Choice> allergens, List<Choice> equipment) {}

    public record Preferences(
            @NotNull @Size(max = 1000) List<@NotNull @Positive Long> allergenIds,
            @NotNull @Size(max = 1000) List<@NotNull @Positive Long> excludedProductIds,
            @NotNull @Size(max = 1000) List<@NotNull @Positive Long> equipmentIds) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        @AssertTrue(message = "Списки ограничений не должны содержать дубли")
        public boolean isUnique() {
            return (allergenIds == null || new HashSet<>(allergenIds).size() == allergenIds.size())
                    && (excludedProductIds == null
                            || new HashSet<>(excludedProductIds).size()
                                    == excludedProductIds.size())
                    && (equipmentIds == null
                            || new HashSet<>(equipmentIds).size() == equipmentIds.size());
        }
    }

    public record PreferencesView(Preferences values, List<Choice> excludedProducts) {}

    @GetMapping("/api/planning-restrictions")
    public Catalogues catalogues() {
        return new Catalogues(choices("allergens"), choices("equipment"));
    }

    private List<Choice> choices(String table) {
        return jdbc.query(
                "select id,name from " + table + " order by name,id",
                Map.of(),
                (rs, n) -> new Choice(rs.getLong(1), rs.getString(2)));
    }

    @GetMapping("/api/me/planning-preferences")
    @Transactional(readOnly = true)
    public PreferencesView get(Authentication auth) {
        return load(users.findByEmail(auth.getName()).orElseThrow().getId());
    }

    @PutMapping("/api/me/planning-preferences")
    @Transactional
    public PreferencesView update(
            Authentication auth, @Valid @RequestBody Preferences preferences) {
        var owner = users.findByEmailForUpdate(auth.getName()).orElseThrow();
        requireIds("allergens", preferences.allergenIds());
        requireIds("products", preferences.excludedProductIds());
        requireIds("equipment", preferences.equipmentIds());
        replace(owner.getId(), "user_allergies", "allergen_id", preferences.allergenIds());
        replace(
                owner.getId(),
                "user_excluded_products",
                "product_id",
                preferences.excludedProductIds());
        replace(owner.getId(), "user_equipment", "equipment_id", preferences.equipmentIds());
        return load(owner.getId());
    }

    private void requireIds(String table, List<Long> ids) {
        if (!ids.isEmpty()
                && jdbc.queryForObject(
                                "select count(*) from " + table + " where id in (:ids)",
                                Map.of("ids", ids),
                                Long.class)
                        != ids.size())
            throw new PlanningException(
                    HttpStatus.BAD_REQUEST,
                    "UNKNOWN_RESTRICTION",
                    "Один из выбранных продуктов или ограничений больше не существует");
    }

    private void replace(UUID user, String table, String column, List<Long> ids) {
        jdbc.update("delete from " + table + " where user_id=:user", Map.of("user", user));
        if (!ids.isEmpty())
            jdbc.batchUpdate(
                    "insert into " + table + "(user_id," + column + ") values(:user,:id)",
                    ids.stream().map(id -> Map.of("user", user, "id", id)).toArray(Map[]::new));
    }

    private PreferencesView load(UUID user) {
        var parameters = Map.of("user", user);
        var allergies =
                jdbc.queryForList(
                        "select allergen_id from user_allergies where user_id=:user order by"
                            + " allergen_id",
                        parameters,
                        Long.class);
        var equipment =
                jdbc.queryForList(
                        "select equipment_id from user_equipment where user_id=:user order by"
                            + " equipment_id",
                        parameters,
                        Long.class);
        var excluded =
                jdbc.query(
                        "select p.id,p.name from user_excluded_products x join products p on"
                            + " p.id=x.product_id where x.user_id=:user order by p.name,p.id",
                        parameters,
                        (rs, n) -> new Choice(rs.getLong(1), rs.getString(2)));
        return new PreferencesView(
                new Preferences(allergies, excluded.stream().map(Choice::id).toList(), equipment),
                excluded);
    }
}
