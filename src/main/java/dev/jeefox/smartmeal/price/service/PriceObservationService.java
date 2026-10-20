package dev.jeefox.smartmeal.price.service;

import dev.jeefox.smartmeal.exception.PriceObservationEntryNotFoundException;
import dev.jeefox.smartmeal.exception.PriceObservationNotFoundException;
import dev.jeefox.smartmeal.exception.StoreProductNotFoundException;
import dev.jeefox.smartmeal.price.dto.CreatePriceObservationRequest;
import dev.jeefox.smartmeal.price.dto.InvalidatePriceObservationRequest;
import dev.jeefox.smartmeal.price.dto.PriceObservationResponse;
import dev.jeefox.smartmeal.price.entity.PriceObservation;
import dev.jeefox.smartmeal.price.entity.PriceSource;
import dev.jeefox.smartmeal.price.repository.PriceObservationRepository;
import dev.jeefox.smartmeal.store.entity.StoreProduct;
import dev.jeefox.smartmeal.store.repository.StoreProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceObservationService {
    private final PriceObservationRepository priceObservationRepository;
    private final StoreProductRepository storeProductRepository;

    public PriceObservationService(PriceObservationRepository priceObservationRepository, StoreProductRepository storeProductRepository) {
        this.priceObservationRepository = priceObservationRepository;
        this.storeProductRepository = storeProductRepository;
    }

    @Transactional
    public PriceObservationResponse create(Long storeProductId, CreatePriceObservationRequest request){
        StoreProduct storeProduct = storeProductRepository.findById(storeProductId)
                .orElseThrow(()-> new StoreProductNotFoundException(storeProductId));
        PriceObservation observation = new PriceObservation(storeProduct, request.price(),
                request.observedAt(), PriceSource.MANUAL, request.promotional());
        PriceObservation savedObservation = priceObservationRepository.save(observation);
        return PriceObservationResponse.from(savedObservation);
    }

    @Transactional
    public PriceObservationResponse invalidate(Long storeProductId, Long priceId,
                                               InvalidatePriceObservationRequest request, String email) {
        if (!storeProductRepository.existsById(storeProductId)) {
            throw new StoreProductNotFoundException(storeProductId);
        }
        PriceObservation observation = priceObservationRepository.findByIdAndStoreProduct_Id(priceId, storeProductId)
                .orElseThrow(() -> new PriceObservationEntryNotFoundException(priceId));
        observation.invalidate(request.reason(), email);
        return PriceObservationResponse.from(observation);
    }

    @Transactional(readOnly = true)
    public Page<PriceObservationResponse> findHistory(Long storeProductId, Boolean promotional, Boolean invalidated, Pageable pageable) {
        if (!storeProductRepository.existsById(storeProductId)) {
            throw new StoreProductNotFoundException(storeProductId);
        }
        return priceObservationRepository.findHistory(storeProductId, promotional, invalidated, pageable)
                .map(PriceObservationResponse::from);
    }

    @Transactional(readOnly = true)
    public PriceObservationResponse findLatest(Long storeProductId) {
        if (!storeProductRepository.existsById(storeProductId)) {
            throw new StoreProductNotFoundException(storeProductId);
        }

        PriceObservation observation = priceObservationRepository
                .findFirstByStoreProduct_IdAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(storeProductId)
                .orElseThrow(() ->
                        new PriceObservationNotFoundException(storeProductId));

        return PriceObservationResponse.from(observation);
    }
}
