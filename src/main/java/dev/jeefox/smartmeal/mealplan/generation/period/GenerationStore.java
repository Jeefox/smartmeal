package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import dev.jeefox.smartmeal.user.repository.UserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class GenerationStore {
    private final MenuGenerationRepository generations;
    private final UserRepository users;
    private final dev.jeefox.smartmeal.pantry.PantryService pantry;

    public GenerationStore(
            MenuGenerationRepository generations,
            UserRepository users,
            dev.jeefox.smartmeal.pantry.PantryService pantry) {
        this.pantry = pantry;
        this.generations = generations;
        this.users = users;
    }

    @Transactional
    public View create(String email, Document document) {
        var owner = users.findByEmail(email).orElseThrow();
        return generations.saveAndFlush(new MenuGeneration(owner, document, Instant.now())).view();
    }

    @Transactional(readOnly = true)
    public View get(String email, UUID id) {
        var generation = owned(email, id, false);
        if (generation.getCommitKey() == null && !generation.getExpiresAt().isAfter(Instant.now()))
            throw new PlanningException(
                    HttpStatus.GONE,
                    "GENERATION_EXPIRED",
                    "Срок предпросмотра истёк. Подберите меню ещё раз.");
        return generation.view();
    }

    @Transactional
    public View replace(String email, UUID id, long revision, Document document) {
        var generation = owned(email, id, true);
        checkEditable(generation, revision);
        generation.replace(document);
        generations.flush();
        return generation.view();
    }

    @Transactional
    public View replaceQuoted(
            String email, UUID id, long revision, Document document, long pantryRevision) {
        pantry.lockOwner(email);
        if (document.options().usePantry()
                && pantry.snapshot(email, java.util.Set.of()).revision() != pantryRevision)
            throw PlanningException.conflict(
                    "REPLACEMENT_COMPARISON_CHANGED",
                    "Запасы изменились. Обновите варианты замены.");
        return replace(email, id, revision, document);
    }

    MenuGeneration owned(String email, UUID id, boolean lock) {
        return (lock
                        ? generations.findOwnedForUpdate(id, email)
                        : generations.findByIdAndUser_Email(id, email))
                .orElseThrow(
                        () ->
                                new PlanningException(
                                        HttpStatus.NOT_FOUND,
                                        "GENERATION_NOT_FOUND",
                                        "Предпросмотр не найден"));
    }

    static void checkEditable(MenuGeneration generation, long revision) {
        if (generation.getCommitKey() != null)
            throw PlanningException.conflict(
                    "GENERATION_ALREADY_SAVED",
                    "Меню уже сохранено. Создайте новый подбор для другого периода.");
        if (generation.getExpiresAt().isBefore(Instant.now()))
            throw new PlanningException(
                    HttpStatus.GONE,
                    "GENERATION_EXPIRED",
                    "Срок предпросмотра истёк. Подберите меню ещё раз.");
        if (generation.getRevision() != revision)
            throw PlanningException.conflict(
                    "GENERATION_REVISION_CONFLICT",
                    "Предпросмотр изменился в другом запросе. Обновите страницу.");
    }
}
