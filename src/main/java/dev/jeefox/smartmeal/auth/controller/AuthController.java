package dev.jeefox.smartmeal.auth.controller;

import dev.jeefox.smartmeal.auth.dto.RegisterRequest;
import dev.jeefox.smartmeal.auth.service.RegistrationService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        try {
            registrationService.register(request);
        } catch (dev.jeefox.smartmeal.exception.EmailAlreadyExistsException ignored) {
            // Do not disclose whether an address already has an account.
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            Throwable cause = exception;
            boolean duplicateEmail = false;
            while (cause != null) {
                if (cause instanceof java.sql.SQLException sql
                        && "23505".equals(sql.getSQLState())
                        && sql.getMessage() != null
                        && sql.getMessage().contains("users_email_key")) duplicateEmail = true;
                cause = cause.getCause();
            }
            if (!duplicateEmail) throw exception;
        }
        return ResponseEntity.noContent().build();
    }
}
