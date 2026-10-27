package dev.jeefox.smartmeal.mealplan.balanced;

import static dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.*;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class BalancedController {
    private final BalancedDataService data;

    public BalancedController(BalancedDataService data) {
        this.data = data;
    }

    @GetMapping("/api/recipes/{id}/portion-profile")
    public Profile profile(@PathVariable UUID id) {
        return data.profile(id);
    }

    @PutMapping("/api/recipes/{id}/portion-profile")
    public Profile profile(@PathVariable UUID id, @Valid @RequestBody Profile r) {
        return data.saveProfile(id, r);
    }

    @GetMapping("/api/products/{id}/fiber")
    public Fiber fiber(@PathVariable Long id) {
        return data.fiber(id);
    }

    @PutMapping("/api/products/{id}/fiber")
    public Fiber fiber(@PathVariable Long id, @Valid @RequestBody Fiber r) {
        return data.saveFiber(id, r);
    }

    @GetMapping("/api/me/nutrition-targets")
    public List<TargetView> targets(Authentication a) {
        return data.targets(a.getName());
    }

    @PostMapping("/api/me/nutrition-targets/suggest")
    public Targets suggest(@Valid @RequestBody Targets r) {
        return data.suggest(r);
    }

    @PutMapping("/api/me/nutrition-targets/{id}")
    public TargetView target(
            Authentication a, @PathVariable UUID id, @Valid @RequestBody Targets r) {
        return data.saveTargets(a.getName(), id, r);
    }
}
