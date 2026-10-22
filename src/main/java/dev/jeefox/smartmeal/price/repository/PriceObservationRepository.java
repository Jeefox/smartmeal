package dev.jeefox.smartmeal.price.repository;

import dev.jeefox.smartmeal.price.entity.PriceObservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, Long> {
    @Query(value = """
            select distinct on (store_product_id) * from price_observations
            where store_product_id in (:ids) and not promotional and invalidated_at is null
            order by store_product_id, observed_at desc, id desc
            """, nativeQuery = true)
    java.util.List<PriceObservation> findLatestRegular(java.util.List<Long> ids);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PriceObservation> findByIdAndStoreProduct_Id(Long id, Long storeProductId);

    @Query("""
            select p from PriceObservation p
            where p.storeProduct.id = :storeProductId
              and (:promotional is null or p.promotional = :promotional)
              and (:invalidated is null
                   or (:invalidated = true and p.invalidatedAt is not null)
                   or (:invalidated = false and p.invalidatedAt is null))
            order by p.observedAt desc, p.id desc
            """)
    Page<PriceObservation> findHistory(Long storeProductId, Boolean promotional, Boolean invalidated, Pageable pageable);

    Optional<PriceObservation>
    findFirstByStoreProduct_IdAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(Long storeProductId);

    Optional<PriceObservation>
    findFirstByStoreProduct_IdAndPromotionalFalseAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(
            Long storeProductId);
}
