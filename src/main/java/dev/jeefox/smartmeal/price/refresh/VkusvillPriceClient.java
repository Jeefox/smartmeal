package dev.jeefox.smartmeal.price.refresh;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;

/** Only configured public product pages are fetched; redirects and arbitrary hosts are rejected. */
@Component
public class VkusvillPriceClient implements CataloguePriceClient {
    private final ObjectMapper mapper;

    public VkusvillPriceClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Document search(String phrase) throws IOException {
        String query=java.net.URLEncoder.encode(phrase,java.nio.charset.StandardCharsets.UTF_8);
        return Jsoup.connect("https://vkusvill.ru/search/?q="+query)
                .userAgent("SmartMeal/0.1 (catalogue price discovery)")
                .timeout(15000).maxBodySize(2_000_000).followRedirects(false).get();
    }

    @Override
    public boolean supports(CatalogueRefreshSource source) {
        return source.url() != null && source.url().contains("vkusvill.ru");
    }

    public BigDecimal fetch(CatalogueRefreshSource source) throws IOException {
        if (source.ingredientName()!=null) {
            var matcher=new CatalogueProductMatcher();
            if (!matcher.matches(source.ingredientName(),source.name()) || source.expectedCategory()!=null
                    && !matcher.matchesCategory(source.ingredientName(),source.expectedCategory())) {
                throw new IllegalArgumentException("UNSAFE_PRODUCT_MATCH");
            }
        }
        URI uri = URI.create(source.url());
        if (!"https".equals(uri.getScheme()) || !"vkusvill.ru".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getUserInfo() != null
                || uri.getFragment() != null || !uri.getPath().matches("/goods/[a-z0-9-]+-[0-9]+/")) {
            throw new IllegalArgumentException("UNSUPPORTED_SOURCE_URL");
        }
        Document document = Jsoup.connect(source.url()).userAgent("SmartMeal/0.1 (catalogue price refresh)")
                .timeout(15000).maxBodySize(2_000_000).followRedirects(false)
                .header("Cache-Control", "no-cache").get();
        return parse(document, source);
    }

    public BigDecimal parse(Document document, CatalogueRefreshSource source) {
        for (var script : document.select("script[type=application/ld+json]")) {
            var product = mapper.readTree(script.data());
            if (!"Product".equals(product.path("@type").asText())) continue;
            if (!source.sku().equals(product.path("sku").asText())
                    || !source.url().equals(product.path("url").asText())
                    || !normalizedName(source.name()).equals(normalizedName(product.path("name").asText()))) continue;
            var offer = product.path("offers");
            if (!"Offer".equals(offer.path("@type").asText())
                    || !source.url().equals(offer.path("url").asText())
                    || !"RUB".equals(offer.path("priceCurrency").asText())
                    || !"https://schema.org/InStock".equals(offer.path("availability").asText())) {
                throw new IllegalArgumentException("OFFER_UNAVAILABLE_OR_CHANGED");
            }
            var cards = document.select("[data-place=tov_detail][data-id=" + source.sku() + "]");
            if (cards.size() != 1) throw new IllegalArgumentException("AMBIGUOUS_PRODUCT");
            var card = cards.first();
            if (source.expectedCategory() != null && !normalized(source.expectedCategory())
                    .equals(normalized(card.select(".js-datalayer-catalog-list-category").text()))) {
                throw new IllegalArgumentException("PRODUCT_CATEGORY_CHANGED");
            }
            if (source.expectedCategory() != null && (source.expectedCategory().startsWith("Мясо, птица")
                    || source.expectedCategory().startsWith("Рыба"))) {
                String composition=normalized(document.select(".Product__composition ._sostav").text()).toLowerCase(java.util.Locale.ROOT);
                if (composition.isBlank() || java.util.regex.Pattern.compile("соль|специ|перец|маринад|соус|сахар|масло|рассол").matcher(composition).find()) {
                    throw new IllegalArgumentException("RAW_PRODUCT_COMPOSITION_NOT_VERIFIED");
                }
            }
            String unit = card.attr("data-unit");
            if (!unit.equals(source.soldByWeight() ? "кг" : "шт")) {
                throw new IllegalArgumentException("SALES_UNIT_CHANGED");
            }
            if (!source.soldByWeight()) {
                String weight = normalized(card.select(".js-product-weight-for-log").first().text());
                String expectedG = source.weightG() + " г";
                String expectedKg = BigDecimal.valueOf(source.weightG(), 3).stripTrailingZeros().toPlainString().replace('.', ',') + " кг";
                if (source.expectedWeightLabel() != null ? !weight.equals(normalized(source.expectedWeightLabel()))
                        : !weight.equals(expectedG) && !weight.equals(expectedKg)) {
                    throw new IllegalArgumentException("PACKAGE_WEIGHT_CHANGED");
                }
            }
            BigDecimal published = price(offer.path("price").asText());
            BigDecimal current = price(card.select(".js-datalayer-catalog-list-price").first().text());
            BigDecimal regular = price(card.select(".js-datalayer-catalog-list-price-old").first().text());
            if (published.compareTo(current) != 0 || regular.compareTo(current) < 0) {
                throw new IllegalArgumentException("AMBIGUOUS_PRICE");
            }
            // Ignore personal and quantity discounts: the regular public price is used.
            return regular;
        }
        throw new IllegalArgumentException("PRODUCT_NOT_FOUND_OR_CHANGED");
    }

    private BigDecimal price(String value) {
        BigDecimal result = new BigDecimal(value.trim());
        if (result.signum() <= 0 || result.compareTo(new BigDecimal("100000")) > 0) {
            throw new IllegalArgumentException("INVALID_PRICE");
        }
        return result.setScale(2, RoundingMode.UNNECESSARY);
    }

    private String normalizedName(String text) {
        return normalized(text).replaceFirst("(?i)\\s*/\\s*Супермаркет$", "");
    }

    private String normalized(String text) {
        return Jsoup.parse(text).text().replace('\u00a0', ' ').replace('ё','е').replace('Ё','Е').replaceAll("\\s+", " ").trim();
    }
}
