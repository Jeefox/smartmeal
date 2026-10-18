package dev.jeefox.smartmeal.auth.service;

import dev.jeefox.smartmeal.auth.dto.RegisterRequest;
import dev.jeefox.smartmeal.exception.EmailAlreadyExistsException;
import dev.jeefox.smartmeal.user.dto.UserProfileResponse;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import dev.jeefox.smartmeal.user.repository.UserProfileRepository;
import dev.jeefox.smartmeal.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder encoder;

    public RegistrationService(UserRepository userRepository, UserProfileRepository userProfileRepository, PasswordEncoder encoder) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.encoder = encoder;
    }

    @Transactional
    public UserProfileResponse register(RegisterRequest request){
        String email = request.email().trim();
        if(userRepository.existsByEmail(email)) throw new EmailAlreadyExistsException(email);

        String hash = encoder.encode(request.password());
        User savedUser = userRepository.save(new User(email, hash));
        UserProfile savedProfile = userProfileRepository.save(
                new UserProfile(savedUser));

        return UserProfileResponse.from(savedProfile);
    }
}
