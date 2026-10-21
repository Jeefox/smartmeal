package dev.jeefox.smartmeal.price;

import dev.jeefox.smartmeal.price.service.CataloguePriceReferenceService;
import dev.jeefox.smartmeal.price.entity.*;
import dev.jeefox.smartmeal.product.entity.*;
import dev.jeefox.smartmeal.shopping.dto.*;
import dev.jeefox.smartmeal.shopping.service.ShoppingEstimateService;
import dev.jeefox.smartmeal.store.entity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class CataloguePriceReferenceIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private CataloguePriceReferenceService references;
    @Autowired private ShoppingEstimateService estimates;
    private Product product;
    private Store store;

    @BeforeEach
    void setUp() {
        String key = "REF_" + UUID.randomUUID();
        var category = new ProductCategory(key, "Тест"); em.persist(category);
        product = new Product(category, "Рис", null, 100, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE); em.persist(product);
        var chain = new RetailChain(key, "Тест"); em.persist(chain);
        store = new Store(chain, "Тула", "Тест"); em.persist(store);
        em.flush();
        em.createNativeQuery("""
                insert into catalogue_price_references(product_id,name,package_weight_g,price,source_name,source_url,
                    source_scope,retrieved_at,expires_at)
                values(:id,'Рис 900 г',900,79.90,'Тестовый каталог','https://example.invalid/price',
                    'Регион не подтверждён',now()-interval '1 day',now()+interval '1 day')
                """).setParameter("id", product.getId()).executeUpdate();
    }

    private ShoppingEstimateResponse estimate(String grams) {
        LocalDate date = LocalDate.of(2033, 1, 1);
        return estimates.estimate(new ShoppingListResponse(date,date,false,
                List.of(new ShoppingListItemResponse(product.getId(),product.getName(),new BigDecimal(grams)))),store.getId());
    }

    @Test
    void fillsUnknownCostFromCatalogueWithoutInventingStoreSkuOrConfirmingBudget() {
        var result = estimate("1000");
        assertFalse(result.complete());
        assertEquals(new BigDecimal("159.80"),result.knownPurchaseCost());
        var item = result.items().getFirst();
        assertTrue(item.referencePrice()); assertNull(item.storeProductId());
        assertEquals(2,item.packageCount());
        assertEquals("https://example.invalid/price",item.referenceSourceUrl());
        assertEquals("Регион не подтверждён",item.referenceSourceScope());
        assertEquals(new BigDecimal("800"),item.remainderG());
        assertEquals(1,references.forProducts(List.of(product.getId())).get(product.getId()).size());
    }

    @Test
    void actualStorePriceTakesPrecedenceEvenWhenReferenceIsCheaper() {
        var sku = new StoreProduct(store,product,"Настоящий товар",500); em.persist(sku);
        em.persist(new PriceObservation(sku,new BigDecimal("90"),Instant.now().minusSeconds(60),PriceSource.MANUAL,false)); em.flush();
        var result = estimate("1000");
        assertTrue(result.complete()); assertFalse(result.items().getFirst().referencePrice());
        assertEquals(new BigDecimal("180.00"),result.knownPurchaseCost());
        assertEquals(sku.getId(),result.items().getFirst().storeProductId());
    }

    @Test
    void weightedPriceUsesRequiredGramsRatherThanWholeKilogramPackages() {
        em.createNativeQuery("update catalogue_price_references set sold_by_weight=true,package_weight_g=1000,price=95 where product_id=:id")
                .setParameter("id",product.getId()).executeUpdate();
        var result = estimate("350");
        assertEquals(new BigDecimal("33.25"),result.knownPurchaseCost());
        assertNull(result.items().getFirst().packageCount());
        assertEquals(BigDecimal.ZERO,result.items().getFirst().remainderG());
        assertFalse(result.complete());
    }

    @Test
    void expiredReferenceIsIgnoredAndZeroRoundedNeedDoesNotBecomeFree() {
        assertNull(estimate("0.00").items().getFirst().purchaseCost());
        em.createNativeQuery("update catalogue_price_references set retrieved_at=now()-interval '3 days',expires_at=now()-interval '1 day' where product_id=:id")
                .setParameter("id",product.getId()).executeUpdate();
        assertTrue(references.forProducts(List.of(product.getId())).isEmpty());
        assertNull(estimate("100").items().getFirst().purchaseCost());
    }
}
