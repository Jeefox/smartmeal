package dev.jeefox.smartmeal.user.service;

import dev.jeefox.smartmeal.exception.UserProfileNotFoundException;
import dev.jeefox.smartmeal.user.dto.UpdateDailyCalorieTargetRequest;
import dev.jeefox.smartmeal.user.dto.UserProfileResponse;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import dev.jeefox.smartmeal.user.repository.UserProfileRepository;
import dev.jeefox.smartmeal.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class UserProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserRepository userRepository;

    public UserProfileService(UserProfileRepository userProfileRepository,
                              UserRepository userRepository) {
        this.userProfileRepository = userProfileRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse findByEmail(String email){
        User user = userRepository.findByEmail(email)
                .orElseThrow(()-> new UsernameNotFoundException("Пользователь не найден"));
        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElseThrow(()-> new UserProfileNotFoundException(user.getId()));
        return new UserProfileResponse(profile.getUserId(), profile.getDailyCalorieTarget());
    }

    @Transactional
    public UserProfileResponse updateDailyCalorieTarget(String email, UpdateDailyCalorieTargetRequest request){
        User user = userRepository.findByEmail(email)
                .orElseThrow(()-> new UsernameNotFoundException("Пользователь не найден"));
        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElseThrow(()-> new UserProfileNotFoundException(user.getId()));
        profile.updateDailyCalorieTarget(request.dailyCalorieTarget());
        UserProfileResponse response = new UserProfileResponse(profile.getUserId(), profile.getDailyCalorieTarget());
        return response;
    }
}
