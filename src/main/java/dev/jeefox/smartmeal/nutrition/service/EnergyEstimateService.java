package dev.jeefox.smartmeal.nutrition.service;

import dev.jeefox.smartmeal.exception.ProfileIncompleteException;
import dev.jeefox.smartmeal.exception.UserProfileNotFoundException;
import dev.jeefox.smartmeal.nutrition.CalorieTargetCalculator;
import dev.jeefox.smartmeal.nutrition.DailyEnergyCalculator;
import dev.jeefox.smartmeal.nutrition.RestingEnergyCalculator;
import dev.jeefox.smartmeal.nutrition.dto.EnergyEstimateResponse;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import dev.jeefox.smartmeal.user.repository.UserProfileRepository;
import dev.jeefox.smartmeal.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class EnergyEstimateService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final DailyEnergyCalculator dailyEnergyCalculator;
    private final RestingEnergyCalculator restingEnergyCalculator;
    private final CalorieTargetCalculator calorieTargetCalculator;

    public EnergyEstimateService(UserRepository userRepository, UserProfileRepository userProfileRepository,
                                 DailyEnergyCalculator dailyEnergyCalculator,
                                 RestingEnergyCalculator restingEnergyCalculator, CalorieTargetCalculator calorieTargetCalculator) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.dailyEnergyCalculator = dailyEnergyCalculator;
        this.restingEnergyCalculator = restingEnergyCalculator;
        this.calorieTargetCalculator = calorieTargetCalculator;
    }

    @Transactional(readOnly = true)
    public EnergyEstimateResponse estimate(String email){
        User user = userRepository.findByEmail(email)
                .orElseThrow(()-> new UsernameNotFoundException("Пользователь не найден"));

        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElseThrow(()-> new UserProfileNotFoundException(user.getId()));

        validateProfileForEstimate(profile);

        BigDecimal restingResult = restingEnergyCalculator.calculate(profile.getWeightKg(), profile.getHeightCm(),
                profile.getAge(), profile.getSex());
        BigDecimal maintenanceResult = dailyEnergyCalculator.calculate(profile.getWeightKg(),
                profile.getHeightCm(), profile.getAge(), profile.getSex(),
                profile.getActivityLevel());
        BigDecimal recommendedCalories = calorieTargetCalculator.calculate(maintenanceResult, profile.getGoal());

        return new EnergyEstimateResponse(
                restingResult.setScale(0, RoundingMode.HALF_UP),
                maintenanceResult.setScale(0, RoundingMode.HALF_UP),
                recommendedCalories.setScale(0, RoundingMode.HALF_UP));
    }

    private void validateProfileForEstimate(UserProfile profile) {
        if (profile.getAge() == null
                || profile.getSex() == null
                || profile.getHeightCm() == null
                || profile.getWeightKg() == null
                || profile.getActivityLevel() == null
                || profile.getGoal() == null) {
            throw new ProfileIncompleteException();
        }
    }
}
