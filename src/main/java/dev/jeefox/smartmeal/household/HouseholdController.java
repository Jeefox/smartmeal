package dev.jeefox.smartmeal.household;

import static dev.jeefox.smartmeal.household.HouseholdModels.*;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/me/household-members")
public class HouseholdController {
    private final HouseholdService service;

    public HouseholdController(HouseholdService service) {
        this.service = service;
    }

    @GetMapping
    public List<Member> list(Authentication a) {
        return service.list(a.getName());
    }

    @PostMapping
    public ResponseEntity<Member> create(Authentication a, @Valid @RequestBody MemberRequest r) {
        var m = service.create(a.getName(), r);
        return ResponseEntity.created(URI.create("/api/me/household-members/" + m.id())).body(m);
    }

    @PutMapping("/{id}")
    public Member update(
            Authentication a, @PathVariable UUID id, @Valid @RequestBody MemberRequest r) {
        return service.update(a.getName(), id, r);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(Authentication a, @PathVariable UUID id) {
        service.remove(a.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
