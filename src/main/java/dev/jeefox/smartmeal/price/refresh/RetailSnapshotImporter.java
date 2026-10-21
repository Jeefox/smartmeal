package dev.jeefox.smartmeal.price.refresh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Imports ordinary regional reference prices, never conditional/loyalty prices. */
@Configuration
public class RetailSnapshotImporter {
    private static final Logger LOG = LoggerFactory.getLogger(RetailSnapshotImporter.class);
    private static final Map<String, String> NAMES = Map.of("magnit", "Магнит", "globus", "Глобус",
            "perekrestok", "Перекрёсток", "pyaterochka", "Пятёрочка");
    private static final Map<String, String> HOSTS = Map.of("magnit", "magnit.ru", "globus", "www.globus.ru",
            "perekrestok", "www.perekrestok.ru", "pyaterochka", "5d.5ka.ru");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final CatalogueProductMatcher matcher;
    private final Path directory;

    public RetailSnapshotImporter(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
                                  CatalogueProductMatcher matcher,
                                  @Value("${smartmeal.prices.retail.snapshot-directory:data/runtime}") String directory) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
        this.matcher = matcher;
        this.directory = Path.of(directory);
    }

    @Scheduled(initialDelayString = "${smartmeal.prices.retail.import-delay-ms:60000}",
            fixedDelayString = "${smartmeal.prices.retail.import-interval-ms:3600000}")
    public void importLatest() {
        for (String chain : NAMES.keySet()) {
            Path sourceDirectory = directory.resolve(chain);
            importFile(sourceDirectory.resolve("latest.json"), chain);
            for (String subdirectory : List.of("categories", "pages")) {
                Path snapshots = sourceDirectory.resolve(subdirectory);
                if (!Files.isDirectory(snapshots)) continue;
                try (var paths = Files.list(snapshots)) {
                    paths.filter(path -> path.getFileName().toString().matches("[A-Za-z0-9_-]+\\.json"))
                            .limit(5000).sorted().forEach(path -> importFile(path, chain));
                } catch (Exception error) {
                    LOG.warn("Cannot list retail snapshots for {}: {}", chain, error.getClass().getSimpleName());
                }
            }
        }
    }

    private void importFile(Path path, String chain) {
        if (!Files.isRegularFile(path)) return;
        try {
            if (Files.size(path) > 50_000_000) throw new IllegalArgumentException("SNAPSHOT_TOO_LARGE");
            Snapshot snapshot = mapper.readValue(Files.readString(path), Snapshot.class);
            validate(snapshot, chain);
            transaction.executeWithoutResult(status -> publish(snapshot));
        } catch (Exception error) {
            LOG.warn("Retail snapshot import failed for {}; previous prices retained: {}", chain,
                    error.getClass().getSimpleName());
        }
    }

    void validate(Snapshot snapshot, String chain) {
        if (!NAMES.containsKey(chain) || !chain.equals(snapshot.chain()) || snapshot.schemaVersion() != 1
                || !snapshot.complete() || snapshot.products() == null || snapshot.products().isEmpty()
                || snapshot.categories() == null || snapshot.categories().isEmpty()
                || !List.of("SELECTED_CATEGORIES", "ALL_DISCOVERED_CATEGORIES", "FOOD_CATEGORIES", "OBSERVED_PAGE").contains(snapshot.scope())) {
            throw new IllegalArgumentException("INVALID_SNAPSHOT");
        }
        if ("OBSERVED_PAGE".equals(snapshot.scope()) && (snapshot.categories().size() != 1
                || snapshot.categories().getFirst().page() == null || snapshot.categories().getFirst().page() <= 0)) {
            throw new IllegalArgumentException("INVALID_PAGE_SNAPSHOT");
        }
        Instant retrieved = Instant.parse(snapshot.retrievedAt());
        if (retrieved.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))
                || retrieved.isBefore(Instant.now().minus(15, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException("STALE_SNAPSHOT");
        }
        Context context = snapshot.sourceContext();
        if (context == null || !List.of("SITE_DEFAULT_STORE", "SITE_DEFAULT_REGION").contains(context.scope())
                || context.storeId() == null && context.cityId() == null
                || context.storeId() != null && !context.storeId().matches("[A-Za-z0-9_-]{1,40}")
                || context.cityId() != null && !context.cityId().matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("SOURCE_CONTEXT_MISSING");
        }
        var ids = new HashSet<String>();
        String prefix = switch (chain) {
            case "magnit" -> "/product/";
            case "globus" -> "/products/";
            case "perekrestok" -> "/cat/";
            default -> "/api/catalog/v2/stores/" + context.storeId() + "/categories/";
        };
        for (Item item : snapshot.products()) {
            URI url = URI.create(item.sourceUrl());
            if (item.id() == null || !ids.add(item.id()) || item.name() == null || item.name().isBlank()
                    || item.name().length() > 300 || item.packageLabel() == null || item.category() == null
                    || !"RUB".equals(item.currency()) || !"https".equals(url.getScheme())
                    || !HOSTS.get(chain).equals(url.getHost()) || url.getUserInfo() != null
                    || url.getPort() != -1 || !url.getPath().startsWith(prefix)) {
                throw new IllegalArgumentException("INVALID_PRODUCT");
            }
            if (item.regularPrice() != null) {
                BigDecimal price = new BigDecimal(item.regularPrice()).setScale(2, java.math.RoundingMode.UNNECESSARY);
                if (price.signum() <= 0 || price.compareTo(new BigDecimal("100000")) > 0) {
                    throw new IllegalArgumentException("INVALID_PRICE");
                }
            }
        }
    }

    private void publish(Snapshot snapshot) {
        Context context = snapshot.sourceContext();
        String source = NAMES.get(snapshot.chain()) + " · "
                + (context.storeId() != null ? context.storeId() : context.cityId());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtext(?))",
                Boolean.class, "retail-import-" + source))) return;
        Instant retrieved = Instant.parse(snapshot.retrievedAt());
        List<Ingredient> ingredients = jdbc.query("""
                select p.id,p.name from products p
                where exists(select 1 from recipe_ingredients i where i.product_id=p.id)
                """, (rs, row) -> new Ingredient(rs.getLong(1), rs.getString(2)));
        int saved = 0;
        for (Item item : snapshot.products()) {
            if (!item.available() || item.regularPrice() == null) {
                jdbc.update("update catalogue_price_references set active=false where source_url=? and retrieved_at<=?",
                        item.sourceUrl(), Timestamp.from(retrieved));
                continue;
            }
            for (Ingredient ingredient : ingredients) {
                if (!matcher.matches(ingredient.name(), item.name())
                        || !matcher.matchesCategory(ingredient.name(), item.category())) continue;
                CatalogueProductMatcher.Measure measure;
                try {
                    measure = matcher.measure(ingredient.name(), item.packageLabel(), item.soldByWeight());
                } catch (IllegalArgumentException unknownMeasure) {
                    continue;
                }
                String region = context.address() != null ? context.address()
                        : context.city() != null ? context.city() : "магазин " + context.storeId();
                String scope = "Региональная справочная цена сайта: " + region
                        + "; не цена выбранного магазина пользователя. Наличие не гарантировано.";
                if (measure.note() != null) scope += " " + measure.note();
                if (scope.length() > 300) scope = scope.substring(0, 300);
                saved += jdbc.update("""
                        insert into catalogue_price_references(product_id,name,package_weight_g,price,
                            source_name,source_url,source_scope,retrieved_at,expires_at,sold_by_weight)
                        values(?,?,?,?,?,?,?,?,?,?)
                        on conflict(product_id,source_url) do update set name=excluded.name,
                            package_weight_g=excluded.package_weight_g,price=excluded.price,
                            source_name=excluded.source_name,source_scope=excluded.source_scope,
                            retrieved_at=excluded.retrieved_at,expires_at=excluded.expires_at,
                            sold_by_weight=excluded.sold_by_weight,active=true
                        where catalogue_price_references.retrieved_at < excluded.retrieved_at
                        """, ingredient.id(), item.name(), measure.grams(), new BigDecimal(item.regularPrice()),
                        source, item.sourceUrl(), scope, Timestamp.from(retrieved),
                        Timestamp.from(retrieved.plus(15, ChronoUnit.DAYS)), item.soldByWeight());
            }
        }
        LOG.info("Imported retail reference snapshot: source={}, updatedPrices={}", source, saved);
    }

    record Ingredient(long id, String name) { }
    public record Snapshot(int schemaVersion, String chain, boolean complete, String retrievedAt,
                           String scope, Context sourceContext, List<Category> categories, List<Item> products) { }
    public record Context(String storeId, String address, String city, String cityId, String scope) { }
    public record Category(String id, Integer page) {
        public Category(String id) { this(id, null); }
    }
    public record Item(String id, String name, String sourceUrl, String regularPrice, String currency,
                       boolean soldByWeight, String packageLabel, String category, boolean available) { }
}
