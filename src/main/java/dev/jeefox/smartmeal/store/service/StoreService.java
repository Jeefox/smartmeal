package dev.jeefox.smartmeal.store.service;

import dev.jeefox.smartmeal.exception.InvalidStoreFilterException;
import dev.jeefox.smartmeal.store.dto.RetailChainResponse;
import dev.jeefox.smartmeal.store.dto.StoreResponse;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.repository.RetailChainRepository;
import dev.jeefox.smartmeal.store.repository.StoreRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StoreService {
    private final RetailChainRepository retailChainRepository;
    private final StoreRepository storeRepository;

    public StoreService(RetailChainRepository retailChainRepository, StoreRepository storeRepository) {
        this.retailChainRepository = retailChainRepository;
        this.storeRepository = storeRepository;
    }

    @Transactional(readOnly = true)
    public List<RetailChainResponse> findRetailChains() {
        return retailChainRepository.findAll(Sort.by("name", "id")).stream()
                .map(RetailChainResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> findStores(String city, Long chainId) {
        if (city == null || city.isBlank()) {
            throw new InvalidStoreFilterException("Укажите город для поиска магазинов");
        }
        if (chainId != null && chainId <= 0) {
            throw new InvalidStoreFilterException("Идентификатор сети должен быть больше нуля");
        }
        String normalizedCity = city.trim();
        List<Store> stores = chainId == null
                ? storeRepository.findByActiveTrueAndCityIgnoreCaseOrderByAddressAscIdAsc(normalizedCity)
                : storeRepository.findByActiveTrueAndCityIgnoreCaseAndChain_IdOrderByAddressAscIdAsc(
                        normalizedCity, chainId);
        return stores.stream().map(StoreResponse::from).toList();
    }
}
