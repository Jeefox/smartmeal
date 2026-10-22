package dev.jeefox.smartmeal.recipe.controller;

import dev.jeefox.smartmeal.recipe.dto.RecipeImageResponse;
import dev.jeefox.smartmeal.recipe.service.RecipeImageService;
import dev.jeefox.smartmeal.storage.StoredObject;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/recipes/{recipeId}/images")
public class RecipeImageController {
    private final RecipeImageService imageService;

    public RecipeImageController(RecipeImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RecipeImageResponse> uploadCover(
            @PathVariable UUID recipeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "altText", required = false) String altText) {
        RecipeImageResponse response = imageService.uploadCover(recipeId, file, altText);
        return ResponseEntity.created(URI.create(response.url())).body(response);
    }

    @GetMapping(value = "/{imageId}/content", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> content(@PathVariable UUID recipeId, @PathVariable UUID imageId) {
        StoredObject object = imageService.download(recipeId, imageId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .contentType(MediaType.parseMediaType(object.contentType()))
                .body(object.content());
    }

    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(@PathVariable UUID recipeId, @PathVariable UUID imageId) {
        imageService.delete(recipeId, imageId);
        return ResponseEntity.noContent().build();
    }
}
