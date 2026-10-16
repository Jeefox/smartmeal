package dev.jeefox.smartmeal.product.service;

import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.product.repository.ProductCategoryRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductCategoryService {

    private final ProductCategoryRepository categoryRepository;

    public ProductCategoryService(ProductCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductCategory> getCategories() {
        return categoryRepository.findAll(Sort.by("code"));
    }
}
