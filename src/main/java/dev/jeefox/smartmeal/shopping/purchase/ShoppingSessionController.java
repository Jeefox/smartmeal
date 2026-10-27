package dev.jeefox.smartmeal.shopping.purchase;

import static dev.jeefox.smartmeal.shopping.purchase.ShoppingPurchaseModels.*;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/me/shopping-sessions")
public class ShoppingSessionController {
    private final ShoppingSessionService sessions;
    private final ShoppingPurchaseService purchases;

    public ShoppingSessionController(
            ShoppingSessionService sessions, ShoppingPurchaseService purchases) {
        this.sessions = sessions;
        this.purchases = purchases;
    }

    @PostMapping("/{id}/purchases")
    public View purchase(
            Authentication a,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Purchase r) {
        return purchases.purchase(a.getName(), id, key, r);
    }

    @PostMapping
    public View open(
            Authentication a,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Open r) {
        return sessions.open(a.getName(), key, r);
    }

    @GetMapping
    public Page list(
            Authentication a,
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int page) {
        return sessions.list(a.getName(), status, page);
    }

    @GetMapping("/{id}")
    public View get(Authentication a, @PathVariable UUID id) {
        return sessions.get(a.getName(), id);
    }

    @PatchMapping("/{id}/items/{item}")
    public View change(
            Authentication a,
            @PathVariable UUID id,
            @PathVariable UUID item,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Change r) {
        return sessions.change(a.getName(), id, item, key, r);
    }
}
