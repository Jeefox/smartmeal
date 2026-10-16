package dev.jeefox.smartmeal.recipe.repository;

import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, UUID> {

    @EntityGraph(attributePaths = "product")
    public List<RecipeIngredient> findByRecipe_IdOrderByPositionAsc(UUID id);
}
