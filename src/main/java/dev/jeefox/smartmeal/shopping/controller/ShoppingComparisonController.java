package dev.jeefox.smartmeal.shopping.controller;

import dev.jeefox.smartmeal.shopping.dto.ShoppingComparisonResponse;
import dev.jeefox.smartmeal.shopping.service.ShoppingComparisonService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/me/shopping-comparison")
public class ShoppingComparisonController {

    private final ShoppingComparisonService shoppingComparisonService;

    public ShoppingComparisonController(ShoppingComparisonService shoppingComparisonService) {
        this.shoppingComparisonService = shoppingComparisonService;
    }

    @GetMapping
    public ShoppingComparisonResponse compare (
            Authentication authentication,
            @RequestParam("from")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam("to")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam("storeIds") List<Long> storeIds){
        return shoppingComparisonService.compare(
                authentication.getName(),
                from,
                to,
                storeIds
        );
    }
}
