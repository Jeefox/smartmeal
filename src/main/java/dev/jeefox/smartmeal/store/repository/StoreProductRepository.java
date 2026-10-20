package dev.jeefox.smartmeal.store.repository;

import dev.jeefox.smartmeal.store.entity.StoreProduct;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StoreProductRepository extends JpaRepository<StoreProduct, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StoreProduct p where p.id = :id")
    Optional<StoreProduct> findForUpdate(Long id);

    Page<StoreProduct> findByStore_IdAndActive(Long storeId, boolean active, Pageable pageable);

    Page<StoreProduct> findByStore_IdAndActiveAndNameContainingIgnoreCase(
            Long storeId, boolean active, String name, Pageable pageable);

    Page<StoreProduct> findByStore_IdAndActiveAndProduct_Id(
            Long storeId, boolean active, Long productId, Pageable pageable);

    Page<StoreProduct> findByStore_IdAndActiveAndNameContainingIgnoreCaseAndProduct_Id(
            Long storeId, boolean active, String name, Long productId, Pageable pageable);

    List<StoreProduct> findByStore_IdAndActiveTrueAndProduct_IdIn(
            Long storeId, List<Long> productIds);
}
