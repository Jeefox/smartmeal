package dev.jeefox.smartmeal.product.controller;

import dev.jeefox.smartmeal.product.dto.ProductCategoryResponse;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.product.service.ProductCategoryService;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/product-categories")
public class ProductCategoryController {

    private final ProductCategoryService productCategoryService;

    public ProductCategoryController(ProductCategoryService productCategoryService) {
        this.productCategoryService = productCategoryService;
    }

    @GetMapping
    public List<ProductCategoryResponse> getCategories(){
        return productCategoryService.getCategories().stream()
                .map(ProductCategoryController::productCategoryToResponse)
                .toList();
    }

    private static @NonNull ProductCategoryResponse productCategoryToResponse(ProductCategory productCategory) {
        ProductCategoryResponse response = new ProductCategoryResponse(productCategory.getId(),
                productCategory.getCode(), productCategory.getName());
        return response;
    }
}
