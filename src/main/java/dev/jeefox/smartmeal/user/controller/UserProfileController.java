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
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public UserProfileController(
            UserProfileService userProfileService,
            EnergyEstimateService energyEstimateService,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.userProfileService = userProfileService;
        this.energyEstimateService = energyEstimateService;
        this.jdbc = jdbc;
    }

    private UserProfileResponse decorate(UserProfileResponse profile, Authentication auth) {
        boolean configured =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "select exists(select 1 from user_kitchen_setup where"
                                    + " user_id=:user)",
                                java.util.Map.of("user", profile.userId()),
                                Boolean.class));
        return profile.withCataloguePermission(canEdit(auth)).withKitchenConfigured(configured);
    }

    private boolean canEdit(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(
                        a ->
                                a.getAuthority().equals("ROLE_CATALOG_EDITOR")
                                        || a.getAuthority().equals("ROLE_ADMIN"));
    }

    @GetMapping("/profile")
    public UserProfileResponse findProfile(Authentication authentication) {
        return decorate(userProfileService.findByEmail(authentication.getName()), authentication);
    }

    @PutMapping("/profile/daily-calorie-target")
    public UserProfileResponse updateDailyCalorieTarget(
            Authentication authentication,
            @Valid @RequestBody UpdateDailyCalorieTargetRequest request) {
        return decorate(
                userProfileService.updateDailyCalorieTarget(authentication.getName(), request),
                authentication);
    }

    @GetMapping("/energy-estimate")
    public EnergyEstimateResponse estimate(Authentication authentication) {
        return energyEstimateService.estimate(authentication.getName());
    }

    @PutMapping("/profile")
    public UserProfileResponse updateProfile(
            Authentication authentication, @Valid @RequestBody UpdateUserProfileRequest request) {

        return decorate(
                userProfileService.updateProfile(authentication.getName(), request),
                authentication);
    }
}
