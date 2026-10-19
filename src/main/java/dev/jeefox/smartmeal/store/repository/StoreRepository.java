package dev.jeefox.smartmeal.store.repository;

import dev.jeefox.smartmeal.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {
    @EntityGraph(attributePaths = "chain")
    List<Store> findByActiveTrueAndCityIgnoreCaseOrderByAddressAscIdAsc(
            String city);

    @EntityGraph(attributePaths = "chain")
    List<Store> findByActiveTrueAndCityIgnoreCaseAndChain_IdOrderByAddressAscIdAsc(
            String city, Long chainId);
}
