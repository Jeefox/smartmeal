package dev.jeefox.smartmeal.store.repository;

import dev.jeefox.smartmeal.store.entity.StoreProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreProductRepository extends JpaRepository<StoreProduct, Long> {
}
