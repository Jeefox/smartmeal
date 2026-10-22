package dev.jeefox.smartmeal.mealplan.generation.period;

import dev.jeefox.smartmeal.user.entity.User;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "meal_plan_generations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuGeneration {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Version
    @Column(name = "revision", nullable = false)
    private long revision;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false, columnDefinition = "jsonb")
    private PlanningModels.Document document;

    @Column(name = "commit_key")
    private UUID commitKey;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "saved_plan_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> savedPlanIds = List.of();

    public MenuGeneration(User user, PlanningModels.Document document, Instant now) {
        this.user = user;
        this.document = document;
        createdAt = now;
        expiresAt = now.plusSeconds(7200);
    }

    public void replace(PlanningModels.Document document) {
        this.document = document;
    }

    public void committed(UUID key, List<UUID> ids) {
        commitKey = key;
        savedPlanIds = List.copyOf(ids);
    }

    public PlanningModels.View view() {
        return new PlanningModels.View(id, revision, expiresAt, document, savedPlanIds);
    }
}
