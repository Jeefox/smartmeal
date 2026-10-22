package dev.jeefox.smartmeal.mealplan.generation.period;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface MenuGenerationRepository extends JpaRepository<MenuGeneration, UUID> {
    Optional<MenuGeneration> findByIdAndUser_Email(UUID id, String email);

    Optional<MenuGeneration> findByUser_IdAndCommitKey(UUID userId, UUID commitKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from MenuGeneration g where g.id = :id and g.user.email = :email")
    Optional<MenuGeneration> findOwnedForUpdate(UUID id, String email);
}
