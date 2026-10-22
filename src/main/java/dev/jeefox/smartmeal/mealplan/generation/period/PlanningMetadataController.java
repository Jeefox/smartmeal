package dev.jeefox.smartmeal.mealplan.generation.period;

import dev.jeefox.smartmeal.exception.ProductNotFoundException;
import dev.jeefox.smartmeal.exception.RecipeNotFoundException;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/** Explicit catalogue maintenance, never guessing allergen safety or fractional eggs. */
@RestController
public class PlanningMetadataController {
    private final RecipeRepository recipes;
    private final JdbcTemplate jdbc;

    public PlanningMetadataController(RecipeRepository recipes, JdbcTemplate jdbc) {
        this.recipes = recipes;
        this.jdbc = jdbc;
    }

    public record RecipeSettings(
            @NotNull @DecimalMin("0.25") @DecimalMax("4") @Digits(integer = 1, fraction = 2)
                    BigDecimal minPortions,
            @NotNull @DecimalMin("0.25") @DecimalMax("4") @Digits(integer = 1, fraction = 2)
                    BigDecimal maxPortions,
            @NotNull @DecimalMin("0.25") @DecimalMax("2") @Digits(integer = 1, fraction = 2)
                    BigDecimal portionStep,
            @Size(max = 100) String dishFamily,
            @Size(max = 4) Set<@NotNull MealType> mealTypes,
            @Size(max = 100) List<@NotNull @Positive Long> equipmentIds) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        @AssertTrue(
                message =
                        "Максимум порций должен быть не меньше минимума; границы должны"
                            + " соответствовать шагу")
        public boolean isRangeValid() {
            return minPortions == null
                    || maxPortions == null
                    || portionStep == null
                    || portionStep.signum() <= 0
                    || (maxPortions.compareTo(minPortions) >= 0
                            && maxPortions.subtract(minPortions).remainder(portionStep).signum()
                                    == 0);
        }
    }

    public record ProductSettings(
            boolean allergenDataComplete,
            @Size(max = 500) String verificationNote,
            @Size(max = 100) List<@NotNull @Positive Long> allergenIds) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        @AssertTrue(message = "Для проверенных аллергенов укажите источник/основание проверки")
        public boolean isVerificationValid() {
            return !allergenDataComplete
                    || (verificationNote != null && !verificationNote.isBlank());
        }
    }

    @GetMapping("/api/recipes/{id}/planning")
    @Transactional(readOnly = true)
    public RecipeSettings getRecipe(@PathVariable UUID id) {
        var recipe = recipes.findById(id).orElseThrow(() -> new RecipeNotFoundException(id));
        return new RecipeSettings(
                recipe.getMinPortions(),
                recipe.getMaxPortions(),
                recipe.getPortionStep(),
                recipe.getDishFamily(),
                recipe.getMealTypes(),
                jdbc.queryForList(
                        "select equipment_id from recipe_equipment where recipe_id=? order by"
                            + " equipment_id",
                        Long.class,
                        id));
    }

    @PutMapping("/api/recipes/{id}/planning")
    @Transactional
    public RecipeSettings updateRecipe(
            @PathVariable UUID id, @Valid @RequestBody RecipeSettings settings) {
        var recipe = recipes.findById(id).orElseThrow(() -> new RecipeNotFoundException(id));
        recipe.changePlanningDetails(
                settings.minPortions(),
                settings.maxPortions(),
                settings.portionStep(),
                settings.dishFamily() == null || settings.dishFamily().isBlank()
                        ? null
                        : settings.dishFamily().trim());
        if (settings.mealTypes() != null) recipe.changeMealTypes(settings.mealTypes());
        if (settings.equipmentIds() != null) {
            requireIds("equipment", settings.equipmentIds());
            jdbc.update("delete from recipe_equipment where recipe_id=?", id);
            for (Long equipment : settings.equipmentIds())
                jdbc.update(
                        "insert into recipe_equipment(recipe_id,equipment_id) values(?,?)",
                        id,
                        equipment);
        }
        return getRecipe(id);
    }

    @GetMapping("/api/products/{id}/planning")
    @Transactional(readOnly = true)
    public ProductSettings getProduct(@PathVariable Long id) {
        var result =
                jdbc.query(
                        "select allergen_data_complete,allergen_verification_note from products"
                            + " where id=?",
                        (rs, n) ->
                                new ProductSettings(rs.getBoolean(1), rs.getString(2), List.of()),
                        id);
        if (result.isEmpty()) throw new ProductNotFoundException(id);
        var value = result.getFirst();
        return new ProductSettings(
                value.allergenDataComplete(),
                value.verificationNote(),
                jdbc.queryForList(
                        "select allergen_id from product_allergens where product_id=? order by"
                            + " allergen_id",
                        Long.class,
                        id));
    }

    @PutMapping("/api/products/{id}/planning")
    @Transactional
    public ProductSettings updateProduct(
            @PathVariable Long id, @Valid @RequestBody ProductSettings settings) {
        if (settings.allergenIds() != null) requireIds("allergens", settings.allergenIds());
        if (jdbc.update(
                        "update products set"
                            + " allergen_data_complete=?,allergen_verification_note=?,updated_at=now()"
                            + " where id=?",
                        settings.allergenDataComplete(),
                        settings.verificationNote(),
                        id)
                == 0) throw new ProductNotFoundException(id);
        if (settings.allergenIds() != null) {
            jdbc.update("delete from product_allergens where product_id=?", id);
            for (Long allergen : settings.allergenIds())
                jdbc.update(
                        "insert into product_allergens(product_id,allergen_id) values(?,?)",
                        id,
                        allergen);
        }
        return getProduct(id);
    }

    private void requireIds(String table, List<Long> ids) {
        if (new HashSet<>(ids).size() != ids.size()
                || ids.stream()
                        .anyMatch(
                                id ->
                                        !Boolean.TRUE.equals(
                                                jdbc.queryForObject(
                                                        "select exists(select 1 from "
                                                                + table
                                                                + " where id=?)",
                                                        Boolean.class,
                                                        id))))
            throw new PlanningException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_METADATA_IDS",
                    "Выберите существующие значения без повторов");
    }
}
