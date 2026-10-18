package dev.jeefox.smartmeal.mealplan.repository;

import dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import dev.jeefox.smartmeal.mealplan.entity.MealType;

@Repository
public interface MealPlanEntryRepository extends JpaRepository<MealPlanEntry, UUID> {

    List<MealPlanEntry> findByMealPlan_IdOrderByMealTypeAscPositionAsc(UUID mealPlanId);

    Optional<MealPlanEntry> findByIdAndMealPlan_Id(UUID id, UUID mealPlanId);

    Optional<MealPlanEntry> findFirstByMealPlan_IdAndMealTypeOrderByPositionDesc(
            UUID mealPlanId, MealType mealType);

    boolean existsByMealPlan_Id(UUID mealPlanId);
}
