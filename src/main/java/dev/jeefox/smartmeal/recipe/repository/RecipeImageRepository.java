package dev.jeefox.smartmeal.recipe.repository;

import dev.jeefox.smartmeal.recipe.entity.RecipeImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecipeImageRepository extends JpaRepository<RecipeImage, UUID> {
    Optional<RecipeImage> findByRecipe_IdAndPosition(UUID recipeId, int position);

    List<RecipeImage> findByRecipe_IdInAndPosition(Collection<UUID> recipeIds, int position);
}
