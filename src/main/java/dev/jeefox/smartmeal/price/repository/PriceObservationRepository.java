package dev.jeefox.smartmeal.price.repository;

import dev.jeefox.smartmeal.price.entity.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, Long> {

    Optional<PriceObservation>
    findFirstByStoreProduct_IdOrderByObservedAtDescIdDesc(Long storeProductId);
}
