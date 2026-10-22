package dev.jeefox.smartmeal.household;

import static dev.jeefox.smartmeal.household.HouseholdModels.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class HouseholdService {
    private final NamedParameterJdbcTemplate jdbc;

    public HouseholdService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private UUID owner(String email, boolean lock) {
        var ids =
                jdbc.query(
                        "select id from users where email=:email" + (lock ? " for update" : ""),
                        Map.of("email", email),
                        (rs, n) -> rs.getObject(1, UUID.class));
        if (ids.isEmpty())
            throw new HouseholdException(HttpStatus.UNAUTHORIZED, "Войдите в аккаунт");
        return ids.getFirst();
    }

    @Transactional(readOnly = true)
    public List<Member> list(String email) {
        return listForOwner(owner(email, false));
    }

    public List<Member> listForOwner(UUID owner) {
        return jdbc.query(
                "select id,name,daily_calorie_target from household_members where user_id=:u and"
                    + " active order by created_at,id",
                Map.of("u", owner),
                (rs, n) ->
                        member(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getInt("daily_calorie_target")));
    }

    private Member member(UUID id, String name, int target) {
        return new Member(
                id,
                name,
                target,
                jdbc.queryForList(
                        "select allergen_id from household_member_allergies where member_id=:m"
                            + " order by allergen_id",
                        Map.of("m", id),
                        Long.class),
                jdbc.queryForList(
                        "select product_id from household_member_exclusions where member_id=:m"
                            + " order by product_id",
                        Map.of("m", id),
                        Long.class),
                jdbc.query(
                        "select p.id,p.name from products p join household_member_exclusions x on"
                            + " x.product_id=p.id where x.member_id=:m order by p.name,p.id",
                        Map.of("m", id),
                        (rs, n) -> new ProductChoice(rs.getLong(1), rs.getString(2))));
    }

    public Member create(String email, MemberRequest request) {
        UUID owner = owner(email, true);
        if (listForOwner(owner).size() >= 9)
            throw new HouseholdException(
                    HttpStatus.CONFLICT, "Можно добавить до 9 человек помимо вашего профиля");
        UUID id = UUID.randomUUID();
        validate(request);
        jdbc.update(
                "insert into household_members(id,user_id,name,daily_calorie_target)"
                    + " values(:id,:u,:name,:target)",
                Map.of(
                        "id",
                        id,
                        "u",
                        owner,
                        "name",
                        request.name().trim(),
                        "target",
                        request.dailyCalorieTarget()));
        restrictions(id, request);
        return member(id, request.name().trim(), request.dailyCalorieTarget());
    }

    public Member update(String email, UUID id, MemberRequest request) {
        UUID owner = owner(email, true);
        owned(owner, id);
        validate(request);
        jdbc.update(
                "update household_members set"
                    + " name=:name,daily_calorie_target=:target,updated_at=now() where id=:id",
                Map.of(
                        "id",
                        id,
                        "name",
                        request.name().trim(),
                        "target",
                        request.dailyCalorieTarget()));
        restrictions(id, request);
        return member(id, request.name().trim(), request.dailyCalorieTarget());
    }

    public void remove(String email, UUID id) {
        UUID owner = owner(email, true);
        owned(owner, id);
        jdbc.update(
                "update household_members set active=false,updated_at=now() where id=:id",
                Map.of("id", id));
    }

    private void owned(UUID owner, UUID id) {
        if (jdbc.queryForObject(
                        "select count(*) from household_members where id=:id and user_id=:u and"
                            + " active",
                        Map.of("id", id, "u", owner),
                        Integer.class)
                == 0) throw new HouseholdException(HttpStatus.NOT_FOUND, "Человек не найден");
    }

    private void validate(MemberRequest r) {
        if (r.name() == null
                || r.name().isBlank()
                || r.name().trim().length() > 80
                || r.dailyCalorieTarget() == null
                || r.dailyCalorieTarget() < 500
                || r.dailyCalorieTarget() > 10000
                || r.allergenIds() == null
                || r.excludedProductIds() == null)
            throw new HouseholdException(
                    HttpStatus.BAD_REQUEST, "Проверьте имя, норму калорий и ограничения");
        checkIds(r.allergenIds(), "allergens");
        checkIds(r.excludedProductIds(), "products");
    }

    private void checkIds(List<Long> ids, String table) {
        if (new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new HouseholdException(
                    HttpStatus.BAD_REQUEST,
                    "Идентификаторы ограничений должны быть положительными и не повторяться");
        if (!ids.isEmpty()
                && jdbc.queryForObject(
                                "select count(*) from " + table + " where id in (:ids)",
                                Map.of("ids", ids),
                                Integer.class)
                        != ids.size())
            throw new HouseholdException(HttpStatus.BAD_REQUEST, "Часть ограничений не существует");
    }

    private void restrictions(UUID id, MemberRequest r) {
        jdbc.update("delete from household_member_allergies where member_id=:m", Map.of("m", id));
        jdbc.update("delete from household_member_exclusions where member_id=:m", Map.of("m", id));
        for (Long a : r.allergenIds())
            jdbc.update(
                    "insert into household_member_allergies values(:m,:v)",
                    Map.of("m", id, "v", a));
        for (Long p : r.excludedProductIds())
            jdbc.update(
                    "insert into household_member_exclusions values(:m,:v)",
                    Map.of("m", id, "v", p));
    }

    public List<Member> selected(UUID owner, List<UUID> ids) {
        var found =
                listForOwner(owner).stream()
                        .filter(m -> ids.contains(m.id()))
                        .sorted(Comparator.comparing(Member::id))
                        .toList();
        if (found.size() != ids.size())
            throw new HouseholdException(
                    HttpStatus.BAD_REQUEST,
                    "В подборе есть недоступный или удалённый профиль семьи");
        return found;
    }
}
