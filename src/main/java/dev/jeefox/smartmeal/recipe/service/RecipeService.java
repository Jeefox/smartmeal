package dev.jeefox.smartmeal.recipe.service;

import dev.jeefox.smartmeal.exception.DuplicateRecipeProductsException;
import dev.jeefox.smartmeal.exception.ProductNotFoundException;
import dev.jeefox.smartmeal.exception.RecipeNotFoundException;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.nutrition.NutritionCalculator;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.repository.ProductRepository;
import dev.jeefox.smartmeal.recipe.dto.*;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeImage;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.recipe.repository.RecipeImageRepository;
import dev.jeefox.smartmeal.recipe.repository.RecipeIngredientRepository;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RecipeService {
    private final RecipeRepository recipeRepository;
    private final ProductRepository productRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;
    private final RecipeImageRepository recipeImageRepository;

    public RecipeService(RecipeRepository recipeRepository, ProductRepository productRepository,
                         RecipeIngredientRepository recipeIngredientRepository,
                         RecipeImageRepository recipeImageRepository) {
        this.recipeRepository = recipeRepository;
        this.productRepository = productRepository;
        this.recipeIngredientRepository = recipeIngredientRepository;
        this.recipeImageRepository = recipeImageRepository;
    }

    @Transactional
    public RecipeResponse createRecipe(CreateRecipeRequest request) {
        List<Long> duplicates = request.ingredients().stream()
                .collect(Collectors.groupingBy(RecipeIngredientRequest::productId, Collectors.counting()))
                .entrySet().stream().filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey).toList();
        if (!duplicates.isEmpty()) throw new DuplicateRecipeProductsException(duplicates);

        List<Long> productIds = request.ingredients().stream().map(RecipeIngredientRequest::productId).toList();
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Recipe recipe = new Recipe(request.name(), request.description(), request.instructions(),
                request.preparationTimeMin(), request.servings());
        recipe.changeMealTypes(request.mealTypes());
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int i = 0; i < request.ingredients().size(); i++) {
            RecipeIngredientRequest item = request.ingredients().get(i);
            Product product = products.get(item.productId());
            if (product == null) throw new ProductNotFoundException(item.productId());
            ingredients.add(new RecipeIngredient(recipe, product, item.quantityG(), i + 1));
        }
        Recipe savedRecipe = recipeRepository.save(recipe);
        List<RecipeIngredient> savedIngredients = recipeIngredientRepository.saveAll(ingredients);
        return recipeToResponse(savedRecipe, savedIngredients, null);
    }

    @Transactional(readOnly = true)
    public RecipeResponse findById(UUID id) {
        Recipe recipe = recipeRepository.findById(id).orElseThrow(() -> new RecipeNotFoundException(id));
        List<RecipeIngredient> ingredients = recipeIngredientRepository.findByRecipe_IdOrderByPositionAsc(id);
        RecipeImage cover = recipeImageRepository.findByRecipe_IdAndPosition(id, 1).orElse(null);
        return recipeToResponse(recipe, ingredients, cover);
    }

    @Transactional(readOnly = true)
    public Page<RecipeSummaryResponse> findByName(String name, Pageable pageable) {
        Page<Recipe> recipes = name != null && !name.isBlank()
                ? recipeRepository.findByNameContainingIgnoreCase(name.trim(), pageable)
                : recipeRepository.findAll(pageable);
        return toSummaryPage(recipes);
    }

    @Transactional
    public RecipeSummaryResponse changeMealTypes(UUID id, UpdateRecipeMealTypesRequest request) {
        Recipe recipe = recipeRepository.findById(id).orElseThrow(() -> new RecipeNotFoundException(id));
        recipe.changeMealTypes(request.mealTypes());
        RecipeImage cover = recipeImageRepository.findByRecipe_IdAndPosition(id, 1).orElse(null);
        return recipeToSummary(recipe, cover);
    }

    @Transactional(readOnly = true)
    public Page<RecipeSummaryResponse> findCandidates(MealType mealType, int maxPreparationTimeMin,
                                                       String name, Pageable pageable) {
        String pattern = name == null || name.isBlank() ? null
                : "%" + name.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return toSummaryPage(recipeRepository.findCandidates(mealType, maxPreparationTimeMin, pattern, pageable));
    }

    private Page<RecipeSummaryResponse> toSummaryPage(Page<Recipe> page) {
        List<UUID> recipeIds = page.getContent().stream().map(Recipe::getId).toList();
        Map<UUID, RecipeImage> covers = recipeIds.isEmpty() ? Map.of()
                : recipeImageRepository.findByRecipe_IdInAndPosition(recipeIds, 1).stream()
                .collect(Collectors.toMap(image -> image.getRecipe().getId(), Function.identity()));
        List<RecipeSummaryResponse> content = page.getContent().stream()
                .map(recipe -> recipeToSummary(recipe, covers.get(recipe.getId()))).toList();
        return new PageImpl<>(content, page.getPageable(), page.getTotalElements());
    }

    private static RecipeSummaryResponse recipeToSummary(Recipe recipe, RecipeImage cover) {
        return new RecipeSummaryResponse(recipe.getId(), recipe.getName(), recipe.getDescription(),
                recipe.getPreparationTimeMin(), recipe.getServings(),
                recipe.getMealTypes().stream().sorted().toList(),
                cover == null ? null : RecipeImageService.contentUrl(cover));
    }

    private RecipeResponse recipeToResponse(Recipe recipe, List<RecipeIngredient> ingredients, RecipeImage cover) {
        List<RecipeIngredientResponse> responseList = ingredients.stream()
                .map(RecipeService::ingredientToResponse).toList();
        NutritionValues nutritionTotal = NutritionCalculator.calculate(ingredients);
        NutritionValues nutritionPerServing = NutritionCalculator.calculatePortion(ingredients, recipe.getServings());
        return new RecipeResponse(recipe.getId(), recipe.getName(), recipe.getDescription(), responseList,
                recipe.getInstructions(), recipe.getPreparationTimeMin(), recipe.getServings(), nutritionTotal,
                nutritionPerServing, recipe.getCreatedAt(), recipe.getUpdatedAt(),
                recipe.getMealTypes().stream().sorted().toList(),
                cover == null ? null : RecipeImageService.contentUrl(cover));
    }

    private static @NonNull RecipeIngredientResponse ingredientToResponse(RecipeIngredient ingredient) {
        return new RecipeIngredientResponse(ingredient.getId(), ingredient.getProduct().getId(),
                ingredient.getProduct().getName(), ingredient.getQuantityG(), ingredient.getPosition());
    }
}
