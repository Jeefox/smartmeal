package dev.jeefox.smartmeal.auth.controller;

import dev.jeefox.smartmeal.auth.dto.RegisterRequest;
import dev.jeefox.smartmeal.auth.service.RegistrationService;
import dev.jeefox.smartmeal.user.dto.UserProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registrationService;

    public AuthController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @PostMapping("/register")
    public ResponseEntity<UserProfileResponse> register(
            @Valid @RequestBody RegisterRequest registerRequest
            ){
        final UserProfileResponse response = registrationService.register(registerRequest);
        URI location = URI.create("/api/me/profile");

        return ResponseEntity.created(location).body(response);
    }
}
