package dev.jeefox.smartmeal.product.service;

import dev.jeefox.smartmeal.exception.ProductCategoryNotFoundException;
import dev.jeefox.smartmeal.exception.ProductNotFoundException;
import dev.jeefox.smartmeal.product.dto.CreateProductRequest;
import dev.jeefox.smartmeal.product.dto.UpdateProductRequest;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.product.repository.ProductCategoryRepository;
import dev.jeefox.smartmeal.product.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductCategoryRepository productCategoryRepository;
    private final ProductRepository productRepository;

    public ProductService(ProductCategoryRepository productCategoryRepository, ProductRepository productRepository) {
        this.productCategoryRepository = productCategoryRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Product create(CreateProductRequest request) {
        ProductCategory category = productCategoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ProductCategoryNotFoundException(request.categoryId()));
        Product product = new Product(category, request.name(),
                request.description(), request.caloriesPer100g(), request.proteinPer100g(),
                request.fatPer100g(), request.carbsPer100g());

        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Product findById(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Product> findByName(Long categoryId, String name, Pageable pageable) {

        boolean hasQuery = name != null && !name.isBlank();
        boolean hasCategory = categoryId != null;

        if (hasQuery && hasCategory) {
            return productRepository.findByNameContainingIgnoreCaseAndCategory_Id(name.trim(), categoryId, pageable);
        } else if (hasQuery) {
            return productRepository.findByNameContainingIgnoreCase(name.trim(), pageable);
        } else if (hasCategory) {
            return productRepository.findByCategory_Id(categoryId, pageable);
        } else {
            return productRepository.findAll(pageable);
        }
    }

    @Transactional
    public Product updateProduct(Long productId, UpdateProductRequest request){
        Product product = productRepository.findById(productId).orElseThrow(()-> new ProductNotFoundException(productId));
        ProductCategory category = productCategoryRepository.findById(request.categoryId())
                .orElseThrow(()->new ProductCategoryNotFoundException(request.categoryId()));


        product.updateProduct(category, request.name(), request.description(),
                request.caloriesPer100g(), request.proteinPer100g(),
                request.fatPer100g(), request.carbsPer100g());
        return product;
    }
}
