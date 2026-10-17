package dev.jeefox.smartmeal.exception;

import java.util.UUID;

public class UserProfileNotFoundException extends RuntimeException {
    public UserProfileNotFoundException(UUID id) {
        super("Профиль пользователя с ID: "+ id + " не найден");
    }
}
