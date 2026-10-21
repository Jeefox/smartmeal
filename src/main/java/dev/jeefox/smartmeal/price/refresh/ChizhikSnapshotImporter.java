package dev.jeefox.smartmeal.price.refresh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Publishes complete browser snapshots without renewing the original retrieval time. */
@Configuration
@EnableScheduling
public class ChizhikSnapshotImporter {
    private static final Logger LOG = LoggerFactory.getLogger(ChizhikSnapshotImporter.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final CatalogueProductMatcher matcher;
    private final Path path;
    private final String storeId;

    public ChizhikSnapshotImporter(JdbcTemplate jdbc, TransactionTemplate transaction,
                                   ObjectMapper mapper, CatalogueProductMatcher matcher,
                                   @Value("${smartmeal.prices.chizhik.snapshot:data/runtime/chizhik/latest.json}") String path,
                                   @Value("${smartmeal.prices.chizhik.store-id:HDC5}") String storeId) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
        this.matcher = matcher;
        this.path = Path.of(path);
        this.storeId = storeId;
    }

    @Scheduled(initialDelayString = "${smartmeal.prices.chizhik.import-delay-ms:60000}",
            fixedDelayString = "${smartmeal.prices.chizhik.import-interval-ms:3600000}")
    public void importLatest() {
        if (!Files.isRegularFile(path)) return;
        try {
            if (Files.size(path) > 20_000_000) throw new IllegalArgumentException("SNAPSHOT_TOO_LARGE");
            Snapshot snapshot = mapper.readValue(Files.readString(path), Snapshot.class);
            validate(snapshot);
            transaction.executeWithoutResult(status -> publish(snapshot));
        } catch (Exception error) {
            LOG.warn("Chizhik snapshot import failed; previous prices retained: {}", error.getClass().getSimpleName());
        }
    }

    void validate(Snapshot snapshot) {
        if (snapshot.schemaVersion() != 1 || !snapshot.complete() || !storeId.equals(snapshot.storeId())
                || snapshot.categories() == null || snapshot.categories().isEmpty()) {
            throw new IllegalArgumentException("INVALID_SNAPSHOT");
        }
        Instant date = Instant.parse(snapshot.retrievedAt());
        if (date.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))
                || date.isBefore(Instant.now().minus(15, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException("STALE_SNAPSHOT");
        }
        for (Category category : snapshot.categories()) {
            String expected = "https://app.chizhik.club/delivery/api/catalog/v2/stores/" + storeId
                    + "/categories/" + category.id() + "/overview?mode=store";
            if (category.id() == null || !category.id().matches("[A-Z0-9]+")
                    || !expected.equals(category.sourceUrl()) || category.products() == null) {
                throw new IllegalArgumentException("INVALID_CATEGORY");
            }
            for (Item item : category.products()) {
                if (item.plu() == null || !item.plu().matches("[0-9]+") || item.name() == null
                        || item.name().isBlank() || item.name().length() > 300
                        || item.category() == null || item.packageLabel() == null) {
                    throw new IllegalArgumentException("INVALID_PRODUCT");
                }
                if (item.available()) {
                    BigDecimal price = new BigDecimal(item.regularPrice()).setScale(2, java.math.RoundingMode.UNNECESSARY);
                    if (price.signum() <= 0 || price.compareTo(new BigDecimal("100000")) > 0) {
                        throw new IllegalArgumentException("INVALID_PRICE");
                    }
                }
            }
        }
    }

    private void publish(Snapshot snapshot) {
        String sourceName = "Чижик · " + storeId;
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, "chizhik-import-" + storeId))) return;
        Instant retrieved = Instant.parse(snapshot.retrievedAt());
        var latest = jdbc.queryForObject("select max(retrieved_at) from catalogue_price_references where source_name=?",
                java.sql.Timestamp.class, sourceName);
        if (latest != null && !latest.toInstant().isBefore(retrieved)) return;
        var products = jdbc.query("""
                select p.id,p.name from products p
                where exists(select 1 from recipe_ingredients i where i.product_id=p.id)
                """, (rs, row) -> new Ingredient(rs.getLong(1), rs.getString(2)));
        jdbc.update("update catalogue_price_references set active=false where source_name=?", sourceName);
        int saved = 0;
        for (Category category : snapshot.categories()) {
            for (Item item : category.products()) {
                if (!item.available()) continue;
                for (Ingredient ingredient : products) {
                    if (!matcher.matches(ingredient.name(), item.name())
                            || !matcher.matchesCategory(ingredient.name(), item.category())) continue;
                    boolean weighted = "кг".equals(item.uom());
                    if (!weighted && !"шт".equals(item.uom())) continue;
                    CatalogueProductMatcher.Measure measure;
                    try {
                        measure = matcher.measure(ingredient.name(), item.packageLabel(), weighted);
                    } catch (IllegalArgumentException error) {
                        continue;
                    }
                    String scope = "Каталог магазина " + storeId + "; цена на дату загрузки, наличие не гарантировано.";
                    if ("HDC5".equals(storeId)) scope = "Чижик, Тула, Красноармейский проспект, 25; цена на дату загрузки, наличие не гарантировано.";
                    if (measure.note() != null) scope += " " + measure.note();
                    jdbc.update("""
                            insert into catalogue_price_references(product_id,name,package_weight_g,price,
                                source_name,source_url,source_scope,retrieved_at,expires_at,sold_by_weight)
                            values(?,?,?,?,?,?,?,?,?,?)
                            on conflict(product_id,source_url) do update set name=excluded.name,
                                package_weight_g=excluded.package_weight_g,price=excluded.price,
                                source_scope=excluded.source_scope,retrieved_at=excluded.retrieved_at,
                                expires_at=excluded.expires_at,sold_by_weight=excluded.sold_by_weight,active=true
                            """, ingredient.id(), item.name(), measure.grams(), new BigDecimal(item.regularPrice()),
                            sourceName, category.sourceUrl() + "#plu=" + item.plu(), scope,
                            java.sql.Timestamp.from(retrieved), java.sql.Timestamp.from(retrieved.plus(15, ChronoUnit.DAYS)), weighted);
                    saved++;
                }
            }
        }
        LOG.info("Imported Chizhik snapshot: store={}, matchedPrices={}", storeId, saved);
    }

    record Ingredient(long id, String name) { }
    public record Snapshot(int schemaVersion, String storeId, boolean complete, String retrievedAt,
                           java.util.List<Category> categories) { }
    public record Category(String id, String sourceUrl, java.util.List<Item> products) { }
    public record Item(String plu, String name, String uom, String packageLabel,
                       String regularPrice, boolean available, String category) { }
}
