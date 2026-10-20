package dev.jeefox.smartmeal.shopping.controller;

import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateResponse;
import dev.jeefox.smartmeal.shopping.service.ShoppingEstimateService;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/me/shopping-estimate")
public class ShoppingEstimateController {
    private final ShoppingEstimateService shoppingEstimateService;

    public ShoppingEstimateController(ShoppingEstimateService shoppingEstimateService) {
        this.shoppingEstimateService = shoppingEstimateService;
    }

    @GetMapping
    public ShoppingEstimateResponse estimate(
            Authentication authentication,
            @RequestParam("from")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam("storeId") @Positive Long storeId) {
        return shoppingEstimateService.estimate(authentication.getName(), from, to, storeId);
    }
}
