package dev.jeefox.smartmeal.recipe.service;

import dev.jeefox.smartmeal.exception.InvalidRecipeImageException;
import dev.jeefox.smartmeal.exception.RecipeImageNotFoundException;
import dev.jeefox.smartmeal.exception.RecipeNotFoundException;
import dev.jeefox.smartmeal.recipe.dto.RecipeImageResponse;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeImage;
import dev.jeefox.smartmeal.recipe.repository.RecipeImageRepository;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import dev.jeefox.smartmeal.storage.ObjectStorage;
import dev.jeefox.smartmeal.storage.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
@Service
public class RecipeImageService {
    private static final int COVER_POSITION = 1;

    private final RecipeRepository recipeRepository;
    private final RecipeImageRepository imageRepository;
    private final RecipeImageProcessor processor;
    private final ObjectStorage storage;

    public RecipeImageService(RecipeRepository recipeRepository,
                              RecipeImageRepository imageRepository,
                              RecipeImageProcessor processor,
                              ObjectStorage storage) {
        this.recipeRepository = recipeRepository;
        this.imageRepository = imageRepository;
        this.processor = processor;
        this.storage = storage;
    }

    @Transactional
    public RecipeImageResponse uploadCover(UUID recipeId, MultipartFile file, String altText) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new RecipeNotFoundException(recipeId));
        String normalizedAlt = normalizeAltText(altText, recipe.getName());
        RecipeImageProcessor.ProcessedImage processed = processor.process(file);
        String newKey = "recipes/%s/%s/cover.jpg".formatted(recipeId, UUID.randomUUID());
        storage.put(newKey, processed.content(), processed.contentType());

        RecipeImage image = imageRepository.findByRecipe_IdAndPosition(recipeId, COVER_POSITION).orElse(null);
        String oldKey = image == null ? null : image.getObjectKey();
        if (image == null) {
            image = new RecipeImage(recipe, newKey, processed.contentType(), processed.content().length,
                    processed.width(), processed.height(), normalizedAlt, COVER_POSITION);
        } else {
            image.replace(newKey, processed.contentType(), processed.content().length,
                    processed.width(), processed.height(), normalizedAlt);
        }

        RecipeImage saved;
        try {
            saved = imageRepository.saveAndFlush(image);
        } catch (RuntimeException exception) {
            safeDelete(newKey);
            throw exception;
        }
        registerUploadCleanup(newKey, oldKey);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public StoredObject download(UUID recipeId, UUID imageId) {
        RecipeImage image = findOwnedImage(recipeId, imageId);
        return storage.get(image.getObjectKey());
    }

    @Transactional
    public void delete(UUID recipeId, UUID imageId) {
        RecipeImage image = findOwnedImage(recipeId, imageId);
        String key = image.getObjectKey();
        imageRepository.delete(image);
        imageRepository.flush();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeDelete(key);
            }
        });
    }

    private RecipeImage findOwnedImage(UUID recipeId, UUID imageId) {
        RecipeImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new RecipeImageNotFoundException(imageId));
        if (!image.getRecipe().getId().equals(recipeId)) {
            throw new RecipeImageNotFoundException(imageId);
        }
        return image;
    }

    private void registerUploadCleanup(String newKey, String oldKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    if (oldKey != null) safeDelete(oldKey);
                } else {
                    safeDelete(newKey);
                }
            }
        });
    }

    private void safeDelete(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Не удалось удалить объект {} из хранилища", key, exception);
        }
    }

    private static String normalizeAltText(String altText, String recipeName) {
        String value = altText == null || altText.isBlank() ? recipeName : altText.trim();
        if (value.length() > 300) {
            throw new InvalidRecipeImageException("Описание изображения не должно превышать 300 символов");
        }
        return value;
    }

    public static RecipeImageResponse toResponse(RecipeImage image) {
        return new RecipeImageResponse(image.getId(), contentUrl(image), image.getAltText(),
                image.getWidth(), image.getHeight());
    }

    public static String contentUrl(RecipeImage image) {
        UUID version = UUID.nameUUIDFromBytes(image.getObjectKey().getBytes(StandardCharsets.UTF_8));
        return "/api/recipes/%s/images/%s/content?v=%s"
                .formatted(image.getRecipe().getId(), image.getId(), version);
    }
}
