package dev.jeefox.smartmeal.recipe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recipe_images")
public class RecipeImage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @NotNull
    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    private String objectKey;

    @NotNull
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @NotNull
    @Positive
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @NotNull
    @Positive
    @Column(nullable = false)
    private Integer width;

    @NotNull
    @Positive
    @Column(nullable = false)
    private Integer height;

    @Column(name = "alt_text", length = 300)
    private String altText;

    @NotNull
    @Positive
    @Column(nullable = false)
    private Integer position;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RecipeImage(Recipe recipe, String objectKey, String contentType, long sizeBytes,
                       int width, int height, String altText, int position) {
        this.recipe = recipe;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.altText = altText;
        this.position = position;
    }

    public void replace(String objectKey, String contentType, long sizeBytes,
                        int width, int height, String altText) {
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.altText = altText;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
