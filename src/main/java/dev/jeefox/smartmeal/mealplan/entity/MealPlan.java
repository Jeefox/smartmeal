package dev.jeefox.smartmeal.mealplan.entity;

import dev.jeefox.smartmeal.exception.MealPlanNotEditableException;
import dev.jeefox.smartmeal.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@Table(name = "meal_plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @NotNull
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;
    @NotNull
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private MealPlanStatus status;
    @Positive
    @Column(name = "daily_calorie_target_snapshot")
    private Integer dailyCalorieTargetSnapshot;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    public MealPlan(User user, LocalDate planDate) {
        this.user = user;
        this.planDate = planDate;
        this.status = MealPlanStatus.DRAFT;
    }

    public void markModified() {
        this.updatedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate(){
        var now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate(){
        this.updatedAt = Instant.now();
    }

    public void confirm(Integer calorieTarget){
        if(!this.status.equals(MealPlanStatus.DRAFT)){
            throw new MealPlanNotEditableException();
        }
        Objects.requireNonNull(calorieTarget, "Калорийность меню не должна быть null");
        if (calorieTarget <= 0) {
            throw new IllegalArgumentException("Цель должна быть больше нуля");
        }
        this.dailyCalorieTargetSnapshot = calorieTarget;
        this.confirmedAt = Instant.now();
        this.status = MealPlanStatus.CONFIRMED;
    }
}
