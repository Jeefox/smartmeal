package dev.jeefox.smartmeal.store.repository;

import dev.jeefox.smartmeal.store.entity.RetailChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RetailChainRepository extends JpaRepository<RetailChain, Long> {
}
