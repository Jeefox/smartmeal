package dev.jeefox.smartmeal.auth.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(

        @NotBlank
        @Email
        @Size(max = 100)
        String email,

        @NotBlank
        @Size(min = 8, max = 128, message = "Пароль должен содержать от 8 до 128 символов")
//        @Pattern(regexp = ".*[A-Z].*", message = "Пароль должен содержать заглавную букву")
//        @Pattern(regexp = ".*\\d.*", message = "Пароль должен содержать цифру")
        String password
) {
}
