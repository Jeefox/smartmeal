package dev.jeefox.smartmeal.recipe.repository;

import dev.jeefox.smartmeal.recipe.entity.Recipe;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

    Page<Recipe> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
