package dev.jeefox.smartmeal.price;

import dev.jeefox.smartmeal.price.entity.PriceObservation;
import dev.jeefox.smartmeal.price.entity.PriceSource;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import dev.jeefox.smartmeal.store.entity.RetailChain;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.entity.StoreProduct;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PriceObservationPersistenceTest {
    @Autowired private EntityManager em;
    private StoreProduct storeProduct;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        RetailChain chain = new RetailChain("PRICE_" + suffix, "Тестовая сеть");
        em.persist(chain);
        Store store = new Store(chain, "Тестовый город", "Тестовый адрес");
        em.persist(store);
        ProductCategory category = new ProductCategory("PRICE_" + suffix, "Крупы");
        em.persist(category);
        Product product = new Product(category, "Гречка", null, 340,
                new BigDecimal("13"), new BigDecimal("3"), new BigDecimal("65"));
        em.persist(product);
        storeProduct = new StoreProduct(store, product, "Гречка 800 г", 800);
        em.persist(storeProduct);
    }

    @Test
    void savesProductAndManualPriceWithRelationsAndTimestamps() {
        Instant observedAt = Instant.parse("2026-10-01T09:00:00Z");
        PriceObservation observation = new PriceObservation(storeProduct, new BigDecimal("89.90"),
                observedAt, PriceSource.MANUAL, false);
        em.persist(observation);
        Long productId = storeProduct.getProduct().getId();
        Long storeId = storeProduct.getStore().getId();
        Long id = observation.getId();
        em.flush();
        em.clear();

        PriceObservation loaded = em.find(PriceObservation.class, id);
        assertEquals(new BigDecimal("89.90"), loaded.getPrice());
        assertEquals(observedAt, loaded.getObservedAt());
        assertEquals(PriceSource.MANUAL, loaded.getPriceSource());
        assertFalse(loaded.isPromotional());
        assertNotNull(loaded.getCreatedAt());
        StoreProduct item = loaded.getStoreProduct();
        assertEquals("Гречка 800 г", item.getName());
        assertEquals(800, item.getPackageWeightG());
        assertTrue(item.isActive());
        assertEquals(productId, item.getProduct().getId());
        assertEquals(storeId, item.getStore().getId());
        assertNotNull(item.getCreatedAt());
        assertNotNull(item.getUpdatedAt());
    }

    @Test
    void keepsPriceHistoryAndOrdersByObservationTimeNotInsertionTime() {
        PriceObservation newer = new PriceObservation(storeProduct, new BigDecimal("95.00"),
                Instant.parse("2026-10-02T09:00:00Z"), PriceSource.MANUAL, false);
        em.persist(newer);
        PriceObservation olderReceipt = new PriceObservation(storeProduct, new BigDecimal("85.00"),
                Instant.parse("2026-10-01T09:00:00Z"), PriceSource.RECEIPT, true);
        em.persist(olderReceipt);
        Long itemId = storeProduct.getId();
        Long newerId = newer.getId();
        Long olderId = olderReceipt.getId();
        em.flush();
        em.clear();

        List<PriceObservation> history = em.createQuery(
                        "select p from PriceObservation p where p.storeProduct.id = :id "
                                + "order by p.observedAt desc, p.id desc", PriceObservation.class)
                .setParameter("id", itemId).getResultList();
        assertEquals(List.of(newerId, olderId), history.stream().map(PriceObservation::getId).toList());
        assertEquals(PriceSource.RECEIPT, history.get(1).getPriceSource());
        assertTrue(history.get(1).isPromotional());
        assertEquals(new BigDecimal("85.00"), history.get(1).getPrice());
    }
}
