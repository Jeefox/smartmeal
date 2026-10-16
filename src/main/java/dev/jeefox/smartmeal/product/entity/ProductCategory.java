package dev.jeefox.smartmeal.product.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_categories")
public class ProductCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @NotNull
    @Column(nullable = false, unique = true)
    private String code;
    @NotNull
    @Column(nullable = false)
    private String name;

    public ProductCategory(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
