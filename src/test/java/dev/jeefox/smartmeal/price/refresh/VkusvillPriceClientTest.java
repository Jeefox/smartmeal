package dev.jeefox.smartmeal.price.refresh;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VkusvillPriceClientTest {
    private final VkusvillPriceClient client = new VkusvillPriceClient(new ObjectMapper());
    private final CatalogueRefreshSource source = new CatalogueRefreshSource(1,10,
            "https://vkusvill.ru/goods/makarony-spagetti-781/","781","Макароны",400,false,UUID.randomUUID());

    private String html() {
        return """
                <script type="application/ld+json">{"@type":"Product","name":"Макароны","sku":781,
                "url":"https://vkusvill.ru/goods/makarony-spagetti-781/","offers":{"@type":"Offer",
                "url":"https://vkusvill.ru/goods/makarony-spagetti-781/","priceCurrency":"RUB","price":90,
                "availability":"https://schema.org/InStock"}}</script>
                <div data-place="tov_detail" data-id="781" data-unit="шт">
                  <span class="js-product-weight-for-log">400 г</span>
                  <span class="js-datalayer-catalog-list-price">90</span>
                  <span class="js-datalayer-catalog-list-price-old">90</span>
                </div>
                """;
    }

    @Test void readsVerifiedRegularPrice() {
        assertEquals(new BigDecimal("90.00"),client.parse(Jsoup.parse(html()),source));
    }

    @Test void acceptsVerifiedSupermarketDisplaySuffixWithoutChangingProductIdentity() {
        String supermarket=html().replace("\"name\":\"Макароны\"","\"name\":\"Макароны / Супермаркет\"");
        assertEquals(new BigDecimal("90.00"),client.parse(Jsoup.parse(supermarket),source));
        assertThrows(IllegalArgumentException.class,() -> client.parse(Jsoup.parse(supermarket.replace("Макароны / Супермаркет","Макароны с соусом / Супермаркет")),source));
    }

    @Test void usesRegularPriceInsteadOfPromotionalPrice() {
        assertEquals(new BigDecimal("120.00"),client.parse(Jsoup.parse(html().replace("price-old\">90", "price-old\">120")),source));
    }

    @Test void rejectsAnotherSkuAndAnotherUrl() {
        assertThrows(IllegalArgumentException.class, () -> client.parse(Jsoup.parse(html().replace("\"sku\":781","\"sku\":782")),source));
        assertThrows(IllegalArgumentException.class, () -> client.parse(Jsoup.parse(html().replace("spagetti-781/","spagetti-782/")),source));
    }

    @Test void rejectsDifferentCurrencyAvailabilityWeightAndSalesUnit() {
        for (String changed : new String[]{html().replace("RUB","USD"),html().replace("InStock","OutOfStock"),
                html().replace("400 г","450 г"),html().replace("data-unit=\"шт\"","data-unit=\"кг\"")}) {
            assertThrows(IllegalArgumentException.class, () -> client.parse(Jsoup.parse(changed),source));
        }
    }

    @Test void rejectsZeroPriceAndContradictingMarkup() {
        assertThrows(IllegalArgumentException.class, () -> client.parse(Jsoup.parse(html().replace("\"price\":90","\"price\":0")),source));
        assertThrows(IllegalArgumentException.class, () -> client.parse(Jsoup.parse(html().replace("list-price\">90","list-price\">99")),source));
    }

    @Test void readsWeightedOfferWithoutInventingPackageSize() {
        var weighted = new CatalogueRefreshSource(1,10,source.url(),source.sku(),source.name(),1000,true,source.leaseToken());
        assertEquals(new BigDecimal("90.00"),client.parse(Jsoup.parse(html().replace("data-unit=\"шт\"","data-unit=\"кг\"")),weighted));
    }

    @Test void verifiesRawCategoryAndCompositionBeforeAcceptingPrice() {
        var raw=new CatalogueRefreshSource(1,10,source.url(),source.sku(),source.name(),400,false,source.leaseToken(),null,null,"Мясо, птица//Курица",null);
        String verified=html().replace("data-unit=\"шт\">", "data-unit=\"шт\"><span class=\"js-datalayer-catalog-list-category\">Мясо, птица//Курица</span>")
                +"<div class=\"Product__composition\"><span class=\"_sostav\">Филе грудки цыпленка-бройлера</span></div>";
        assertEquals(new BigDecimal("90.00"),client.parse(Jsoup.parse(verified),raw));
        assertThrows(IllegalArgumentException.class,() -> client.parse(Jsoup.parse(verified.replace("Филе грудки цыпленка-бройлера","Филе, соль, специи")),raw));
        assertThrows(IllegalArgumentException.class,() -> client.parse(Jsoup.parse(verified.replace("Мясо, птица//Курица","Колбаса, сосиски, деликатесы")),raw));
    }

    @Test void rejectsArbitraryHostBeforeNetworkCall() {
        var invalid = new CatalogueRefreshSource(1,10,"http://localhost/admin","1","Test",400,false,source.leaseToken());
        assertThrows(IllegalArgumentException.class, () -> client.fetch(invalid));
    }
}
