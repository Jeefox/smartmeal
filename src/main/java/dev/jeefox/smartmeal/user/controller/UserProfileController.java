package dev.jeefox.smartmeal.user.controller;

import dev.jeefox.smartmeal.user.dto.UpdateDailyCalorieTargetRequest;
import dev.jeefox.smartmeal.user.dto.UserProfileResponse;
import dev.jeefox.smartmeal.user.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/me")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/profile")
    public UserProfileResponse findProfile(Authentication authentication) {
        return userProfileService.findByEmail(authentication.getName());
    }

    @PutMapping("/profile/daily-calorie-target")
    public UserProfileResponse updateDailyCalorieTarget(Authentication authentication,
                                                        @Valid @RequestBody UpdateDailyCalorieTargetRequest request){
        return userProfileService.updateDailyCalorieTarget(authentication.getName(), request);
    }
}
