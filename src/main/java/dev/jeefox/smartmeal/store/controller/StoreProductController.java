package dev.jeefox.smartmeal.store.controller;

import dev.jeefox.smartmeal.price.dto.CreatePriceObservationRequest;
import dev.jeefox.smartmeal.price.dto.PriceObservationResponse;
import dev.jeefox.smartmeal.price.service.PriceObservationService;
import dev.jeefox.smartmeal.store.dto.CreateStoreProductRequest;
import dev.jeefox.smartmeal.store.dto.StoreProductResponse;
import dev.jeefox.smartmeal.store.service.StoreProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/store-products")
public class StoreProductController {

    private final StoreProductService storeProductService;
    private final PriceObservationService priceObservationService;

    public StoreProductController(StoreProductService storeProductService, PriceObservationService priceObservationService) {
        this.storeProductService = storeProductService;
        this.priceObservationService = priceObservationService;
    }

    @PostMapping
    public ResponseEntity<StoreProductResponse> create(@Valid @RequestBody CreateStoreProductRequest request){
        StoreProductResponse response = storeProductService.create(request);
        URI location = URI.create("/api/store-products/"+response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/{storeProductId}/prices")
    public ResponseEntity<PriceObservationResponse>create(
            @PathVariable("storeProductId") Long storeProductId,
            @Valid @RequestBody CreatePriceObservationRequest request){
        PriceObservationResponse response = priceObservationService.create(storeProductId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoreProductResponse> findById (@PathVariable("id") Long id){
        StoreProductResponse response = storeProductService.findById(id);
        return ResponseEntity.ok().body(response);
    }

    @GetMapping("/{storeProductId}/prices/latest")
    public PriceObservationResponse findLatestPrice(
            @PathVariable("storeProductId") Long storeProductId) {

        return priceObservationService.findLatest(storeProductId);
    }
}
