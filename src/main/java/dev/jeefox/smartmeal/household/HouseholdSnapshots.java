package dev.jeefox.smartmeal.household;

import static dev.jeefox.smartmeal.household.HouseholdModels.*;

import dev.jeefox.smartmeal.mealplan.dto.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class HouseholdSnapshots {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public HouseholdSnapshots(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(UUID planId, List<Participant> people) {
        if (people.size() < 2) return;
        String json = mapper.writeValueAsString(people);
        jdbc.update(
                "insert into meal_plan_households(meal_plan_id,participants) values(:id,cast(:p as"
                        + " jsonb))",
                Map.of("id", planId, "p", json));
    }

    public List<Participant> people(UUID planId) {
        var values =
                jdbc.queryForList(
                        "select participants::text from meal_plan_households where"
                                + " meal_plan_id=:id",
                        Map.of("id", planId),
                        String.class);
        return values.isEmpty()
                ? List.of()
                : mapper.readValue(values.getFirst(), new TypeReference<List<Participant>>() {});
    }

    public Integer target(UUID planId, Integer fallback) {
        var ps = people(planId);
        if (ps.isEmpty()) return fallback;
        return ps.stream().mapToInt(Participant::dailyCalorieTarget).sum();
    }

    public MealPlanEntryResponse decorate(MealPlanEntryResponse entry) {
        return decorate(entry, people(entry.mealPlanId()));
    }

    public MealPlanEntryResponse decorate(MealPlanEntryResponse entry, List<Participant> people) {
        return new MealPlanEntryResponse(
                entry.id(),
                entry.mealPlanId(),
                entry.mealType(),
                entry.portions(),
                entry.position(),
                entry.recipeSnapshot(),
                entry.nutrition(),
                HouseholdPortions.allocate(entry.portions(), entry.recipeSnapshot(), people));
    }
}
