package dev.jeefox.smartmeal.store.controller;

import dev.jeefox.smartmeal.price.dto.CreatePriceObservationRequest;
import dev.jeefox.smartmeal.price.dto.InvalidatePriceObservationRequest;
import dev.jeefox.smartmeal.price.dto.PriceObservationPageResponse;
import dev.jeefox.smartmeal.price.dto.PriceObservationResponse;
import dev.jeefox.smartmeal.price.service.PriceObservationService;
import dev.jeefox.smartmeal.store.dto.CreateStoreProductRequest;
import dev.jeefox.smartmeal.store.dto.StoreProductPageResponse;
import dev.jeefox.smartmeal.store.dto.StoreProductResponse;
import dev.jeefox.smartmeal.store.dto.UpdateStoreProductActivityRequest;
import dev.jeefox.smartmeal.store.service.StoreProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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

    @GetMapping
    public StoreProductPageResponse findAll(
            @RequestParam("storeId") @Positive Long storeId,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "productId", required = false) @Positive Long productId,
            @RequestParam(name = "active", defaultValue = "true") boolean active,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return new StoreProductPageResponse(storeProductService.findAll(
                storeId, name, productId, active, PageRequest.of(page, size, Sort.by("name", "id"))));
    }

    @PutMapping("/{id}/activity")
    public StoreProductResponse changeActivity(
            @PathVariable("id") @Positive Long id,
            @Valid @RequestBody UpdateStoreProductActivityRequest request) {
        return storeProductService.changeActivity(id, request.active());
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoreProductResponse> findById (@PathVariable("id") Long id){
        StoreProductResponse response = storeProductService.findById(id);
        return ResponseEntity.ok().body(response);
    }

    @PostMapping("/{storeProductId}/prices/{priceId}/invalidate")
    public PriceObservationResponse invalidatePrice(
            @PathVariable("storeProductId") @Positive Long storeProductId,
            @PathVariable("priceId") @Positive Long priceId,
            @Valid @RequestBody InvalidatePriceObservationRequest request,
            Authentication authentication) {
        return priceObservationService.invalidate(storeProductId, priceId, request, authentication.getName());
    }

    @GetMapping("/{storeProductId}/prices")
    public PriceObservationPageResponse findPriceHistory(
            @PathVariable("storeProductId") @Positive Long storeProductId,
            @RequestParam(name = "promotional", required = false) Boolean promotional,
            @RequestParam(name = "invalidated", required = false) Boolean invalidated,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return new PriceObservationPageResponse(priceObservationService.findHistory(
                storeProductId, promotional, invalidated, PageRequest.of(page, size)));
    }

    @GetMapping("/{storeProductId}/prices/latest")
    public PriceObservationResponse findLatestPrice(
            @PathVariable("storeProductId") Long storeProductId) {

        return priceObservationService.findLatest(storeProductId);
    }
}
