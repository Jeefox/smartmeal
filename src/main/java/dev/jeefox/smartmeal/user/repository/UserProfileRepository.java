package dev.jeefox.smartmeal.user.repository;

import dev.jeefox.smartmeal.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
}
