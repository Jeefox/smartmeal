package dev.jeefox.smartmeal.price.refresh;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChizhikOfficialPriceClientTest {
    private final ChizhikOfficialPriceClient client = new ChizhikOfficialPriceClient(new ObjectMapper());
    private final CatalogueRefreshSource source = new CatalogueRefreshSource(1, 10,
            "https://chizhik.club/product/grechka-yadritsa-900g--3231481/", "3231481",
            "Гречка ядрица 900г", 900, false, UUID.randomUUID());

    @Test
    void readsStructuredPublicCataloguePrice() throws Exception {
        var page = Jsoup.parse("""
                <script type="application/ld+json">{"@type":"Product","name":"Гречка ядрица 900г",
                "sku":"3231481","offers":{"priceCurrency":"RUB","price":"34.00"}}</script>
                """);

        assertEquals(new BigDecimal("34.00"), client.parse(page, source));
    }

    @Test
    void fallsBackToVisibleOfficialPagePrice() throws Exception {
        var page = Jsoup.parse("<h1>Гречка ядрица 900г</h1><div>Цена 34 00 ₽</div>");

        assertEquals(new BigDecimal("34.00"), client.parse(page, source));
    }

    @Test
    void rejectsAnotherProductAndMissingPrice() {
        assertThrows(Exception.class,
                () -> client.parse(Jsoup.parse("<h1>Рис 900г</h1><div>Цена 34 00 ₽</div>"), source));
        assertThrows(Exception.class,
                () -> client.parse(Jsoup.parse("<h1>Гречка ядрица 900г</h1>"), source));
    }

    @Test
    void rejectsUnavailableAndAmbiguousVisiblePrices() {
        assertThrows(java.io.IOException.class, () -> client.parse(Jsoup.parse(
                "<h1>Гречка ядрица 900г</h1>Нет в наличии Цена 34 00 ₽"), source));
        assertThrows(java.io.IOException.class, () -> client.parse(Jsoup.parse(
                "<h1>Гречка ядрица 900г</h1>Цена 34 00 ₽ Цена 69 00 ₽"), source));
    }

    @Test
    void rejectsForeignHostsBeforeNetworkRequest() {
        for (String url : new String[]{"https://chizhik.club.evil.invalid/product/item--1/",
                "https://evil.invalid/chizhik", "http://chizhik.club/product/item--1/"}) {
            var invalid = new CatalogueRefreshSource(1, 10, url, "1", "Test", 900, false, UUID.randomUUID());
            assertThrows(java.io.IOException.class, () -> client.fetch(invalid));
        }
    }
}
