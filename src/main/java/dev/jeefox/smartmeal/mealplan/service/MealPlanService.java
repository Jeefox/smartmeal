package dev.jeefox.smartmeal.mealplan.service;

import dev.jeefox.smartmeal.exception.MealPlanAlreadyExistsException;
import dev.jeefox.smartmeal.exception.MenuEntryNotExistsException;
import dev.jeefox.smartmeal.exception.MealPlanNotEditableException;
import dev.jeefox.smartmeal.exception.MealPlanNotFoundException;
import dev.jeefox.smartmeal.exception.DailyCalorieTargetNotSetException;
import dev.jeefox.smartmeal.mealplan.dto.CreateMealPlanRequest;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanResponse;
import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanStatus;
import dev.jeefox.smartmeal.mealplan.dto.MealPlanEntryResponse;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanEntryRepository;
import dev.jeefox.smartmeal.user.entity.UserProfile;
import dev.jeefox.smartmeal.user.repository.UserProfileRepository;
import java.util.Comparator;
import java.util.List;
import dev.jeefox.smartmeal.mealplan.repository.MealPlanRepository;
import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class MealPlanService {
    private final UserRepository userRepository;
    private final MealPlanRepository mealPlanRepository;
    private final MealPlanEntryRepository entries;
    private final UserProfileRepository profiles;

    public MealPlanService(UserRepository userRepository, MealPlanRepository mealPlanRepository,
                           MealPlanEntryRepository entries, UserProfileRepository profiles) {
        this.userRepository = userRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.entries = entries;
        this.profiles = profiles;
    }

    @Transactional
    public MealPlanResponse create(
            String email, CreateMealPlanRequest request
    ){
        User user = userRepository.findByEmail(email).orElseThrow(()->
                new UsernameNotFoundException(email));
        final LocalDate planDate = request.planDate();
        mealPlanRepository.findByUser_IdAndPlanDate(user.getId(), planDate).ifPresent(
                mealPlan -> {
                    throw new MealPlanAlreadyExistsException(planDate);
                }
        );
        MealPlan savedMealPlan = mealPlanRepository.save(new MealPlan(user, planDate));
        return toResponse(savedMealPlan);
    }

    @Transactional(readOnly = true)
    public MealPlanResponse findByDate(String email, LocalDate date){
        User user = userRepository.findByEmail(email).orElseThrow(()-> new UsernameNotFoundException(email));
        MealPlan mealPlan = mealPlanRepository.findByUser_IdAndPlanDate(user.getId(), date)
                .orElseThrow(()-> new MealPlanNotFoundException(date));
        return toResponse(mealPlan);
    }

    @Transactional
    public MealPlanResponse confirmMenu(String email, UUID planId){
        MealPlan mealPlan = mealPlanRepository.findOwnedForUpdate(planId, email)
                .orElseThrow(()-> new MealPlanNotFoundException(planId));
        if(mealPlan.getStatus() != MealPlanStatus.DRAFT){
            throw new MealPlanNotEditableException();
        }
        if(!entries.existsByMealPlan_Id(mealPlan.getId())){
            throw new MenuEntryNotExistsException();
        }
        UserProfile profile =profiles.findById(mealPlan.getUser().getId())
                .orElseThrow(DailyCalorieTargetNotSetException::new);
        Integer dailyCalorieTarget = profile.getDailyCalorieTarget();
        if (dailyCalorieTarget == null || dailyCalorieTarget<=0){
            throw new DailyCalorieTargetNotSetException();
        }
        mealPlan.confirm(dailyCalorieTarget);
        return toResponse(mealPlan);
    }

    private MealPlanResponse toResponse(MealPlan plan) {
        List<MealPlanEntry> items = entries.findByMealPlan_IdOrderByMealTypeAscPositionAsc(plan.getId()).stream()
                .sorted(Comparator.comparingInt((MealPlanEntry entry) -> entry.getMealType().ordinal())
                        .thenComparingInt(MealPlanEntry::getPosition)).toList();
        Integer target = plan.getStatus() == MealPlanStatus.CONFIRMED
                ? plan.getDailyCalorieTargetSnapshot()
                : profiles.findById(plan.getUser().getId()).map(profile -> profile.getDailyCalorieTarget())
                        .orElse(null);
        return MealPlanResponse.from(plan, items.stream().map(MealPlanEntryResponse::from).toList(),
                MealPlanNutritionCalculator.calculate(items), target);
    }
}
