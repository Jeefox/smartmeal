package dev.jeefox.smartmeal.mealplan.repository;

import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MealPlanRepository extends JpaRepository<MealPlan, UUID> {
    Optional<MealPlan> findByUser_IdAndPlanDate(UUID userId, LocalDate planDate);

    Optional<MealPlan> findByIdAndUser_Email(UUID id, String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MealPlan p where p.id = :id and p.user.email = :email")
    Optional<MealPlan> findOwnedForUpdate(@Param("id") UUID id, @Param("email") String email);
}
