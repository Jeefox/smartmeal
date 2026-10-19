package dev.jeefox.smartmeal.store.controller;

import dev.jeefox.smartmeal.store.dto.StoreResponse;
import dev.jeefox.smartmeal.store.service.StoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
public class StoreController {
    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping
    public List<StoreResponse> findStores(@RequestParam("city") String city,
                                         @RequestParam(name = "chainId", required = false) Long chainId) {
        return storeService.findStores(city, chainId);
    }
}
