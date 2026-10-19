package dev.jeefox.smartmeal.store.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "stores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chain_id", nullable = false)
    private RetailChain chain;

    @NotBlank
    @Size(max = 120)
    @Column(name = "city", nullable = false, length = 120)
    private String city;

    @NotBlank
    @Size(max = 300)
    @Column(name = "address", nullable = false, length = 300)
    private String address;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public Store(RetailChain chain, String city, String address) {
        this.chain = chain;
        this.city = city;
        this.address = address;
        this.active = true;
    }
}
