package dev.jeefox.smartmeal.store.service;

import dev.jeefox.smartmeal.exception.ProductNotFoundException;
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

    @Transactional(readOnly = true)
    public StoreProductResponse findById(Long id){
        StoreProduct storeProduct = storeProductRepository.findById(id)
                .orElseThrow(()-> new StoreProductNotFoundException(id));

        return StoreProductResponse.from(storeProduct);
    }
}
