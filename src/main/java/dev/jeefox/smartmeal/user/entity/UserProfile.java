package dev.jeefox.smartmeal.user.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @NotNull
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Positive
    @Column(name = "age")
    private Short age;

    @Enumerated(EnumType.STRING)
    @Column(name = "sex", length = 16)
    private Sex sex;

    @Positive
    @Column(name = "height_cm")
    private Integer heightCm;

    @Positive
    @Digits(integer = 3, fraction = 2)
    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "activity_level", columnDefinition = "activity_level")
    private ActivityLevel activityLevel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "goal", columnDefinition = "goal")
    private Goal goal;

    @Positive
    @Digits(integer = 3, fraction = 2)
    @Column(name = "target_weight_kg", precision = 5, scale = 2)
    private BigDecimal targetWeightKg;

    @Positive
    @Column(name = "daily_calorie_target")
    private Integer dailyCalorieTarget;

    public UserProfile(Integer dailyCalorieTarget, User user) {
        this.dailyCalorieTarget = dailyCalorieTarget;
        this.user = user;
    }
    public UserProfile(User user) {
        this.user = user;
    }

    public void updateProfile(
            Short age,
            Sex sex,
            Integer heightCm,
            BigDecimal weightKg,
            ActivityLevel activityLevel,
            Goal goal){
        this.age = age;
        this.sex = sex;
        this.heightCm = heightCm;
        this.weightKg = weightKg;
        this.activityLevel = activityLevel;
        this.goal = goal;
    }

    public void updateDailyCalorieTarget(Integer dailyCalorieTarget){
        this.dailyCalorieTarget = dailyCalorieTarget;
    }
}
