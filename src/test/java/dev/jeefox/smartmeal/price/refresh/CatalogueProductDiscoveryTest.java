package dev.jeefox.smartmeal.price.refresh;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogueProductDiscoveryTest {
    private final CatalogueProductDiscovery discovery=new CatalogueProductDiscovery(null,null,new CatalogueProductMatcher(),null);
    @Test void extractsSafeCandidateWithPackageAndDoesNotTrustArbitraryLinks() {
        String html="""
                <h1>Молочные продукты, яйцо</h1>
                <div class="product-card"><a href="/product/moloko-25-900ml--36296/">
                <span class="product-title">Молоко 2,5% в бутылке, 900 мл</span>
                <span class="product-weight">900 мл</span><span>Цена 100 00 ₽</span></a></div>
                """;
        var result=discovery.candidates(java.util.List.of(Jsoup.parse(html)),"Молоко 2,5%");
        assertEquals(1,result.size()); assertEquals(927,result.getFirst().grams());
        assertEquals("900 мл",result.getFirst().label());
        assertEquals("https://chizhik.club/product/moloko-25-900ml--36296/",result.getFirst().url());
        assertTrue(discovery.candidates(java.util.List.of(Jsoup.parse(html.replace("/product/moloko-25-900ml--36296/","https://evil.invalid/"))),"Молоко 2,5%").isEmpty());
        assertTrue(discovery.candidates(java.util.List.of(Jsoup.parse(html)),"Молоко 3,2%").isEmpty());
    }

    @Test void matchesBuckwheatCatalogueNameAndRejectsReadyMeal() {
        String html = """
                <h1>Крупы и макароны</h1>
                <a href="/product/grechka-yadritsa-900g--3231481/">
                <span class="product-title">Гречка ядрица 900г</span><span>900 г Цена 34 00 ₽</span></a>
                <a href="/product/kasha-500g--123/">
                <span class="product-title">Каша гречневая 500г</span><span>500 г Цена 64 00 ₽</span></a>
                """;
        var result = discovery.candidates(java.util.List.of(Jsoup.parse(html)), "Гречневая крупа");
        assertEquals(1, result.size());
        assertEquals(900, result.getFirst().grams());
        assertEquals("3231481", result.getFirst().sku());
    }
}
