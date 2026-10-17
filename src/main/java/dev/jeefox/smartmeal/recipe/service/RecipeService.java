package dev.jeefox.smartmeal.recipe.service;

import dev.jeefox.smartmeal.exception.DuplicateRecipeProductsException;
import dev.jeefox.smartmeal.exception.ProductNotFoundException;
import dev.jeefox.smartmeal.exception.RecipeNotFoundException;
import dev.jeefox.smartmeal.nutrition.NutritionCalculator;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.repository.ProductRepository;
import dev.jeefox.smartmeal.recipe.dto.*;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.entity.RecipeIngredient;
import dev.jeefox.smartmeal.recipe.repository.RecipeIngredientRepository;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
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

    public RecipeService(RecipeRepository recipeRepository, ProductRepository productRepository,
                         RecipeIngredientRepository recipeIngredientRepository) {
        this.recipeRepository = recipeRepository;
        this.productRepository = productRepository;
        this.recipeIngredientRepository = recipeIngredientRepository;
    }

    @Transactional
    public RecipeResponse createRecipe(CreateRecipeRequest request) {

        List<Long> duplicates = request.ingredients().stream()
                .collect(Collectors.groupingBy(RecipeIngredientRequest::productId, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!duplicates.isEmpty()) throw new DuplicateRecipeProductsException(duplicates);

        List<Long> iterable = request.ingredients()
                .stream()
                .map(RecipeIngredientRequest::productId)
                .toList();
        Map<Long, Product> products = productRepository.findAllById(iterable).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Recipe recipe = new Recipe(request.name(), request.description(),
                request.instructions(), request.preparationTimeMin(), request.servings());

        List<RecipeIngredient> ingredients = new ArrayList<>();

        for (int i = 0; i < request.ingredients().size(); i++) {
            RecipeIngredientRequest item = request.ingredients().get(i);

            Product product = products.get(item.productId());

            if (product == null) {
                throw new ProductNotFoundException(item.productId());
            }

            RecipeIngredient ingredient = new RecipeIngredient(
                    recipe,
                    product,
                    item.quantityG(),
                    i + 1
            );

            ingredients.add(ingredient);
        }
        Recipe savedRecipe = recipeRepository.save(recipe);

        List<RecipeIngredient> savedIngredients =
                recipeIngredientRepository.saveAll(ingredients);

        return recipeToResponse(savedRecipe, savedIngredients);
    }

    @Transactional(readOnly = true)
    public RecipeResponse findById(UUID uuid) {
        Recipe recipe = recipeRepository.findById(uuid)
                .orElseThrow(() -> new RecipeNotFoundException(uuid));

        List<RecipeIngredient> ingredients =
                recipeIngredientRepository.findByRecipe_IdOrderByPositionAsc(uuid);

        return recipeToResponse(recipe, ingredients);
    }

    @Transactional(readOnly = true)
    public Page<RecipeSummaryResponse> findByName(String name, Pageable pageable) {
        boolean hasName = name != null && !name.isBlank();
        Page<Recipe> recipes;
        if (hasName) {
            recipes = recipeRepository.findByNameContainingIgnoreCase(name.trim(), pageable);
        } else {
            recipes = recipeRepository.findAll(pageable);
        }
        return recipes.map(recipe -> new RecipeSummaryResponse(recipe.getId(),
                recipe.getName(), recipe.getDescription(),
                recipe.getPreparationTimeMin(), recipe.getServings()));
    }

    private RecipeResponse recipeToResponse(
            Recipe recipe,
            List<RecipeIngredient> ingredients) {

        List<RecipeIngredientResponse> responseList = ingredients.stream()
                .map(RecipeService::ingredientToResponse)
                .toList();

        NutritionValues nutritionTotal = NutritionCalculator.calculate(ingredients);
        NutritionValues nutritionPerServing = NutritionCalculator.calculatePortion(ingredients, recipe.getServings());

        return new RecipeResponse(
                recipe.getId(),
                recipe.getName(),
                recipe.getDescription(),
                responseList,
                recipe.getInstructions(),
                recipe.getPreparationTimeMin(),
                recipe.getServings(),
                nutritionTotal,
                nutritionPerServing,
                recipe.getCreatedAt(),
                recipe.getUpdatedAt()
        );
    }

    private static @NonNull RecipeIngredientResponse ingredientToResponse(RecipeIngredient recipeIngredient) {
        return new RecipeIngredientResponse(recipeIngredient.getId(),
                recipeIngredient.getProduct().getId(), recipeIngredient.getProduct().getName(),
                recipeIngredient.getQuantityG(), recipeIngredient.getPosition());
    }
}
