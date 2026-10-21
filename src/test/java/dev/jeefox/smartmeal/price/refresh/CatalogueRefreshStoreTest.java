package dev.jeefox.smartmeal.price.refresh;

import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class CatalogueRefreshStoreTest {
    @Autowired CatalogueRefreshStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    long id;

    @BeforeEach void setup() {
        jdbc.update("update catalogue_price_refresh_sources set next_attempt_at=now()+interval '7 days'");
        var category = new ProductCategory("AUTO_"+UUID.randomUUID(),"Тест"); em.persist(category);
        var product = new Product(category,"Тестовый продукт",null,100,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE);
        em.persist(product); em.flush();
        id = jdbc.queryForObject("""
                insert into catalogue_price_refresh_sources(product_id,source_url,expected_sku,expected_name,package_weight_g)
                values(?,'https://vkusvill.ru/goods/test-1/','1','Тест',400) returning id
                """,Long.class,product.getId());
    }

    @Test void claimPreventsAnotherWorkerAndSuccessPublishesHistory() {
        var claimed = store.claim(); assertEquals(id,claimed.id()); assertNull(store.claim());
        store.success(claimed,new BigDecimal("45.00"));
        assertEquals(1,jdbc.queryForObject("select count(*) from catalogue_price_refresh_history where source_id=?",Integer.class,id));
        assertEquals(new BigDecimal("45.00"),jdbc.queryForObject("select price from catalogue_price_references where source_url=?",BigDecimal.class,claimed.url()));
        assertEquals(0,jdbc.queryForObject("select failures from catalogue_price_refresh_sources where id=?",Integer.class,id));
    }

    @Test void repeatedVerifiedPriceRefreshesFreshnessAndKeepsHistoryWithoutDuplicates() {
        var first=store.claim(); store.success(first,new BigDecimal("45.00"));
        jdbc.update("update catalogue_price_references set retrieved_at=now()-interval '1 day' where source_url=?",first.url());
        var before=jdbc.queryForObject("select retrieved_at from catalogue_price_references where source_url=?",java.sql.Timestamp.class,first.url());
        jdbc.update("update catalogue_price_refresh_sources set next_attempt_at=now() where id=?",id);
        store.success(store.claim(),new BigDecimal("45.00"));
        var after=jdbc.queryForObject("select retrieved_at from catalogue_price_references where source_url=?",java.sql.Timestamp.class,first.url());
        assertTrue(after.after(before));
        assertEquals(2,jdbc.queryForObject("select count(*) from catalogue_price_refresh_history where source_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("select count(*) from catalogue_price_references where source_url=?",Integer.class,first.url()));
    }

    @Test void failureKeepsOldPriceAndFreshnessAndSchedulesRetry() {
        var claimed = store.claim(); store.success(claimed,new BigDecimal("45.00"));
        var before = jdbc.queryForMap("select retrieved_at,expires_at from catalogue_price_references where source_url=?",claimed.url());
        jdbc.update("update catalogue_price_refresh_sources set next_attempt_at=now() where id=?",id);
        var second = store.claim(); store.failure(second,"SOURCE_UNAVAILABLE_OR_CHANGED");
        assertEquals(before,jdbc.queryForMap("select retrieved_at,expires_at from catalogue_price_references where source_url=?",claimed.url()));
        assertEquals(1,jdbc.queryForObject("select failures from catalogue_price_refresh_sources where id=?",Integer.class,id));
        assertNull(store.claim());
    }

    @Test void abandonedLeaseCanBeReclaimedAndOldWorkerCannotPublish() {
        var first = store.claim();
        jdbc.update("update catalogue_price_refresh_sources set lease_until=now()-interval '1 second' where id=?",id);
        var second = store.claim(); assertNotEquals(first.leaseToken(),second.leaseToken());
        store.success(first,new BigDecimal("1.00"));
        store.failure(first,"UNSAFE_PRODUCT_MATCH");
        assertTrue(jdbc.queryForObject("select enabled from catalogue_price_refresh_sources where id=?",Boolean.class,id));
        assertEquals(0,jdbc.queryForObject("select count(*) from catalogue_price_refresh_history where source_id=?",Integer.class,id));
        store.success(second,new BigDecimal("46.00"));
        assertEquals(new BigDecimal("46.00"),jdbc.queryForObject("select price from catalogue_price_references where source_url=?",BigDecimal.class,second.url()));
    }
}
