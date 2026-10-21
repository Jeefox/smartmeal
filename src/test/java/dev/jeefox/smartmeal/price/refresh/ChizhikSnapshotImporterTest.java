package dev.jeefox.smartmeal.price.refresh;

import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class ChizhikSnapshotImporterTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired CatalogueProductMatcher matcher;
    @TempDir Path directory;

    @Test
    void importsMatchedPriceWithoutRenewingDateAndDeactivatesUnavailableOffer() throws Exception {
        String store = "TEST" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String source = "Чижик · " + store;
        var category = new ProductCategory("COLL_" + UUID.randomUUID(), "Крупы");
        em.persist(category);
        var product = new Product(category, "Гречневая крупа", null, 350,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        em.persist(product);
        em.flush();
        UUID recipe = UUID.randomUUID();
        jdbc.update("""
                insert into recipes(id,name,instructions,preparation_time_min,servings,created_at,updated_at)
                values(?,'Тест','Тест',1,1,now(),now())
                """, recipe);
        jdbc.update("""
                insert into recipe_ingredients(id,recipe_id,product_id,quantity_g,position)
                values(?,?,?,100,1)
                """, UUID.randomUUID(), recipe, product.getId());
        Path path = directory.resolve("snapshot.json");
        var importer = new ChizhikSnapshotImporter(jdbc, transaction, mapper, matcher, path.toString(), store);
        Instant old = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Files.writeString(path, mapper.writeValueAsString(snapshot(store, old, true, "39.0")));
        importer.importLatest();
        assertEquals(new BigDecimal("39.00"), jdbc.queryForObject(
                "select price from catalogue_price_references where source_name=? and product_id=?",
                BigDecimal.class, source, product.getId()));
        Files.writeString(path, mapper.writeValueAsString(snapshot(store, old, true, "99.0")));
        importer.importLatest();
        assertEquals(new BigDecimal("39.00"), jdbc.queryForObject(
                "select price from catalogue_price_references where source_name=? and product_id=?",
                BigDecimal.class, source, product.getId()));
        Files.writeString(path, mapper.writeValueAsString(snapshot(store, Instant.now(), false, null)));
        importer.importLatest();
        assertEquals(false, jdbc.queryForObject(
                "select active from catalogue_price_references where source_name=? and product_id=?",
                Boolean.class, source, product.getId()));
    }

    @Test
    void rejectsWrongStoreAndExpiredSnapshot() {
        var importer = new ChizhikSnapshotImporter(jdbc, transaction, mapper, matcher,
                directory.resolve("snapshot.json").toString(), "HDC5");
        assertThrows(IllegalArgumentException.class, () -> importer.validate(
                snapshot("OTHER", Instant.now(), true, "39.0")));
        assertThrows(IllegalArgumentException.class, () -> importer.validate(
                snapshot("HDC5", Instant.now().minus(16, ChronoUnit.DAYS), true, "39.0")));
    }

    private ChizhikSnapshotImporter.Snapshot snapshot(String store, Instant date, boolean available, String price) {
        var item = new ChizhikSnapshotImporter.Item("3231481", "Гречка ядрица 900г", "шт",
                "900 г", price, available, "Крупы и макароны");
        var category = new ChizhikSnapshotImporter.Category("264C39160",
                "https://app.chizhik.club/delivery/api/catalog/v2/stores/" + store
                        + "/categories/264C39160/overview?mode=store", List.of(item));
        return new ChizhikSnapshotImporter.Snapshot(1, store, true, date.toString(), List.of(category));
    }
}
