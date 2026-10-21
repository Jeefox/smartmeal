package dev.jeefox.smartmeal.price.refresh;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class CatalogueRefreshStore {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${smartmeal.prices.auto-refresh.enabled:true}")
    private boolean refreshEnabled;

    public CatalogueRefreshStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CatalogueRefreshSource claim() {
        UUID token = UUID.randomUUID();
        var claimed = jdbc.query("""
                UPDATE catalogue_price_refresh_sources SET lease_token=?,lease_until=now()+interval '2 minutes',last_attempt_at=now()
                WHERE id=(SELECT id FROM catalogue_price_refresh_sources WHERE enabled AND next_attempt_at<=now()
                    AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id,product_id,source_url,expected_sku,expected_name,package_weight_g,sold_by_weight,expected_weight_label,conversion_note,expected_category,(SELECT shopping_name FROM products WHERE id=product_id)
                """, (rs, row) -> new CatalogueRefreshSource(rs.getLong(1),rs.getLong(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getInt(6),rs.getBoolean(7),token,rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11)), token);
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    @Transactional
    public void success(CatalogueRefreshSource source, BigDecimal price) {
        // Lock and check ownership before publishing, so an expired worker cannot overwrite a later attempt.
        var owned = jdbc.queryForList("SELECT id FROM catalogue_price_refresh_sources WHERE id=? AND lease_token=? AND lease_until>now() FOR UPDATE",
                Long.class,source.id(),source.leaseToken());
        if (owned.isEmpty()) return;
        boolean chizhik = source.url().startsWith("https://chizhik.club/");
        String sourceName = chizhik ? "Чижик · открытый каталог" : "ВкусВилл · интернет-каталог";
        String scope = chizhik
                ? "Открытый официальный каталог сети Чижик; наличие и цена конкретного магазина Тулы могут отличаться."
                : "Публичный каталог сети ВкусВилл; регион и цена в магазинах Тулы не подтверждены.";
        if (source.name().equals("Картофель")) scope += " Цена неочищенного картофеля; потери при очистке требуют уточнения.";
        if (source.conversionNote() != null) scope += " " + source.conversionNote();
        jdbc.update("""
                INSERT INTO catalogue_price_references(product_id,name,package_weight_g,price,source_name,source_url,
                    source_scope,retrieved_at,expires_at,sold_by_weight)
                VALUES(?,?,?,?,?,?,?,now(),now()+interval '3 days',?)
                ON CONFLICT(product_id,source_url) DO UPDATE SET name=excluded.name,package_weight_g=excluded.package_weight_g,
                    price=excluded.price,source_name=excluded.source_name,source_scope=excluded.source_scope,retrieved_at=excluded.retrieved_at,
                    expires_at=excluded.expires_at,sold_by_weight=excluded.sold_by_weight,active=true
                """,source.productId(),source.name(),source.weightG(),price,sourceName,source.url(),scope,source.soldByWeight());
        jdbc.update("INSERT INTO catalogue_price_refresh_history(source_id,checked_at,price) VALUES(?,now(),?)",source.id(),price);
        jdbc.update("""
                UPDATE catalogue_price_refresh_sources SET last_success_at=now(),failures=0,last_error=NULL,
                    next_attempt_at=now()+interval '1 day',lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?
                """,source.id(),source.leaseToken());
    }

    @Transactional
    public void failure(CatalogueRefreshSource source, String error) {
        var owned=jdbc.queryForList("SELECT id FROM catalogue_price_refresh_sources WHERE id=? AND lease_token=? AND lease_until>now() FOR UPDATE",
                Long.class,source.id(),source.leaseToken());
        if (owned.isEmpty()) return;
        if (error.equals("UNSAFE_PRODUCT_MATCH")) {
            jdbc.update("UPDATE catalogue_price_references SET active=false WHERE product_id=? AND source_url=?",source.productId(),source.url());
            jdbc.update("UPDATE catalogue_price_refresh_sources SET enabled=false WHERE id=? AND lease_token=?",source.id(),source.leaseToken());
            jdbc.update("UPDATE catalogue_product_discovery SET next_attempt_at=now() WHERE product_id=?",source.productId());
        }
        jdbc.update("""
                UPDATE catalogue_price_refresh_sources SET failures=failures+1,last_error=?,
                    next_attempt_at=now()+CASE WHEN failures=0 THEN interval '1 hour'
                    WHEN failures=1 THEN interval '4 hours' ELSE interval '1 day' END,
                    lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?
                """,error,source.id(),source.leaseToken());
    }

    @Transactional(readOnly = true)
    public List<Status> status() {
        return jdbc.query("""
                SELECT s.id,p.name,s.source_url,s.enabled,s.last_attempt_at,s.last_success_at,s.next_attempt_at,s.last_error
                FROM catalogue_price_refresh_sources s JOIN products p ON p.id=s.product_id ORDER BY s.id
                """, (rs,row) -> new Status(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getBoolean(4) && refreshEnabled,
                instant(rs.getObject(5,OffsetDateTime.class)),instant(rs.getObject(6,OffsetDateTime.class)),
                instant(rs.getObject(7,OffsetDateTime.class)),rs.getString(8)));
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    public record Status(long id, String productName, String sourceUrl, boolean enabled, Instant lastAttemptAt,
                         Instant lastSuccessAt, Instant nextAttemptAt, String lastError) {
    }
}
