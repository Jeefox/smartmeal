package dev.jeefox.smartmeal.exception;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("Пользователь с таким Email уже зарегистрирован: "+email);
    }
}
