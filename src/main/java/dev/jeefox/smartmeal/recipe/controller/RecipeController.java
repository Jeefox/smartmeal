package dev.jeefox.smartmeal.recipe.controller;

import dev.jeefox.smartmeal.recipe.dto.CreateRecipeRequest;
import dev.jeefox.smartmeal.recipe.dto.RecipeResponse;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.service.RecipeService;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @PostMapping
    public ResponseEntity<RecipeResponse> create(@Valid @RequestBody CreateRecipeRequest request){

        RecipeResponse recipeResponse = recipeService.createRecipe(request);

        URI location = URI.create("/api/recipes/"+recipeResponse.id());
        return ResponseEntity.created(location).body(recipeResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecipeResponse> findById (@PathVariable("id") UUID uuid){
        RecipeResponse response = recipeService.findById(uuid);
        return ResponseEntity.ok().body(response);
    }
}
