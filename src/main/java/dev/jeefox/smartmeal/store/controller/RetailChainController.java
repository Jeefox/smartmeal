package dev.jeefox.smartmeal.store.controller;

import dev.jeefox.smartmeal.store.dto.RetailChainResponse;
import dev.jeefox.smartmeal.store.service.StoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/retail-chains")
public class RetailChainController {
    private final StoreService storeService;

    public RetailChainController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping
    public List<RetailChainResponse> findRetailChains() {
        return storeService.findRetailChains();
    }
}
