package dev.jeefox.smartmeal.product.controller;

import dev.jeefox.smartmeal.product.dto.CreateProductRequest;
import dev.jeefox.smartmeal.product.dto.ProductPageResponse;
import dev.jeefox.smartmeal.product.dto.ProductResponse;
import dev.jeefox.smartmeal.product.dto.UpdateProductRequest;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.service.ProductService;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request){
        Product product = productService.create(request);
        ProductResponse response = productToResponse(product);

        URI location = URI.create("/api/products/"+product.getId());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById (@PathVariable("id") Long id){
        Product product = productService.findById(id);
        ProductResponse response = productToResponse(product);
        return ResponseEntity.ok().body(response);
    }

    @GetMapping
    public ResponseEntity<ProductPageResponse> getProducts (
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "q", required = false) String name,
            @PageableDefault(size = 20, sort = "id") Pageable pageable){
        Page<ProductResponse> page = productService.findByName(categoryId, name, pageable)
                .map(ProductController::productToResponse);
        ProductPageResponse response = new ProductPageResponse(page);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateProductRequest updateProductRequest){
        Product product = productService.updateProduct(id, updateProductRequest);
        ProductResponse response = productToResponse(product);

        return ResponseEntity.ok(response);
    }

    private static @NonNull ProductResponse productToResponse(Product product) {
        ProductResponse response = new ProductResponse(product.getId(),
                product.getCategory().getId(), product.getName(),
                product.getDescription(), product.getCaloriesPer100g(),
                product.getProteinPer100g(), product.getFatPer100g(),
                product.getCarbsPer100g(), product.getCreatedAt(),
                product.getUpdatedAt(), product.getShoppingName(), product.getEdibleYield());
        return response;
    }
}
