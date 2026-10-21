package dev.jeefox.smartmeal.price.refresh;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads public product and category pages published on the official Chizhik website.
 */
@Component
public class ChizhikOfficialPriceClient implements CataloguePriceClient {
    private static final String ORIGIN = "https://chizhik.club";
    private static final Pattern VISIBLE_PRICE = Pattern.compile(
            "Цена\\s*([0-9][0-9\\s\\u00a0]*)\\s+([0-9]{2})\\s*₽", Pattern.CASE_INSENSITIVE);
    private static final Map<String, List<String>> CATEGORY_PATHS = Map.ofEntries(
            Map.entry("VEGETABLES", List.of("/catalog/ovoshchi-i-frukty--264C39196/")),
            Map.entry("FRUITS", List.of("/catalog/ovoshchi-i-frukty--264C39196/")),
            Map.entry("GRAINS", List.of("/catalog/krupy-i-makarony--264C39160/")),
            Map.entry("DAIRY", List.of("/catalog/molochnye-produkty-yaytso--264C39140/",
                    "/catalog/syry-i-maslo--264C39153/")),
            Map.entry("EGGS", List.of("/catalog/molochnye-produkty-yaytso--264C39140/")),
            Map.entry("MEAT", List.of("/catalog/myaso-i-ptitsa--264C39184/")),
            Map.entry("FISH", List.of("/catalog/ryba-i-moreprodukty--264C39189/")),
            Map.entry("LEGUMES", List.of("/catalog/krupy-i-makarony--264C39160/",
                    "/catalog/konservatsiya--264C39207/")),
            Map.entry("NUTS_SEEDS", List.of("/catalog/orekhi-chipsy-sneki--264C39253/")),
            Map.entry("OTHER", List.of("/catalog/maslo-sousy-spetsii--264C39172/",
                    "/catalog/konservatsiya--264C39207/", "/catalog/krupy-i-makarony--264C39160/")));

    private final ObjectMapper mapper;

    public ChizhikOfficialPriceClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<Document> categoryPages(String categoryCode) throws IOException {
        var paths = CATEGORY_PATHS.getOrDefault(categoryCode, CATEGORY_PATHS.get("OTHER"));
        var pages = new java.util.ArrayList<Document>();
        for (String path : paths) {
            pages.add(connect(ORIGIN + path));
        }
        return pages;
    }

    @Override
    public boolean supports(CatalogueRefreshSource source) {
        if (source.url() == null) return false;
        try {
            var uri = java.net.URI.create(source.url());
            return "https".equals(uri.getScheme()) && "chizhik.club".equals(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getRawQuery() == null
                    && uri.getFragment() == null && uri.getPath().matches("/product/[a-z0-9-]+--[0-9]+/");
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    @Override
    public BigDecimal fetch(CatalogueRefreshSource source) throws IOException {
        if (!supports(source))
            throw new IOException("CHIZHIK_SOURCE_URL_NOT_ALLOWED");
        Document page = connect(source.url());
        return parse(page, source);
    }

    BigDecimal parse(Document page, CatalogueRefreshSource source) throws IOException {
        if (page.body().text().contains("Нет в наличии")) {
            throw new IOException("CHIZHIK_PRODUCT_UNAVAILABLE");
        }
        for (var script : page.select("script[type=application/ld+json]")) {
            JsonNode body = mapper.readTree(script.data());
            if (!"Product".equals(body.path("@type").asText())) continue;
            String sku = firstText(body, "sku", "gtin", "gtin13", "barcode");
            String name = firstText(body, "name", "title");
            if (!source.sku().equals(sku) || !source.name().equalsIgnoreCase(name)) continue;
            JsonNode offers = body.path("offers");
            if (!"RUB".equals(offers.path("priceCurrency").asText())) {
                throw new IOException("CHIZHIK_CURRENCY_CHANGED");
            }
            if (offers.path("availability").asText().contains("OutOfStock")) {
                throw new IOException("CHIZHIK_PRODUCT_UNAVAILABLE");
            }
            BigDecimal price = firstDecimal(offers, "price");
            if (price == null || price.signum() <= 0) throw new IOException("CHIZHIK_PRICE_NOT_FOUND");
            return price.setScale(2, RoundingMode.UNNECESSARY);
        }
        var heading = page.selectFirst("h1");
        if (heading != null && source.name().equalsIgnoreCase(heading.text())) {
            var match = VISIBLE_PRICE.matcher(page.body().text());
            if (match.find()) {
                String rubles = match.group(1).replaceAll("[\\s\\u00a0]", "");
                BigDecimal price = new BigDecimal(rubles + "." + match.group(2));
                if (match.find() || price.signum() <= 0) {
                    throw new IOException("CHIZHIK_AMBIGUOUS_PRICE");
                }
                return price.setScale(2, RoundingMode.UNNECESSARY);
            }
        }
        throw new IOException("CHIZHIK_PRODUCT_NOT_FOUND_OR_CHANGED");
    }

    private Document connect(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent("SmartMeal/0.1 (public Chizhik catalogue refresh)")
                .timeout(15000)
                .maxBodySize(3_000_000)
                .followRedirects(false)
                .get();
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            if (!node.path(name).isMissingNode() && !node.path(name).asText().isBlank()) {
                return node.path(name).asText();
            }
        }
        return "";
    }

    private BigDecimal firstDecimal(JsonNode node, String... names) {
        for (String name : names) {
            if (!node.has(name) || node.path(name).isNull()) continue;
            try {
                return new BigDecimal(node.path(name).asText());
            } catch (NumberFormatException ignored) {
                // Try the next documented price field.
            }
        }
        return null;
    }
}
