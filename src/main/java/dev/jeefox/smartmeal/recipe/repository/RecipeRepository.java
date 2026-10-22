package dev.jeefox.smartmeal.recipe.repository;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.recipe.entity.Recipe;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

    @Query(
            """
            select r from Recipe r
            where r.catalogueVisible = true and :mealType member of r.mealTypes
              and r.preparationTimeMin <= :maxPreparationTimeMin
              and (:namePattern is null or lower(r.name) like lower(:namePattern) escape '!')
              and exists (select i.id from RecipeIngredient i where i.recipe = r)
            """)
    Page<Recipe> findCandidates(
            MealType mealType, int maxPreparationTimeMin, String namePattern, Pageable pageable);

    String PREVIEW_ELIGIBILITY =
            """
            from recipes r
            join recipe_meal_types mt on mt.recipe_id = r.id
            where r.catalogue_visible and mt.meal_type = :mealType and r.preparation_time_min <= :maxTime
              and exists (select 1 from recipe_ingredients i where i.recipe_id = r.id)
              and not exists (
                  select 1 from recipe_ingredients i join user_excluded_products x on x.product_id = i.product_id
                  where i.recipe_id = r.id and x.user_id = :userId)
              and not exists (
                  select 1 from recipe_ingredients i
                  join product_allergens pa on pa.product_id = i.product_id
                  join user_allergies ua on ua.allergen_id = pa.allergen_id
                  where i.recipe_id = r.id and ua.user_id = :userId)
              and not exists (
                  select 1 from recipe_equipment re where re.recipe_id = r.id
                  and not exists (select 1 from user_equipment ue
                      where ue.user_id = :userId and ue.equipment_id = re.equipment_id))
            """;

    @Query(value = "select r.* " + PREVIEW_ELIGIBILITY + " order by r.id", nativeQuery = true)
    List<Recipe> findPreviewCandidates(
            UUID userId, String mealType, int maxTime, Pageable pageable);

    @Query(
            value = "select exists (select 1 " + PREVIEW_ELIGIBILITY + " and r.id = :recipeId)",
            nativeQuery = true)
    boolean isPreviewEligible(UUID userId, String mealType, int maxTime, UUID recipeId);

    @Override
    @Query("select r from Recipe r where r.catalogueVisible = true")
    Page<Recipe> findAll(Pageable pageable);

    @Query(
            "select r from Recipe r where r.catalogueVisible = true and lower(r.name) like"
                + " lower(concat('%',:name,'%'))")
    Page<Recipe> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
