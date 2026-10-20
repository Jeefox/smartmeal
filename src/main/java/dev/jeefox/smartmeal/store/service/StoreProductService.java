package dev.jeefox.smartmeal.store.service;

import dev.jeefox.smartmeal.exception.InvalidStoreFilterException;
import dev.jeefox.smartmeal.exception.ProductNotFoundException;
import dev.jeefox.smartmeal.exception.StoreInactiveException;
import dev.jeefox.smartmeal.exception.StoreNotFoundException;
import dev.jeefox.smartmeal.exception.StoreProductNotFoundException;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.repository.ProductRepository;
import dev.jeefox.smartmeal.store.dto.CreateStoreProductRequest;
import dev.jeefox.smartmeal.store.dto.StoreProductResponse;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.entity.StoreProduct;
import dev.jeefox.smartmeal.store.repository.StoreProductRepository;
import dev.jeefox.smartmeal.store.repository.StoreRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreProductService {
    private final StoreRepository storeRepository;
    private final StoreProductRepository storeProductRepository;
    private final ProductRepository productRepository;

    public StoreProductService(StoreRepository storeRepository, StoreProductRepository storeProductRepository,
                               ProductRepository productRepository) {
        this.storeRepository = storeRepository;
        this.storeProductRepository = storeProductRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public StoreProductResponse create(CreateStoreProductRequest request){

        Store store = storeRepository.findById(request.storeId())
                .orElseThrow(()-> new StoreNotFoundException(request.storeId()));
        Product product = productRepository.findById(request.productId())
                .orElseThrow(()-> new ProductNotFoundException(request.productId()));

        StoreProduct savedProduct = storeProductRepository.save(
                new StoreProduct(store, product, request.name(), request.packageWeightG()));

        return StoreProductResponse.from(savedProduct);
    }

    @Transactional
    public StoreProductResponse changeActivity(Long id, boolean active) {
        StoreProduct product = storeProductRepository.findForUpdate(id)
                .orElseThrow(() -> new StoreProductNotFoundException(id));
        product.changeActivity(active);
        storeProductRepository.flush();
        return StoreProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public Page<StoreProductResponse> findAll(Long storeId, String name, Long productId, boolean active, Pageable pageable) {
        if (storeId == null || storeId <= 0 || (productId != null && productId <= 0)) {
            throw new InvalidStoreFilterException("Идентификаторы магазина и продукта должны быть положительными");
        }
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        if (!store.isActive()) {
            throw new StoreInactiveException(storeId);
        }
        String normalizedName = name == null ? "" : name.trim();
        Page<StoreProduct> products;
        if (!normalizedName.isEmpty() && productId != null) {
            products = storeProductRepository.findByStore_IdAndActiveAndNameContainingIgnoreCaseAndProduct_Id(
                    storeId, active, normalizedName, productId, pageable);
        } else if (!normalizedName.isEmpty()) {
            products = storeProductRepository.findByStore_IdAndActiveAndNameContainingIgnoreCase(
                    storeId, active, normalizedName, pageable);
        } else if (productId != null) {
            products = storeProductRepository.findByStore_IdAndActiveAndProduct_Id(storeId, active, productId, pageable);
        } else {
            products = storeProductRepository.findByStore_IdAndActive(storeId, active, pageable);
        }
        return products.map(StoreProductResponse::from);
    }

    @Transactional(readOnly = true)
    public StoreProductResponse findById(Long id){
        StoreProduct storeProduct = storeProductRepository.findById(id)
                .orElseThrow(()-> new StoreProductNotFoundException(id));

        return StoreProductResponse.from(storeProduct);
    }
}
