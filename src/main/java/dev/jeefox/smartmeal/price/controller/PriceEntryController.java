package dev.jeefox.smartmeal.price.controller;

import dev.jeefox.smartmeal.price.dto.PriceEntryPageResponse;
import dev.jeefox.smartmeal.price.dto.SavePriceEntriesRequest;
import dev.jeefox.smartmeal.price.dto.SavePriceEntriesResponse;
import dev.jeefox.smartmeal.price.service.PriceEntryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stores/{storeId}/price-entry")
public class PriceEntryController {
    private final PriceEntryService service;

    public PriceEntryController(PriceEntryService service) {
        this.service = service;
    }

    @GetMapping
    public PriceEntryPageResponse list(@PathVariable @Positive Long storeId,
                                       @RequestParam(defaultValue = "true") boolean missingOnly,
                                       @RequestParam(defaultValue = "") @Size(max = 100) String q,
                                       @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(storeId, missingOnly, q, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavePriceEntriesResponse save(Authentication authentication, @PathVariable @Positive Long storeId,
                                         @Valid @RequestBody SavePriceEntriesRequest request) {
        return service.save(authentication.getName(), storeId, request);
    }
}
