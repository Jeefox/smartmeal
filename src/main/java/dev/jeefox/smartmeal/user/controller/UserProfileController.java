package dev.jeefox.smartmeal.user.controller;

import dev.jeefox.smartmeal.nutrition.dto.EnergyEstimateResponse;
import dev.jeefox.smartmeal.nutrition.service.EnergyEstimateService;
import dev.jeefox.smartmeal.user.dto.UpdateDailyCalorieTargetRequest;
import dev.jeefox.smartmeal.user.dto.UpdateUserProfileRequest;
import dev.jeefox.smartmeal.user.dto.UserProfileResponse;
import dev.jeefox.smartmeal.user.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/me")
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final EnergyEstimateService energyEstimateService;

    public UserProfileController(UserProfileService userProfileService, EnergyEstimateService energyEstimateService) {
        this.userProfileService = userProfileService;
        this.energyEstimateService = energyEstimateService;
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

    @GetMapping("/energy-estimate")
    public EnergyEstimateResponse estimate(Authentication authentication) {
        return energyEstimateService.estimate(authentication.getName());
    }

    @PutMapping("/profile")
    public UserProfileResponse updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateUserProfileRequest request) {

        return userProfileService.updateProfile(
                authentication.getName(), request);
    }
}
