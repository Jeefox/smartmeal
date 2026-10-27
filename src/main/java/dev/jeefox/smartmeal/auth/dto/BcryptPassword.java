package dev.jeefox.smartmeal.auth.dto;

import jakarta.validation.*;

import java.lang.annotation.*;
import java.nio.charset.StandardCharsets;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BcryptPassword.Validator.class)
public @interface BcryptPassword {
    String message() default "Пароль должен занимать не более 72 байт UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BcryptPassword, String> {
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }
}
