package dev.jeefox.smartmeal.price.refresh;

import org.jsoup.nodes.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CatalogueProductDiscovery {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CatalogueProductMatcher matcher;
    private static final Pattern PRODUCT_PATH = Pattern.compile("/product/[a-z0-9-]+--([0-9]+)/");
    private static final Pattern MEASURE = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(кг|г|мл|л|шт)(?![а-яa-z])", Pattern.CASE_INSENSITIVE);

    private final ChizhikOfficialPriceClient client;

    public CatalogueProductDiscovery(JdbcTemplate jdbc, TransactionTemplate transaction,
                                     CatalogueProductMatcher matcher, ChizhikOfficialPriceClient client) {
        this.client=client;
        this.jdbc=jdbc;
        this.transaction=transaction;
        this.matcher=matcher;
    }

    public void discoverNext() {
        var task=transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO catalogue_product_discovery(product_id)
                    SELECT DISTINCT product_id FROM recipe_ingredients ON CONFLICT DO NOTHING
                    """);
            UUID token=UUID.randomUUID();
            var tasks=jdbc.query("""
                    UPDATE catalogue_product_discovery d SET lease_token=?,lease_until=now()+interval '2 minutes',last_attempt_at=now()
                    WHERE product_id=(SELECT d2.product_id FROM catalogue_product_discovery d2
                        WHERE d2.next_attempt_at<=now() AND (d2.lease_until IS NULL OR d2.lease_until<now())
                        AND EXISTS(SELECT 1 FROM recipe_ingredients i WHERE i.product_id=d2.product_id)
                        AND NOT EXISTS(SELECT 1 FROM catalogue_price_refresh_sources s WHERE s.product_id=d2.product_id
                            AND s.enabled AND s.source_url LIKE 'https://chizhik.club/product/%')
                        ORDER BY d2.next_attempt_at,d2.product_id FOR UPDATE SKIP LOCKED LIMIT 1)
                    RETURNING product_id,(SELECT shopping_name FROM products WHERE id=d.product_id),
                        (SELECT c.code FROM products p JOIN product_categories c ON c.id=p.category_id WHERE p.id=d.product_id)
                    """,(rs,row) -> new Task(rs.getLong(1),rs.getString(2),rs.getString(3),token),token);
            return tasks.isEmpty() ? null : tasks.getFirst();
        });
        if (task==null) return;
        try {
            List<Candidate> candidates=candidates(client.categoryPages(task.categoryCode()),task.name());
            transaction.executeWithoutResult(status -> {
                var owned=jdbc.queryForList("SELECT product_id FROM catalogue_product_discovery WHERE product_id=? AND lease_token=? AND lease_until>now() FOR UPDATE",Long.class,task.id(),task.token());
                if (owned.isEmpty()) return;
                // Keep up to three different offers; each is independently verified on its product page later.
                for (var candidate:candidates.stream().limit(3).toList()) {
                    jdbc.update("""
                            INSERT INTO catalogue_price_refresh_sources(product_id,source_url,expected_sku,expected_name,
                                package_weight_g,sold_by_weight,expected_weight_label,conversion_note,expected_category)
                            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(product_id,source_url) DO UPDATE SET
                                enabled=true,next_attempt_at=now(),expected_category=excluded.expected_category,expected_name=excluded.expected_name,
                                expected_weight_label=excluded.expected_weight_label,conversion_note=excluded.conversion_note,
                                package_weight_g=excluded.package_weight_g,sold_by_weight=excluded.sold_by_weight
                            """,task.id(),candidate.url(),candidate.sku(),candidate.name(),candidate.grams(),candidate.weighted(),candidate.label(),candidate.note(),candidate.category());
                }
                finish(task,candidates.isEmpty() ? "NO_SAFE_PRODUCT_MATCH" : null);
            });
        } catch (Exception error) {
            String code = error instanceof org.jsoup.HttpStatusException http
                    ? "DISCOVERY_HTTP_" + http.getStatusCode() : "DISCOVERY_SOURCE_UNAVAILABLE";
            finish(task,code);
        }
    }

    List<Candidate> candidates(List<Document> documents, String ingredient) {
        var result=new java.util.ArrayList<Candidate>();
        for (Document document : documents) {
            String category = document.selectFirst("h1") == null ? "" : document.selectFirst("h1").text();
            for (var link : document.select("a[href^=/product/]")) {
                String path = link.attr("href");
                var pathMatch = PRODUCT_PATH.matcher(path);
                if (!pathMatch.matches()) continue;
                String title = title(link);
                if (title.isBlank() || !matcher.matches(ingredient,title)
                        || !matcher.matchesCategory(ingredient,category)) continue;
                String cardText = link.text();
                if (cardText.contains("Нет в наличии")) continue;
                boolean weighted = cardText.toLowerCase(java.util.Locale.ROOT).contains("цена за 1 кг")
                        || cardText.toLowerCase(java.util.Locale.ROOT).contains("/кг");
                String label = measureLabel(title + " " + cardText);
                try {
                    var measure = matcher.measure(ingredient,label,weighted);
                    result.add(new Candidate("https://chizhik.club" + path,pathMatch.group(1),title,
                            measure.grams(),weighted,weighted ? null : label,measure.note(),category));
                } catch (IllegalArgumentException ignored) {
                    // Unknown package units or drained weight remain visible as missing coverage.
                }
            }
        }
        return result.stream().distinct().sorted(java.util.Comparator.comparing(Candidate::url)).toList();
    }

    private String title(org.jsoup.nodes.Element link) {
        for (String selector : List.of("[class*=title]", "[class*=name]", "h2", "h3", "img[alt]")) {
            var element = link.selectFirst(selector);
            if (element == null) continue;
            String value = element.tagName().equals("img") ? element.attr("alt") : element.text();
            if (!value.isBlank()) return value.trim();
        }
        return link.attr("aria-label").trim();
    }

    private String measureLabel(String text) {
        var found = MEASURE.matcher(text.replace('\u00a0',' '));
        String label = "";
        while (found.find()) label = found.group(1) + " " + found.group(2).toLowerCase(java.util.Locale.ROOT);
        return label;
    }

    private void finish(Task task, String error) {
        jdbc.update("""
                UPDATE catalogue_product_discovery SET next_attempt_at=now()+interval '1 day',last_error=?,
                    lease_token=NULL,lease_until=NULL WHERE product_id=? AND lease_token=?
                """,error,task.id(),task.token());
    }

    public List<Coverage> coverage() {
        return jdbc.query("""
                SELECT p.id,p.shopping_name,EXISTS(SELECT 1 FROM catalogue_price_references r
                    WHERE r.product_id=p.id AND r.active AND r.expires_at>now()),d.last_error
                FROM products p LEFT JOIN catalogue_product_discovery d ON d.product_id=p.id
                WHERE EXISTS(SELECT 1 FROM recipe_ingredients i WHERE i.product_id=p.id) ORDER BY p.shopping_name,p.id
                """,(rs,row) -> new Coverage(rs.getLong(1),rs.getString(2),rs.getBoolean(3),rs.getString(4)));
    }

    private record Task(long id,String name,String categoryCode,UUID token) {}
    record Candidate(String url,String sku,String name,int grams,boolean weighted,String label,String note,String category) {}
    public record Coverage(long productId,String productName,boolean priced,String lastError) {}
}
