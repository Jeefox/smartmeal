package dev.jeefox.smartmeal.nutrition.service;

import dev.jeefox.smartmeal.exception.ProfileIncompleteException;
import dev.jeefox.smartmeal.exception.UserProfileNotFoundException;
import dev.jeefox.smartmeal.nutrition.CalorieTargetCalculator;
import dev.jeefox.smartmeal.nutrition.DailyEnergyCalculator;
import dev.jeefox.smartmeal.nutrition.RestingEnergyCalculator;
import dev.jeefox.smartmeal.nutrition.dto.EnergyEstimateResponse;
import dev.jeefox.smartmeal.user.dto.UserProfileResponse;
import dev.jeefox.smartmeal.user.entity.ActivityLevel;
import dev.jeefox.smartmeal.user.entity.Goal;
import dev.jeefox.smartmeal.user.entity.Sex;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import dev.jeefox.smartmeal.user.repository.UserProfileRepository;
import dev.jeefox.smartmeal.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EnergyEstimateServiceTest {

    private static final String EMAIL = "estimate@example.invalid";
    private static final UUID USER_ID = UUID.fromString("f9ab807b-3422-4c85-a41c-45d2abead7b0");

    private UserRepository users;
    private UserProfileRepository profiles;
    private EnergyEstimateService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(UserProfileRepository.class);
        RestingEnergyCalculator resting = new RestingEnergyCalculator();
        service = new EnergyEstimateService(users, profiles,
                new DailyEnergyCalculator(resting), resting, new CalorieTargetCalculator());
        user = new User(EMAIL, "unused-test-hash");
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    @ParameterizedTest
    @CsvSource({
            "LOSE_WEIGHT, 2884",
            "MAINTAIN_WEIGHT, 3204",
            "GAIN_WEIGHT, 3524"
    })
    void estimatesEachGoalWithoutRequiringOrSettingConfirmedTarget(Goal goal, String expected) {
        UserProfile profile = completeProfile(goal);
        givenProfile(profile);
        UserProfileResponse before = UserProfileResponse.from(profile);

        EnergyEstimateResponse result = service.estimate(EMAIL);

        assertEquals(estimate("1780", "3204", expected), result);
        assertNull(profile.getDailyCalorieTarget());
        assertNull(profile.getTargetWeightKg());
        assertEquals(before, UserProfileResponse.from(profile));
        verifyReadOnlyRepositoryCalls();
    }

    @Test
    void roundsRestingEnergyOnlyForResponseNotBeforeDailyCalculation() {
        UserProfile profile = completeProfile(Goal.MAINTAIN_WEIGHT);
        profile.updateProfile((short) 30, Sex.MALE, 177,
                new BigDecimal("78.05"), ActivityLevel.MODERATE, Goal.MAINTAIN_WEIGHT);
        givenProfile(profile);

        EnergyEstimateResponse result = service.estimate(EMAIL);

        // 1741.75 * 1.8 = 3135.15; early rounding to 1742 would produce 3136.
        assertEquals(estimate("1742", "3135", "3135"), result);
    }

    @Test
    void roundsRecommendationHalfUpUsingUnroundedMaintenanceAndPreservesProfile() {
        UserProfile profile = completeProfile(Goal.LOSE_WEIGHT);
        profile.updateProfile((short) 30, Sex.FEMALE, 177,
                new BigDecimal("78.05"), ActivityLevel.MODERATE, Goal.LOSE_WEIGHT);
        profile.updateDailyCalorieTarget(2100);
        givenProfile(profile);
        UserProfileResponse before = UserProfileResponse.from(profile);

        EnergyEstimateResponse result = service.estimate(EMAIL);

        // 1575.75 * 1.8 = 2836.35; * 0.9 = 2552.715, rounded to 2553.
        // Using the rounded maintenance (2836) would incorrectly produce 2552.
        assertEquals(estimate("1576", "2836", "2553"), result);
        assertEquals(before, UserProfileResponse.from(profile));
        verifyReadOnlyRepositoryCalls();
    }

    @Test
    void roundsExactHalfUp() {
        UserProfile profile = completeProfile(Goal.MAINTAIN_WEIGHT);
        profile.updateProfile((short) 30, Sex.MALE, 177,
                new BigDecimal("78.05"), ActivityLevel.HIGH, Goal.MAINTAIN_WEIGHT);
        givenProfile(profile);

        assertEquals(estimate("1742", "3484", "3484"), service.estimate(EMAIL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"age", "sex", "heightCm", "weightKg", "activityLevel", "goal"})
    void rejectsEachMissingRequiredFieldWithoutModifyingProfile(String field) {
        UserProfile profile = completeProfile(Goal.MAINTAIN_WEIGHT);
        ReflectionTestUtils.setField(profile, field, null);
        profile.updateDailyCalorieTarget(2100);
        givenProfile(profile);
        UserProfileResponse before = UserProfileResponse.from(profile);

        assertThrows(ProfileIncompleteException.class, () -> service.estimate(EMAIL));

        assertEquals(before, UserProfileResponse.from(profile));
        verifyReadOnlyRepositoryCalls();
    }

    @Test
    void rejectsEmptyProfile() {
        givenProfile(new UserProfile(user));

        assertThrows(ProfileIncompleteException.class, () -> service.estimate(EMAIL));
    }

    @Test
    void rejectsMissingUserWithoutLookingUpProfile() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> service.estimate(EMAIL));

        verifyNoInteractions(profiles);
    }

    @Test
    void rejectsMissingProfile() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(profiles.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(UserProfileNotFoundException.class, () -> service.estimate(EMAIL));
    }

    private UserProfile completeProfile(Goal goal) {
        UserProfile profile = new UserProfile(user);
        ReflectionTestUtils.setField(profile, "userId", USER_ID);
        profile.updateProfile((short) 30, Sex.MALE, 180,
                new BigDecimal("80"), ActivityLevel.MODERATE, goal);
        return profile;
    }

    private void givenProfile(UserProfile profile) {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(profiles.findById(USER_ID)).thenReturn(Optional.of(profile));
    }

    private void verifyReadOnlyRepositoryCalls() {
        verify(users).findByEmail(EMAIL);
        verify(profiles).findById(USER_ID);
        verifyNoMoreInteractions(users, profiles);
    }

    private static EnergyEstimateResponse estimate(String resting, String maintenance, String recommended) {
        return new EnergyEstimateResponse(new BigDecimal(resting),
                new BigDecimal(maintenance), new BigDecimal(recommended));
    }
}
